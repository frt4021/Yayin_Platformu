"""
Triton Python backend — Marian (CTranslate2) sarmalayıcısı.

17 Ağustos, üçüncü geçiş: ONNX Runtime'dan (optimum.onnxruntime) CTranslate2'ye
taşındı. Sebep ölçüldü: ONNX Runtime'ın CUDA "caching allocator"ı, değişken
cümle sayısı/uzunluğu yüzünden her yeni tensor şekli için yeni bellek bloğu
açıp bunu HİÇBİR ZAMAN GPU'ya geri vermiyordu — gerçek 15-kanal yükünde 1
saatte 590MB'tan 5,1GB'a çıkıp kartı tıkadı. Whisper (faster-whisper/
CTranslate2) aynı sorunu yaşamıyor çünkü her girdi sabit 30 saniyelik
pencereye dolduruluyor (tensor şekli hiç değişmiyor) — CTranslate2'nin
sabit boyutlu çalışma alanı davranışını Marian'a da taşımak aynı VRAM
sabitliğini kazandırıyor (bkz. export_models.py'deki "MARIAN NEDEN
CTRANSLATE2'YE TAŞINDI" notu).

Kanallar arası batching artık BatchCoalescer yerine Triton'ın dynamic_batching'i
tarafından yapılıyor — execute()'a gelen `requests` listesi zaten Triton'ın
biriktirdiği bir batch.

Bu dosya GERÇEK bir şablon (triton/templates/marian_model.py) —
export_models.py, STT_TARGET_LANGS'taki HER dil için bunu olduğu gibi
kopyalıyor (bkz. o dosya). Hangi dile çevireceğini config.pbtxt'teki
isimden değil, kendi ağırlık klasöründen (1/ctranslate2) okuduğu için
hiçbir dile özel kod içermiyor — yeni bir dil eklemek bu dosyayı hiç
değiştirmeden çalışır.
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
        import ctranslate2
        from transformers import MarianTokenizer

        self._log = logging.getLogger(args["model_name"])
        ct2_path = os.path.join(args["model_repository"], args["model_version"], "ctranslate2")

        device = os.environ.get("STT_DEVICE", "cuda")

        self._log.info("Marian yükleniyor (CTranslate2): %s (%s)", ct2_path, device)

        # local_files_only=True SART -- kapali agda indirmeye kalkmasin
        # (translate.py ile ayni gerekce).
        self._tokenizer = MarianTokenizer.from_pretrained(ct2_path, local_files_only=True)
        self._translator = ctranslate2.Translator(ct2_path, device=device)

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
            # CTranslate2'nin Marian config'i add_source_eos=false -- yani
            # kaynak dizinin sonuna EOS token'ini CTranslate2 KENDISI
            # EKLEMIYOR, cagiran taraf eklemek ZORUNDA. Eklenmezse encoder
            # cikisi bozuluyor ve model sonsuz tekrara giriyor (gercek testte
            # bulundu: "Das Wetter ist Wetter ist Wetter..." gibi anlamsiz
            # tekrar). self._tokenizer.tokenize() ozel token EKLEMEZ, bu
            # yuzden eos_token elle ekleniyor.
            kaynak_tokenler = [
                self._tokenizer.tokenize(cumle) + [self._tokenizer.eos_token]
                for cumle in duz_cumleler
            ]
            sonuclar_ct2 = self._translator.translate_batch(kaynak_tokenler, beam_size=1)
            parts = [
                self._tokenizer.convert_tokens_to_string(r.hypotheses[0])
                for r in sonuclar_ct2
            ]
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
