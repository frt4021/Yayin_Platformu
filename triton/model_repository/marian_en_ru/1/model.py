"""
Triton Python backend — Marian (ONNX, optimum ile) sarmalayıcısı.

stt-worker/app/translate.py'deki Translator._translate_many mantığının
Triton'a taşınmış hali: modelin kendisi ONNX formatında ve hesaplama
onnxruntime CUDAExecutionProvider üzerinden yapılıyor (kullanıcının açıkça
seçtiği yol) ama seq2seq generate() döngüsünü (autoregressive decode + beam
search) saf bir `backend: onnxruntime` config'i KURAMADIĞI için (Triton'ın
protobuf config dili kontrol akışı ifade edemiyor), orkestrasyon burada
optimum.onnxruntime.ORTModelForSeq2SeqLM ile yapılıyor.

Kanallar arası batching artık BatchCoalescer yerine Triton'ın dynamic_batching'i
tarafından yapılıyor — execute()'a gelen `requests` listesi zaten Triton'ın
biriktirdiği bir batch.

Dosya, marian_en_tr / marian_en_de / marian_en_ru dizinlerinde AYNI —
hangi dile çevireceğini config.pbtxt'teki isimden değil, kendi ağırlık
klasöründen (1/onnx) okuyor.
"""

import logging
import os
import re

import numpy as np
import triton_python_backend_utils as pb_utils

# Marian modelleri cumle duzeyinde egitildi; uzun paragraf verildiginde sonu
# SESSIZCE kirpiliyor. Cumlelere bolup ayri ayri cevirmek hem daha dogru hem
# token sinirini asmiyor (translate.py ile birebir ayni gerekce).
SENTENCE_BOUNDARY = re.compile(r"(?<=[.!?])\s+")

# Marian'in girdi siniri 512 token. Noktalamasiz uzun konusma tek "cumle"
# gorunuyor; kelime sinirindan bolup yarim kelime birakmiyoruz.
MAX_CHARS = 900


def _split(text: str) -> list[str]:
    sentences = [s.strip() for s in SENTENCE_BOUNDARY.split(text.strip()) if s.strip()]
    if not sentences:
        return [text.strip()] if text.strip() else []

    out: list[str] = []
    for sentence in sentences:
        while len(sentence) > MAX_CHARS:
            cut = sentence.rfind(" ", 0, MAX_CHARS)
            if cut <= 0:
                cut = MAX_CHARS
            out.append(sentence[:cut])
            sentence = sentence[cut:].lstrip()
        if sentence:
            out.append(sentence)
    return out


class TritonPythonModel:
    def initialize(self, args):
        from optimum.onnxruntime import ORTModelForSeq2SeqLM
        from transformers import MarianTokenizer

        self._log = logging.getLogger(args["model_name"])
        onnx_path = os.path.join(args["model_repository"], args["model_version"], "onnx")

        device = os.environ.get("STT_DEVICE", "cuda")
        provider = "CUDAExecutionProvider" if device == "cuda" else "CPUExecutionProvider"

        self._log.info("Marian yükleniyor (ONNX): %s (%s)", onnx_path, provider)

        # local_files_only=True SART -- kapali agda indirmeye kalkmasin
        # (translate.py ile ayni gerekce).
        self._tokenizer = MarianTokenizer.from_pretrained(onnx_path, local_files_only=True)
        self._model = ORTModelForSeq2SeqLM.from_pretrained(
            onnx_path, provider=provider, local_files_only=True,
        )

    def execute(self, requests):
        texts = []
        for request in requests:
            # max_batch_size>0 oldugu icin tensor bir batch boyutuyla geliyor
            # (whisper'daki 3 vs 4 boyut hatasiyla ayni kok neden) --
            # reshape(-1) hem [1] hem [1,1] sekillerinde tek elemani verir.
            raw = pb_utils.get_input_tensor_by_name(request, "SOURCE_TEXT").as_numpy().reshape(-1)[0]
            texts.append(raw.decode("utf-8") if isinstance(raw, (bytes, bytearray)) else str(raw))

        # translate.py'deki _translate_many ile ayni fikir: her metnin
        # cumleleri TEK yiginda cevrilir, sonra sinirlarla geri bolunur --
        # sabit maliyet (kernel acma, padding hazirligi) N kez degil 1 kez
        # odeniyor.
        tumu = [_split(t) if t.strip() else [] for t in texts]
        duz_cumleler = [s for sentences in tumu for s in sentences]

        if duz_cumleler:
            batch = self._tokenizer(duz_cumleler, return_tensors="pt",
                                     padding=True, truncation=True, max_length=512)
            generated = self._model.generate(**batch, max_length=512, num_beams=1)
            parts = self._tokenizer.batch_decode(generated, skip_special_tokens=True)
        else:
            parts = []

        sonuclar = []
        i = 0
        for sentences in tumu:
            n = len(sentences)
            sonuclar.append(" ".join(p.strip() for p in parts[i:i + n] if p.strip()))
            i += n

        return [
            pb_utils.InferenceResponse(output_tensors=[
                pb_utils.Tensor("TRANSLATED_TEXT", np.array([s.encode("utf-8")], dtype=object)),
            ])
            for s in sonuclar
        ]
