#!/usr/bin/env python3
"""
Modelleri imaja gömmek için indirir. YALNIZCA build sırasında çalışır.

Çalışma anında indirme kapalı ağda sessizce başarısız olur; bu yüzden
modeller build'de yerine konup çalışma anında `local_files_only=True` ile
açılıyor.

Model adları `app.config` içinden geliyor — servis hangi depoyu bekliyorsa
buradan o iniyor. Ayrı listelenselerdi ikisi zamanla ayrışır ve eksik model
ancak çalışma anında fark edilirdi.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.config import SETTINGS  # noqa: E402


def download_whisper() -> None:
    from faster_whisper import download_model

    path = SETTINGS.whisper_path()
    print(f"  Whisper {SETTINGS.model} -> {path}", flush=True)
    download_model(SETTINGS.model, output_dir=path)


def download_translations() -> None:
    from transformers import MarianMTModel, MarianTokenizer

    for language in SETTINGS.target_languages:
        repo = SETTINGS.translation_repo(language)
        path = SETTINGS.translation_path(language)
        print(f"  {repo} -> {path}", flush=True)
        MarianTokenizer.from_pretrained(repo).save_pretrained(path)
        MarianMTModel.from_pretrained(repo).save_pretrained(path)


if __name__ == "__main__":
    try:
        download_whisper()
        download_translations()
    except Exception as e:
        # Sessizce devam etmek, calisma aninda "model yok" hatasiyla
        # karsilasmak demek. Build BURADA patlamali.
        print(f"Model indirilemedi: {e}", file=sys.stderr)
        sys.exit(1)
    print("Modeller hazır.", flush=True)
