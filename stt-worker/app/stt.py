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

        if SETTINGS.batch_size > 1:
            # Yigin ardisik hat, TEK bir sesi parcalara bolup birlikte
            # cozumluyor. 15 saniyelik bir bolutte kazanc sinirli.
            #
            # Asil kazanc KANALLAR ARASI yiginlamada: 20 kanalin bolutleri
            # tek yiginda toplanirsa GPU bosta beklemez. O, istek duzeyinde
            # bir kuyruk gerektiriyor ve HENUZ YOK -- kart geldiginde
            # olculup eklenecek.
            try:
                from faster_whisper import BatchedInferencePipeline
                self._pipeline = BatchedInferencePipeline(model=self._model)
            except ImportError:
                log.warning("BatchedInferencePipeline yok, tekil çözümleme kullanılacak")

    def is_ready(self) -> bool:
        return self._model is not None

    def transcribe(self, pcm: bytes) -> tuple[str, str, float]:
        """
        Bölütü İngilizce metne çevirir.

        :param pcm: 16 kHz, tek kanal, ``s16le`` ham ses
        :return: (İngilizce metin, tespit edilen dil, tespit güveni)
        """
        if self._model is None:
            raise RuntimeError("Model yüklenmedi")

        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0

        # Esszamanlilik siniri: sinirsiz birakilirsa bellek doyar ve butun
        # istekler birden yavaslar. Sirada beklemek, hepsinin bozulmasindan
        # iyi.
        with self._semaphore:
            options = dict(
                task="translate",          # <- hangi dil gelirse gelsin Ingilizce
                beam_size=SETTINGS.beam_size,
                # VAD ZATEN YAPILDI (Silero, Java tarafinda). Ikinci kez
                # kosmak bosa CPU ve bolut sinirlarini bozar.
                vad_filter=False,
            )
            if self._pipeline is not None:
                segments, info = self._pipeline.transcribe(
                    audio, batch_size=SETTINGS.batch_size, **options)
            else:
                segments, info = self._model.transcribe(audio, **options)

            # transcribe TEMBEL bir uretec dondurur; tuketilmeden is
            # yapilmaz. Kilit icinde tuketmek SART, yoksa esszamanlilik
            # siniri hicbir seyi sinirlamaz.
            text = " ".join(s.text.strip() for s in segments).strip()

        return text, info.language, float(info.language_probability)
