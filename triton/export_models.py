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

# stt-worker/app/config.py'deki TRANSLATION_MODELS ile AYNI -- ADLANDIRMA
# TEK BICIMLI DEGIL, formulle uretilemez, esleme sart (bkz. o dosyadaki not).
#
# tr icin standart boyutlu "Helsinki-NLP/opus-mt-en-tr" DENENDI (16 Agustos)
# ama Hugging Face Hub'da artik herkese acik degil (401 -- Helsinki-NLP bu
# kucuk modeli kisitlamis, yalnizca "tc-big" varyanti public kalmis).
# tc-big'e GERI DONULDU -- boyutu diger ikisinin (~1.2GB) ~2.5 kati (~3GB)
# olmasina ragmen calisan tek secenek bu. fp16 export (asagida) hepsinin
# boyutunu yariya indirmesi bekleniyor, bu da tr'nin fazlaligini kismen
# telafi ediyor.
TRANSLATION_MODELS: dict[str, str] = {
    "tr": "Helsinki-NLP/opus-mt-tc-big-en-tr",
    "de": "Helsinki-NLP/opus-mt-en-de",
    "ru": "Helsinki-NLP/opus-mt-en-ru",
}

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
    # dtype="fp16" (16 Agustos DENEME): agirliklari yariya indirmesi
    # bekleniyor -- ONNX Runtime CUDA'da fp16'yi native destekliyor. Export
    # bu makinede GPU'suz (device="cpu") calisiyor; main_export CPU'da da
    # torch_dtype=float16 ile modeli yukleyip export ediyor (CPU fp16 matmul
    # bu imajda dogrulandi, calisiyor). Kalite etkisi OLCULMEDI.
    main_export(
        model_name_or_path=repo,
        output=out,
        task="text2text-generation-with-past",
        dtype="fp16",
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
