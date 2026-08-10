"""
STT servisinin ayarları — hepsi ortam değişkeninden.

Model varyasyonu `.env`'den seçilebilmeli: aynı imaj hem geliştirme
makinesinde (CPU, `small`) hem üretimde (GPU, `large-v3`) çalışacak.
"""

import os
from dataclasses import dataclass, field

# Ses her yerde 16 kHz, tek kanal, s16le.
#
# Bu bir tercih DEGIL, dayatma: Silero-VAD 16 kHz (ya da 8) disinda
# calismiyor, Whisper da girdiyi 16 kHz'e yeniden orneklendiriyor.
#
# DIKKAT: Java tarafindaki VadConfig.SAMPLE_RATE ile AYNI olmak zorunda. Iki
# ayri surec oldugu icin sabit paylasilamiyor; ayrilirlarsa bolut sureleri ve
# zaman damgalari kayar ve bunu hicbir sey uyarmaz.
SAMPLE_RATE = 16_000
BYTES_PER_SAMPLE = 2

# Ingilizce'den hedef dile ceviri modelleri.
#
# ADLANDIRMA TEK BICIMLI DEGIL -- formulle uretilemez, esleme sart:
# "opus-mt-en-tr" HuggingFace'te YOK; Turkce yalnizca Tatoeba Challenge
# (tc-big) varyantinda var. Olculdu: en-tr 401, tc-big-en-tr 200.
#
# tc-big modelleri digerlerinden buyuk (~1 GB / ~300 MB) -- imaj boyutu
# hesaplanirken hesaba katilmali.
TRANSLATION_MODELS: dict[str, str] = {
    "tr": "Helsinki-NLP/opus-mt-tc-big-en-tr",
    "de": "Helsinki-NLP/opus-mt-en-de",
    "ru": "Helsinki-NLP/opus-mt-en-ru",
}


def _split(name: str, default: str) -> list[str]:
    return [p.strip() for p in os.getenv(name, default).split(",") if p.strip()]


@dataclass(frozen=True)
class Settings:
    # --- Whisper ---
    # tiny | base | small | medium | large-v3
    # "Yayina basilabilir" kalite large-v3 istiyor; small ve alti Turkce ve
    # Rusca tarafinda ozel isim ve sayilarda belirgin hata veriyor.
    model: str = os.getenv("STT_MODEL", "small")

    # cpu | cuda
    # Bu makinede GPU yok; large-v3 CPU'da ~0,3-0,5x gercek zaman, yani tek
    # kanali bile tasimaz. Uretimde cuda zorunlu.
    device: str = os.getenv("STT_DEVICE", "cpu")

    # float16 | int8_float16 | int8
    # int8_float16 bellegi yariya indirip ~%30 hiz veriyor. KALITE ETKISI
    # OLCULMELI, varsayilmamali -- kart geldiginde ilk olcum bu olmali.
    compute_type: str = os.getenv("STT_COMPUTE_TYPE", "int8")

    beam_size: int = int(os.getenv("STT_BEAM_SIZE", "5"))

    # Yigin cozumleme. Tek bir sesi parcalara bolup birlikte isliyor; asil
    # kazanc kanallar arasi yiginlamada ve o henuz yok (bkz. stt.py).
    batch_size: int = int(os.getenv("STT_BATCH_SIZE", "8"))

    # Ayni anda kac bolut cozumlenebilir. Sinirsiz birakilirsa bellek doyar.
    max_concurrency: int = int(os.getenv("STT_MAX_CONCURRENCY", "2"))

    # --- Ceviri ---
    # Whisper pivotu sagladigi icin yalnizca Ingilizce'den cikan yonler var.
    # Kaynak dil kumesi genislese bile bu set SABIT kaliyor.
    target_languages: list[str] = field(
        default_factory=lambda: _split("STT_TARGET_LANGS", "tr,de,ru")
    )

    # --- Model dosyalari ---
    # IMAJA GOMULU. Calisma aninda indirme kapali agda sessizce basarisiz
    # olur; offline bayraklari da bunun icin.
    model_dir: str = os.getenv("STT_MODEL_DIR", "/models")

    # --- Servis ---
    port: int = int(os.getenv("STT_PORT", "8100"))
    log_level: str = os.getenv("STT_LOG_LEVEL", "INFO")

    def whisper_path(self) -> str:
        """İmaja gömülü Whisper modelinin yolu."""
        return os.path.join(self.model_dir, "whisper", self.model)

    def translation_path(self, language: str) -> str:
        """İmaja gömülü `EN → <language>` çeviri modelinin yolu."""
        return os.path.join(self.model_dir, "opus", f"en-{language}")

    @staticmethod
    def translation_repo(language: str) -> str:
        """
        HuggingFace deposu — indirme betiği kullanıyor.

        Bilinmeyen dil **sessizce atlanmıyor**: eksik model, o dilde hiç
        altyazı üretilmemesi ve sebebinin hiçbir yerde görünmemesi demek.
        """
        repo = TRANSLATION_MODELS.get(language)
        if repo is None:
            raise KeyError(
                f"'{language}' için çeviri modeli tanımlı değil. "
                f"config.TRANSLATION_MODELS içine ekleyin. "
                f"Bilinenler: {sorted(TRANSLATION_MODELS)}"
            )
        return repo


SETTINGS = Settings()
