"""
Triton Python backend — faster-whisper sarmalayıcısı.

stt-worker/app/stt.py'deki Transcriber.transcribe_batch'in Triton'a taşınmış
hali. Kanallar arası batching artık BatchCoalescer (elle yazılmış, asyncio
tabanlı) yerine Triton'ın kendi dynamic_batching'i tarafından yapılıyor:
execute() çağrısına gelen `requests` listesi, config.pbtxt'teki
max_queue_delay_microseconds/preferred_batch_size'a göre Triton'ın zaten
biriktirdiği bir batch.

Kaynak dil bilinmiyor/sınırlanmıyor — task=translate ile hangi dil gelirse
gelsin tek geçişte İngilizce üretiliyor (stt.py'deki gerekçe aynen geçerli).
"""

import logging
import os

import numpy as np
import triton_python_backend_utils as pb_utils

log = logging.getLogger("whisper")


class TritonPythonModel:
    def initialize(self, args):
        from faster_whisper import BatchedInferencePipeline, WhisperModel
        from faster_whisper.transcribe import (
            Tokenizer,
            TranscriptionOptions,
            get_suppressed_tokens,
            pad_or_trim,
        )

        self._pad_or_trim = pad_or_trim
        self._Tokenizer = Tokenizer
        self._TranscriptionOptions = TranscriptionOptions
        self._get_suppressed_tokens = get_suppressed_tokens

        weights_path = os.path.join(args["model_repository"], args["model_version"], "whisper")
        device = os.environ.get("STT_DEVICE", "cuda")
        compute_type = os.environ.get("STT_COMPUTE_TYPE", "int8_float16")
        self._beam_size = int(os.environ.get("STT_BEAM_SIZE", "1"))

        log.info("Whisper yükleniyor: %s (%s, %s)", weights_path, device, compute_type)

        # local_files_only=True SART: model eksikse sessizce indirmeye
        # calismak yerine acikca patlasin (stt.py ile ayni gerekce).
        self._model = WhisperModel(
            weights_path,
            device=device,
            compute_type=compute_type,
            local_files_only=True,
        )
        self._pipeline = BatchedInferencePipeline(model=self._model)

    def execute(self, requests):
        model = self._model

        arrays = []
        for request in requests:
            pcm = pb_utils.get_input_tensor_by_name(request, "PCM_AUDIO").as_numpy()
            # max_batch_size>0 oldugu icin Triton/istemci tensoru bir batch
            # boyutuyla ([1, N]) tasiyor -- duzlestirmezsek feature_extractor
            # fazladan boyutu ONE tasiyip np.stack sonunda 4 boyutlu (beklenen
            # 3) bir dizi uretiyor ve CTranslate2 bunu reddediyor (olculdu:
            # "Expected input features to have 3 dimensions, but got 4").
            arrays.append(pcm.reshape(-1).astype(np.float32) / 32768.0)

        # .transcribe()'in tek-chunk yolundaki AYNI cikarim: feature_extractor
        # + son karenin atilmasi + 3000 kareye pad/trim (stt.py ile birebir ayni).
        features = np.stack([
            self._pad_or_trim(model.feature_extractor(audio)[..., :-1])
            for audio in arrays
        ])

        # Dil placeholder'i: generate_segment_batched multilingual=True'da
        # bunu OGE BASINA gercek tespit edilen dille degistiriyor.
        tokenizer = self._Tokenizer(
            model.hf_tokenizer, model.model.is_multilingual,
            task="translate", language="en",
        )
        options = self._TranscriptionOptions(
            beam_size=self._beam_size, best_of=5, patience=1,
            length_penalty=1, repetition_penalty=1, no_repeat_ngram_size=0,
            log_prob_threshold=-1.0, no_speech_threshold=0.6,
            compression_ratio_threshold=2.4, temperatures=[0.0],
            initial_prompt=None, prefix=None, suppress_blank=True,
            suppress_tokens=self._get_suppressed_tokens(tokenizer, [-1]),
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

        responses = []
        for output, langs in zip(outputs, lang_results):
            text = tokenizer.decode(output["tokens"]).strip()
            token, probability = langs[0]
            language = token[2:-2]

            responses.append(pb_utils.InferenceResponse(output_tensors=[
                pb_utils.Tensor("PIVOT_TEXT", np.array([text.encode("utf-8")], dtype=object)),
                pb_utils.Tensor("SOURCE_LANGUAGE", np.array([language.encode("utf-8")], dtype=object)),
                pb_utils.Tensor("LANGUAGE_CONFIDENCE", np.array([probability], dtype=np.float32)),
            ]))
        return responses
