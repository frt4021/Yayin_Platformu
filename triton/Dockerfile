# Yayın Merkezi — Triton Inference Server (Whisper + Marian)
#
# NEDEN OZEL IMAJ
#   Stok nvcr.io/nvidia/tritonserver:*-py3 imajinda faster-whisper/
#   ctranslate2/optimum/transformers yok -- stt-worker/Dockerfile'daki
#   "agirliklari build zamaninda imaja gom, calisma aninda offline kal"
#   felsefesinin devami (bkz. ../stt-worker/Dockerfile).
#
# SURUM: TRITON_TAG build sirasinda dogrulanip sabitlenmeli. RTX 4050
# (Ada Lovelace, compute capability 8.9) CUDA 12.x tabanli guncel Triton
# etiketleriyle uyumlu olmali, ama bu Dockerfile yazilirken kesin etiket
# ile GPU uyumu OLCULMEDI -- ilk build'de dogrulanacak.
ARG TRITON_TAG=24.12-py3
FROM nvcr.io/nvidia/tritonserver:${TRITON_TAG}

ARG STT_MODEL=small
# STT_TARGET_LANGS SINIRSIZ: "tr,de,ru,fr,es,..." -- ama her kod icin
# MARIAN_MODELS'ta bir karsiligi OLMAK ZORUNDA, varsayilan kalip TAHMIN
# EDILMIYOR (bkz. export_models.py, 17 Agustos "TAM DINAMIK MARIAN").
ARG STT_TARGET_LANGS=tr,de,ru
# ZORUNLU, format: "tr=Helsinki-NLP/opus-mt-tc-big-en-tr,de=org/model" --
# STT_TARGET_LANGS'taki HER dil icin bir kayit olmali, eksikse build hata
# verip durur.
ARG MARIAN_MODELS=""
ARG MARIAN_EXPORT_DTYPE=fp16

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_REPOSITORY=/models

# gettext-base -- entrypoint.sh'nin config.pbtxt'lerdeki ${WHISPER_INSTANCES}
# gibi yer tutucularini envsubst ile doldurmasi icin.
RUN apt-get update && apt-get install -y --no-install-recommends gettext-base \
    && rm -rf /var/lib/apt/lists/*

# pip'i YUKSELTMEYE CALISMIYORUZ: taban imajdaki pip apt/dpkg ile kurulmus
# (RECORD dosyasi yok), pip kendi kendini uninstall etmeye calisinca
# "Cannot uninstall pip 24.0, RECORD file not found" ile patliyor. Mevcut
# pip 24.0 zaten yeterince guncel, yukseltmeye gerek yok.
#
# optimum[onnxruntime-gpu] ARTIK YOK (17 Agustos, ucuncu gecis): Marian
# CTranslate2'ye tasindi (bkz. export_models.py'deki "MARIAN NEDEN
# CTRANSLATE2'YE TASINDI" notu) -- bu, eskiden burada olan onnxruntime/
# onnxruntime-gpu CAKISMA sorununu (CPU surumunun sessizce kazanmasi) da
# YAPISAL OLARAK ORTADAN KALDIRDI, cunku iki ayri onnxruntime paketi kurma
# ihtiyaci kalmadi. ctranslate2, faster-whisper'in zaten transitive
# bagimliligi -- burada AYRICA belirtiliyor ki faster-whisper bir gun bu
# bagimliligi degistirirse build sessizce kirilmasin.
#
# torch (CPU-only) GEREKLI: ctranslate2'nin TransformersConverter'i,
# donusturmeden ONCE modeli PyTorch olarak yukluyor (MarianMTModel.from_pretrained)
# -- eskiden bunu optimum[onnxruntime-gpu] transitive olarak getiriyordu,
# kaldirilinca "name 'torch' is not defined" ile build patladi (gercek
# testte bulundu). Yalnizca BUILD zamaninda (export_models.py) kullaniliyor,
# calisma anindaki marian_model.py torch'a hic dokunmuyor -- CPU-only surum
# yeterli, CUDA'li torch'un onlarca ekstra GB'ini indirmeye gerek yok.
RUN pip install \
    faster-whisper==1.1.1 \
    ctranslate2 \
    transformers==4.48.0 \
    sentencepiece==0.2.0 \
 && pip install --index-url https://download.pytorch.org/whl/cpu torch

# SIRA BILINCLI: export_models.py, config.pbtxt/model.py'ye hic ihtiyac
# DUYMUYOR (kendi yollarini REPO_ROOT'tan turetiyor) -- bu yuzden agir
# indirme/export adimi ONCE, ucuz-ve-sik-degisen model_repository COPY'si
# SONRA geliyor. Sira tersi olsaydi (eskiden oyleydi), model.py'de TEK
# SATIRLIK bir duzeltme bile Docker'in katman onbellegini gecersiz kilip
# ~150 saniyelik Whisper indirme + 3 Marian ONNX export'unu HER SEFERINDE
# tekrar calistirirdi -- olculdu, tam bu yuzden yavasti.
COPY export_models.py /build/export_models.py
# Sablonlar (config.pbtxt + model.py) -- export_models.py her Marian dili
# icin bunlari kopyalayip dolduruyor, bkz. o dosyadaki not.
COPY templates /build/templates

RUN STT_MODEL=${STT_MODEL} STT_TARGET_LANGS=${STT_TARGET_LANGS} \
    MARIAN_MODELS=${MARIAN_MODELS} MARIAN_EXPORT_DTYPE=${MARIAN_EXPORT_DTYPE} \
    MARIAN_TEMPLATE_DIR=/build/templates \
    python3 /build/export_models.py

# Agirlik eksikse SESSIZCE indirmeye calismak yerine acikca patlasin
# (stt-worker ile ayni gerekce).
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

# SADECE whisper/config.pbtxt icin kaldi (17 Agustos) -- Marian'in
# config.pbtxt/model.py'si artik export_models.py tarafindan sablondan
# uretiliyor, model_repository/ altinda marian_en_* DIYE BIR DIZIN YOK.
# whisper tek, cogaltilmayan bir model oldugu icin sablonlastirmaya gerek
# yok, statik dosya olarak kalmasi yeterli. COPY var olan /models agacini
# SILMEZ, uzerine YAZAR: yukarida export edilen 1/whisper klasoru kalir.
COPY model_repository /models
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8000 8001 8002
ENTRYPOINT ["/entrypoint.sh"]
