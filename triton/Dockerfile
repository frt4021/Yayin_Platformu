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
ARG STT_TARGET_LANGS=tr,de,ru

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
# onnxruntime VS onnxruntime-gpu CAKISMASI: faster-whisper CPU-only
# "onnxruntime"i BAGIMLILIK olarak cekiyor, "optimum[onnxruntime-gpu]" ile
# AYNI pip cagrisinda kurulunca ikisi ayni site-packages/onnxruntime/
# klasorune yaziyor ve CPU surumu kazaniyor (olculdu: ValueError "Asked to
# use CUDAExecutionProvider ... available ... CPUExecutionProvider" --
# Marian modelleri hep CPU'ya duserdi, sessizce degil, acikca patliyor).
# Cozum: onnxruntime (CPU) kurulumdan SONRA kaldirilip onnxruntime-gpu
# force-reinstall ediliyor, boylece dosyalar kesin GPU surumunden kalir.
RUN pip install \
    faster-whisper==1.1.1 \
    "optimum[onnxruntime-gpu]" \
    transformers==4.48.0 \
    sentencepiece==0.2.0 \
 && pip uninstall -y onnxruntime \
 && pip install --force-reinstall --no-deps onnxruntime-gpu

# SIRA BILINCLI: export_models.py, config.pbtxt/model.py'ye hic ihtiyac
# DUYMUYOR (kendi yollarini REPO_ROOT'tan turetiyor) -- bu yuzden agir
# indirme/export adimi ONCE, ucuz-ve-sik-degisen model_repository COPY'si
# SONRA geliyor. Sira tersi olsaydi (eskiden oyleydi), model.py'de TEK
# SATIRLIK bir duzeltme bile Docker'in katman onbellegini gecersiz kilip
# ~150 saniyelik Whisper indirme + 3 Marian ONNX export'unu HER SEFERINDE
# tekrar calistirirdi -- olculdu, tam bu yuzden yavasti.
COPY export_models.py /build/export_models.py

RUN STT_MODEL=${STT_MODEL} STT_TARGET_LANGS=${STT_TARGET_LANGS} \
    python3 /build/export_models.py

# Agirlik eksikse SESSIZCE indirmeye calismak yerine acikca patlasin
# (stt-worker ile ayni gerekce).
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

# config.pbtxt + model.py -- SIK DEGISIR, bu yuzden en sonda. COPY var olan
# /models agacini SILMEZ, uzerine YAZAR: yukarida export edilen 1/whisper,
# 1/onnx klasorleri kalir, yalnizca config.pbtxt + 1/model.py eklenir.
COPY model_repository /models
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 8000 8001 8002
ENTRYPOINT ["/entrypoint.sh"]
