#!/usr/bin/env python3
"""
Triton model repository icin agirliklari indirir/donusturur VE Marian
dilleri icin config.pbtxt/model.py'yi kendisi uretir. YALNIZCA build
sirasinda calisir.

stt-worker/download_models.py'nin ayni felsefesi: kapali agda calisma
aninda indirme sessizce basarisiz olur, bu yuzden agirliklar build'de
yerine konup calisma aninda offline bayraklariyla acilir.

Whisper VE Marian ikisi de CTranslate2 formatina donusturuluyor (17 Agustos,
ikinci gecis -- eskiden Marian ONNX/optimum kullaniyordu, bkz. asagidaki
"MARIAN NEDEN CTRANSLATE2'YE TASINDI" notu).

TAM DINAMIK MARIAN (17 Agustos): eskiden her dil icin triton/model_repository/
altinda elle yazilmis, ayri bir config.pbtxt + model.py dizini gerekiyordu --
yeni bir dil eklemek KOD DEGISIKLIGI demekti. Artik ikisi de
triton/templates/'daki TEK sablondan uretiliyor (model.py zaten hicbir dile
ozel kod icermiyordu, config.pbtxt'te yalnizca isim degisiyordu). Sonuc:
STT_TARGET_LANGS'a yeni bir dil kodu eklemek TEK BASINA yeterli.

VARSAYILAN KALIP YOK (17 Agustos, ikinci gecis): oncesinde belirtilmeyen
her dil icin "Helsinki-NLP/opus-mt-en-<dil>" TAHMIN ediliyordu -- CLAUDE.md'nin
"olcmeden sayi verme" ilkesiyle celisiyordu, cunku bu tahmin bazen yanlis
cikiyor (tr: standart surum artik herkese acik degil, 401) ve bu SESSIZCE
degil ama gec, build ortasinda ortaya cikiyordu. Artik MARIAN_MODELS
STT_TARGET_LANGS'taki HER dil icin ZORUNLU: eksik olan dil icin build acikca
hata verip durur, hicbir repo adi tahmin edilmez.

MARIAN NEDEN CTRANSLATE2'YE TASINDI (17 Agustos, ucuncu gecis): ONNX Runtime
+ optimum.onnxruntime.ORTModelForSeq2SeqLM ile calisan Marian, gercek
trafikte (degisken cumle sayisi/uzunlugu) CUDA "caching allocator"in her
yeni tensor sekli icin yeni bellek bloğu acip ASLA GERI VERMEMESI yuzunden
saatler icinde VRAM'i doldurup kartı tikiyordu -- olculdu: 1 saatlik gercek
15-kanal yukunde 590MB'tan 5,1GB'a cikti. Whisper (CTranslate2/faster-whisper)
ayni sorunu YASAMIYOR cunku her girdiyi sabit 30 saniyelik pencereye
dolduruyor -- tensor sekli hic degismiyor, tek blok surekli yeniden
kullaniliyor (olculdu: 164MB, saatlerce sabit). Marian'i da CTranslate2'ye
tasimak ayni sabit-havuz davranisini kazandiriyor.

Ayni model agirliklari kullaniliyor (MARIAN_MODELS'taki HuggingFace repo'su
degismedi) -- degisen yalnizca GPU'da nasil calistirildigi.

Iki teknik detay, ikisi de gercek testle bulundu:
  1. ctranslate2 4.8.1 + transformers 4.48.0 uyusmazligi: TransformersConverter
     her zaman `from_pretrained(..., dtype=...)` cagiriyor ama bu transformers
     surumu MarianMTModel'de boyle bir parametre kabul etmiyor
     (TypeError). Cozum: _DtypeDuzeltilmisConverter, `dtype` kwarg'ini
     cagirmadan once siliyor.
  2. CTranslate2'nin Marian config'i `add_source_eos: false` -- yani kaynak
     metnin sonuna EOS token'ini CTranslate2 EKLEMIYOR, cagiran taraf
     eklemek ZORUNDA. Eklenmezse encoder cikisi bozuluyor ve model sonsuz
     tekrara giriyor (gercek testte gozlemlendi: "Das Wetter ist Wetter ist
     Wetter..." gibi anlamsiz tekrar). Bkz. marian_model.py'deki EOS ekleme
     satiri.
"""

import os
import shutil
import sys

TARGET_LANGS = [p.strip() for p in os.environ.get("STT_TARGET_LANGS", "tr,de,ru").split(",") if p.strip()]
WHISPER_MODEL = os.environ.get("STT_MODEL", "small")

# MARIAN_MODELS formatı: "tr=Helsinki-NLP/opus-mt-tc-big-en-tr,de=Helsinki-NLP/opus-mt-en-de"
# -- STT_TARGET_LANGS'taki HER dil icin bir karsiligi OLMAK ZORUNDA, eksikse
# build hata verir (bkz. _model_repo_for). Varsayilan kalip TAHMIN EDILMIYOR.
def _marian_repolari() -> dict[str, str]:
    ham = os.environ.get("MARIAN_MODELS", "")
    sonuc: dict[str, str] = {}
    for parca in ham.split(","):
        parca = parca.strip()
        if not parca:
            continue
        if "=" not in parca:
            raise ValueError(
                f"MARIAN_MODELS gecersiz parca: '{parca}' -- format 'kod=repo' olmali "
                f"(ornek: 'de=Helsinki-NLP/opus-mt-en-de')"
            )
        dil, repo = parca.split("=", 1)
        sonuc[dil.strip()] = repo.strip()
    return sonuc


MARIAN_MODELS = _marian_repolari()

# fp16 (16 Agustos DENEME) -- VRAM'i yariya indirdi (bkz. docs/altyazi-hata-
# analizi-16-agustos.md) ama kalite etkisi OLCULMEDI. Sorun cikarsa .env'de
# MARIAN_EXPORT_DTYPE=fp32 ile eski davranisa TEK SATIRLA donulebilir,
# kod degismez.
EXPORT_DTYPE = os.environ.get("MARIAN_EXPORT_DTYPE") or "fp16"

# .env'deki MARIAN_EXPORT_DTYPE ismi (eski ONNX doneminden kalma) CTranslate2'nin
# "quantization" degerlerine cevriliyor -- kullanicinin .env'i degistirmesine
# GEREK YOK, isimlendirme burada eslesiyor.
_CT2_QUANTIZATION = {"fp16": "float16", "fp32": "float32"}.get(EXPORT_DTYPE, EXPORT_DTYPE)

REPO_ROOT = os.environ.get(
    "MODEL_REPOSITORY",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "model_repository"),
)

# Sablonlar: config.pbtxt + model.py, Dockerfile'da bu script'ten once
# /build/templates'e kopyalaniyor (bkz. Dockerfile). Yerel calistirmada
# (rebuild disi test) repo icindeki triton/templates'e duser.
TEMPLATE_DIR = os.environ.get(
    "MARIAN_TEMPLATE_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates"),
)


def _model_repo_for(language: str) -> str:
    repo = MARIAN_MODELS.get(language)
    if not repo:
        raise ValueError(
            f"'{language}' dili STT_TARGET_LANGS'ta var ama MARIAN_MODELS'ta karsiligi yok -- "
            f"varsayilan kalip TAHMIN EDILMIYOR, .env'de acikca eklenmeli: "
            f"MARIAN_MODELS=\"{language}=Helsinki-NLP/opus-mt-en-{language},...\""
        )
    return repo


def export_whisper() -> None:
    from faster_whisper import download_model

    path = os.path.join(REPO_ROOT, "whisper", "1", "whisper")
    print(f"  Whisper {WHISPER_MODEL} -> {path}", flush=True)
    download_model(WHISPER_MODEL, output_dir=path)


def _marian_dosyalarini_uret(language: str, model_dir: str) -> None:
    """config.pbtxt + model.py'yi sablondan bu dilin dizinine yazar.

    Eskiden bu ikisi triton/model_repository/marian_en_<dil>/ altinda elle
    tutuluyordu ve Dockerfile'in sonundaki `COPY model_repository /models`
    adimiyla /models'a yaziliyordu. Artik marian_en_* icin boyle bir dizin
    HIC YOK (silindi) -- bu fonksiyon aynı isi build sirasinda, herhangi bir
    dil icin, sablondan uretiyor.
    """
    model_py_src = os.path.join(TEMPLATE_DIR, "marian_model.py")
    config_tmpl_src = os.path.join(TEMPLATE_DIR, "marian_config.pbtxt.tmpl")

    version_dir = os.path.join(model_dir, "1")
    os.makedirs(version_dir, exist_ok=True)
    shutil.copy(model_py_src, os.path.join(version_dir, "model.py"))

    with open(config_tmpl_src, encoding="utf-8") as f:
        config = f.read()
    config = config.replace("__MODEL_NAME__", f"marian_en_{language}")
    with open(os.path.join(model_dir, "config.pbtxt"), "w", encoding="utf-8") as f:
        f.write(config)


def export_marian(language: str) -> None:
    from ctranslate2.converters import TransformersConverter
    from transformers import MarianTokenizer

    repo = _model_repo_for(language)
    model_dir = os.path.join(REPO_ROOT, f"marian_en_{language}")
    out = os.path.join(model_dir, "1", "ctranslate2")

    _marian_dosyalarini_uret(language, model_dir)

    print(f"  {repo} -> {out} (CTranslate2 export, quantization={_CT2_QUANTIZATION})", flush=True)

    # _DtypeDuzeltilmisConverter GEREKLI: ctranslate2 4.8.1'in TransformersConverter'i
    # her zaman from_pretrained(..., dtype=...) cagiriyor ama bu imajdaki
    # transformers==4.48.0 MarianMTModel'de boyle bir parametre TANIMIYOR
    # (TypeError: unexpected keyword argument 'dtype') -- gercek testte bulundu.
    # Kalici cozum kutuphane surumunu yukseltmek olurdu ama bu, Whisper/faster-whisper
    # ile paylasilan transformers/tokenizers surumunu de etkiler; bunun yerine
    # kwarg'i cagirmadan hemen once silen kucuk bir alt sinif yeterli.
    class _DtypeDuzeltilmisConverter(TransformersConverter):
        def load_model(self, model_class, model_name_or_path, **kwargs):
            kwargs.pop("dtype", None)
            return model_class.from_pretrained(model_name_or_path, **kwargs)

    _DtypeDuzeltilmisConverter(repo).convert(out, quantization=_CT2_QUANTIZATION, force=True)
    # local_files_only ile calisma aninda ac agdan hic denenmesin diye ayrica emin oluyoruz.
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
    print(f"Modeller hazir: whisper + {len(TARGET_LANGS)} Marian dili ({', '.join(TARGET_LANGS)}).", flush=True)
