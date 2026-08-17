#!/usr/bin/env python3
"""
Triton model repository icin agirliklari indirir/donusturur. YALNIZCA build
sirasinda calisir.

stt-worker/download_models.py'nin ayni felsefesi: kapali agda calisma
aninda indirme sessizce basarisiz olur, bu yuzden agirliklar build'de
yerine konup calisma aninda offline bayraklariyla acilir.

Whisper: faster-whisper'in kendi CTranslate2 formatinda indiriliyor (ONNX'e
cevrilmiyor -- kullanicinin ONNX karari sadece Marian icin).
Marian: optimum ile ONNX'e export ediliyor (encoder + decoder +
decoder_with_past -- seq2seq generate() dongusu icin ucu de gerekli).
"""

import os
import sys

TARGET_LANGS = [p.strip() for p in os.environ.get("STT_TARGET_LANGS", "tr,de,ru").split(",") if p.strip()]
WHISPER_MODEL = os.environ.get("STT_MODEL", "small")

# .env'den MARIAN_<DIL>_MODEL ile GEZILEBILIR (16 Agustos) -- daha once
# TRANSLATION_MODELS sozlugu sabit kodluydu, model degistirmek icin bu
# dosyayi elle duzenleyip commit atmak gerekiyordu. Asagidaki varsayilanlar
# su anki bilinen, calisan modeller; ".env"de MARIAN_TR_MODEL=... gibi bir
# satir varsa o kullanilir.
#
# tr icin standart boyutlu "Helsinki-NLP/opus-mt-en-tr" DENENDI (16 Agustos)
# ama Hugging Face Hub'da artik herkese acik degil (401 -- Helsinki-NLP bu
# kucuk modeli kisitlamis, yalnizca "tc-big" varyanti public kalmis).
# tc-big VARSAYILAN -- boyutu diger ikisinin (~1.2GB) ~2.5 kati (~3GB)
# olmasina ragmen calisan tek secenek bu; .env ile daha kucuk, erisilebilir
# bir alternatif bulununca kod degismeden denenebilir.
# "or" ile: Dockerfile'daki ARG'lar bos string varsayilanla geliyor
# (docker-compose'da tanimlanmamislarsa) -- os.environ.get(...,varsayilan)
# BOS STRING'i "ayarlanmis" sayip varsayilana DUSMEZDI, "or" bunu duzeltiyor.
TRANSLATION_MODELS: dict[str, str] = {
    "tr": os.environ.get("MARIAN_TR_MODEL") or "Helsinki-NLP/opus-mt-tc-big-en-tr",
    "de": os.environ.get("MARIAN_DE_MODEL") or "Helsinki-NLP/opus-mt-en-de",
    "ru": os.environ.get("MARIAN_RU_MODEL") or "Helsinki-NLP/opus-mt-en-ru",
}

# fp16 (16 Agustos DENEME) -- VRAM'i yariya indirdi (bkz. docs/altyazi-hata-
# analizi-16-agustos.md) ama kalite etkisi OLCULMEDI. Sorun cikarsa .env'de
# MARIAN_EXPORT_DTYPE=fp32 ile eski davranisa TEK SATIRLA donulebilir,
# kod degismez.
EXPORT_DTYPE = os.environ.get("MARIAN_EXPORT_DTYPE") or "fp16"

REPO_ROOT = os.environ.get(
    "MODEL_REPOSITORY",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "model_repository"),
)


def export_whisper() -> None:
    from faster_whisper import download_model

    path = os.path.join(REPO_ROOT, "whisper", "1", "whisper")
    print(f"  Whisper {WHISPER_MODEL} -> {path}", flush=True)
    download_model(WHISPER_MODEL, output_dir=path)


def export_marian(language: str) -> None:
    from optimum.exporters.onnx import main_export
    from transformers import MarianTokenizer

    repo = TRANSLATION_MODELS.get(language)
    if repo is None:
        raise KeyError(
            f"'{language}' icin ceviri modeli tanimli degil. "
            f"triton/export_models.py TRANSLATION_MODELS'a ekleyin. "
            f"Bilinenler: {sorted(TRANSLATION_MODELS)}"
        )

    out = os.path.join(REPO_ROOT, f"marian_en_{language}", "1", "onnx")
    print(f"  {repo} -> {out} (ONNX export)", flush=True)

    # decoder_with_past dahil -- generate()'in autoregressive dongusunde her
    # token icin encoder'i yeniden calistirmamak icin sart, yoksa
    # ORTModelForSeq2SeqLM cok daha yavas calisir.
    #
    # EXPORT_DTYPE (yukarida, .env'den) -- fp16 ONNX Runtime CUDA'da native
    # destekleniyor. Export bu makinede GPU'suz (device="cpu") calisiyor;
    # main_export CPU'da da torch_dtype=float16 ile modeli yukleyip export
    # ediyor (CPU fp16 matmul bu imajda dogrulandi, calisiyor).
    print(f"    (dtype={EXPORT_DTYPE})", flush=True)
    main_export(
        model_name_or_path=repo,
        output=out,
        task="text2text-generation-with-past",
        dtype=EXPORT_DTYPE,
    )
    # main_export tokenizer'i zaten output'a kaydediyor; local_files_only ile
    # calisma aninda ac agdan hic denenmesin diye ayrica emin oluyoruz.
    MarianTokenizer.from_pretrained(repo).save_pretrained(out)


if __name__ == "__main__":
    try:
        export_whisper()
        for lang in TARGET_LANGS:
            export_marian(lang)
    except Exception as e:
        # Sessizce devam etmek, calisma aninda "model yok" hatasiyla
        # karsilasmak demek. Build BURADA patlamali.
        print(f"Model hazirlanamadi: {e}", file=sys.stderr)
        sys.exit(1)
    print("Modeller hazir.", flush=True)
