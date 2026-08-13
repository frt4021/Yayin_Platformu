"""
faster-whisper sarmalayıcısı.

Kaynak dil **bilinmiyor ve sınırlanmıyor**: Whisper'ın 99 dil desteği tam bu
işe kullanılıyor. `task=translate` ile hangi dil gelirse gelsin tek geçişte
İngilizce üretiliyor; kaynak tarafında çeviri modeli gerekmiyor.

Bilinçli takas: kaynak dil hedef dillerden biriyse (örn. Türkçe yayın), o dil
`TR → EN → TR` yolundan geri geliyor ve özel isim/sayılarda kayıp veriyor.
Karşılığında tek Whisper geçişi ve üç çeviri modeli. Kabul edilemez çıkarsa
tespit sonucu saklandığı için `task=transcribe` dalına geçmek yeterli.
"""

import logging
import threading

import numpy as np

from .config import SETTINGS

log = logging.getLogger(__name__)


class Transcriber:
    """
    Model **tek örnek**, tüm bölütler onu paylaşıyor.

    Model 1,6 GB (int8_float16); kanal başına örnek açmak belleği anlamsızca
    tüketirdi. Sınırlayan şey bellek değil hesap gücü — eşzamanlılık
    `STT_MAX_CONCURRENCY` ile tutuluyor.
    """

    def __init__(self) -> None:
        self._model = None
        self._pipeline = None
        self._semaphore = threading.Semaphore(SETTINGS.max_concurrency)

    def load(self) -> None:
        """
        Modeli belleğe alır.

        Açılışta çağrılıyor: ilk isteğe kadar beklenirse o istek 30+ saniye
        sürer ve zaman aşımına düşer.
        """
        from faster_whisper import WhisperModel

        path = SETTINGS.whisper_path()
        log.info("Whisper yükleniyor: %s (%s, %s)",
                 path, SETTINGS.device, SETTINGS.compute_type)

        # local_files_only=True SART: model eksikse sessizce indirmeye
        # calismak yerine acikca patlasin. Kapali agda indirme zaten
        # basarisiz olur ve sebebi "model yavas yukleniyor" gibi gorunur.
        self._model = WhisperModel(
            path,
            device=SETTINGS.device,
            compute_type=SETTINGS.compute_type,
            local_files_only=True,
        )

        # transcribe_batch SART kosuyor -- kanallar arasi batching'in alt
        # mekanizmasi (generate_segment_batched) bu sinifin uzerinde.
        from faster_whisper import BatchedInferencePipeline
        self._pipeline = BatchedInferencePipeline(model=self._model)

    def is_ready(self) -> bool:
        return self._model is not None

    def transcribe_batch(self, pcm_list: list[bytes]) -> list[tuple[str, str, float]]:
        """
        Birden fazla BAĞIMSIZ bölütü (farklı kanallardan) TEK GPU çağrısında
        çözümler — asıl kazanç budur, tekil `transcribe()`'ın N katı değil.

        <p>Normal akışta N eşzamanlı istek, GPU'da N ayrı forward-pass demek
        — her biri kendi kernel açma/bellek hazırlama maliyetini öder ve
        birbirini bekletir. Ölçüldü: `STT_MAX_CONCURRENCY`'yi 6'dan 20'ye
        çıkarınca GPU util %100'e vurdu ama kapsama %0'a düştü — daha fazla
        eşzamanlı istek kabul etmek GPU'nun sabit hesap gücünü büyütmüyor,
        sadece çekişmeyi artırıyor. Burada N istek TEK forward-pass'te
        birleşiyor; sabit maliyet N kez değil BİR kez ödeniyor.

        <p>`faster-whisper`'ın kendi `BatchedInferencePipeline`'ı TEK bir
        sesin İÇİNDEKİ parçalarını batch'liyor (bkz. {@link #load}). Burada
        AYNI alt mekanizma (`generate_segment_batched`) — kütüphanenin kendi
        `.transcribe()` akışından birebir kopyalanan özellik çıkarma/ayar
        mantığıyla — FARKLI seslere uygulanıyor. Segmentler zaten Java
        tarafında VAD ile kesildiği ve 30 sn'yi hiç aşmadığı için, her biri
        `.transcribe()`'ın tek-chunk'lık normal yolundan geçmiş olsaydı
        üreteceği ÖZELLİK ile birebir aynı özellik üretiliyor — davranış
        değişmiyor, yalnızca N tanesi birlikte işleniyor.

        <p>Dil tespiti: `generate_segment_batched` içeride her öğe için ayrı
        ayrı yapılıyor (`options.multilingual=True`) ama sonucu dışarı
        vermiyor — aynı `encoder_output` üzerinde (ikinci bir forward-pass
        DEĞİL, zaten hesaplanmış çıktı) ayrıca `detect_language` çağırıp
        kendi dil/güven değerimizi çıkarıyoruz.

        :param pcm_list: her biri 16 kHz, tek kanal, ``s16le`` ham ses
        :return: ``pcm_list`` ile AYNI sırada (metin, tespit edilen dil, güven)
        """
        from faster_whisper.transcribe import (
            Tokenizer, TranscriptionOptions, get_suppressed_tokens, pad_or_trim,
        )

        if self._model is None or self._pipeline is None:
            raise RuntimeError("Model veya batch pipeline yüklenmedi")

        model = self._model
        with self._semaphore:
            arrays = [
                np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
                for pcm in pcm_list
            ]
            # ".transcribe()"in tek-chunk yolundaki AYNI cikarim: feature_extractor
            # + son karenin atilmasi + 3000 kareye pad/trim.
            features = np.stack([
                pad_or_trim(model.feature_extractor(audio)[..., :-1])
                for audio in arrays
            ])

            # Dil placeholder'i: generate_segment_batched multilingual=True'da
            # bunu OGE BASINA gercek tespit edilen dille degistiriyor -- ilk
            # deger sadece prompttaki yerini bulmaya yariyor, sonucu etkilemiyor.
            tokenizer = Tokenizer(
                model.hf_tokenizer, model.model.is_multilingual,
                task="translate", language="en",
            )
            options = TranscriptionOptions(
                beam_size=SETTINGS.beam_size, best_of=5, patience=1,
                length_penalty=1, repetition_penalty=1, no_repeat_ngram_size=0,
                log_prob_threshold=-1.0, no_speech_threshold=0.6,
                compression_ratio_threshold=2.4, temperatures=[0.0],
                initial_prompt=None, prefix=None, suppress_blank=True,
                suppress_tokens=get_suppressed_tokens(tokenizer, [-1]),
                prepend_punctuations="\"'“¿([{-",
                append_punctuations="\"'.。,，!！?？:：”)]}、",
                max_new_tokens=None, hotwords=None, word_timestamps=False,
                hallucination_silence_threshold=None,
                condition_on_previous_text=False, clip_timestamps=[],
                prompt_reset_on_temperature=0.5, multilingual=True,
                without_timestamps=True, max_initial_timestamp=0.0,
            )

            encoder_output, outputs = self._pipeline.generate_segment_batched(
                features, tokenizer, options)
            lang_results = model.model.detect_language(encoder_output)

        results = []
        for output, langs in zip(outputs, lang_results):
            text = tokenizer.decode(output["tokens"]).strip()
            token, probability = langs[0]
            results.append((text, token[2:-2], float(probability)))
        return results
