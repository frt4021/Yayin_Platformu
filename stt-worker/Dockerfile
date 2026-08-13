# Yayın Merkezi — STT servisi
#
# NEDEN AYRI KONTEYNER
#   faster-whisper + CUDA kütüphaneleri + modeller ~5-6 GB eder ve GPU
#   yalnızca buraya gerekiyor. video-worker'ı bu boyuta çıkarmanın ve onu
#   GPU'ya bağlamanın anlamı yok — o konteyner GPU'suz makinelerde de
#   çalışmalı.
#
# CPU / GPU SEÇİMİ: TEK DEĞİŞKEN
#   STT_DEVICE=cpu   -> python:3.11-slim  + CPU torch
#   STT_DEVICE=cuda  -> nvidia/cuda:...   + CUDA torch
#
#   Taban imaj ve torch indeksi BURADA türetiliyor. Ayrı ayrı verilseydi
#   (taban imaj CUDA ama torch CPU gibi) sessizce CPU'da çalışan bir GPU
#   kurulumu ortaya çıkardı — hata vermez, yalnızca 40 kat yavaş olurdu.

ARG STT_DEVICE=cpu

# --- Olası tabanlar ---
FROM python:3.11-slim AS taban-cpu
ENV TORCH_INDEX=https://download.pytorch.org/whl/cpu

# -runtime etiketi bilerek: -devel derleyiciyi de içeriyor ve ~3 GB daha
# büyük. Bize hazır kütüphaneler yetiyor.
FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04 AS taban-cuda
# cu124 DEGIL: o indeksteki torch 2.4-2.6 serisinin tumu
# nvidia-cudnn-cu12==9.1.0.70'e sabit bagli ve bu tam surum PyPI'dan
# kaldirildi (dis, gecici bir kirlma -- olculdu, bu Dockerfile ONCEDEN
# ayni satirla basariyla kuruluyordu). cu126 farkli bir cudnn pinliyor.
ENV TORCH_INDEX=https://download.pytorch.org/whl/cu126

# --- Seçilen taban ---
FROM taban-${STT_DEVICE} AS temel

ARG STT_MODEL=small
ARG STT_TARGET_LANGS=tr,de,ru

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    STT_MODEL_DIR=/models

# CUDA tabanli imajda python yok; slim'de var. Ikisinde de calissin.
#
# gcc de burada ekleniyor: cu126 indeksindeki daha yeni torch, Marian
# generate() sirasinda bazi cekirdekleri Triton (torch.compile alt katmani,
# NVIDIA Triton Inference Server DEGIL) ile JIT-derlemeye calisiyor ve
# derleyici yoksa SESSIZCE degil ama HER cagrida basarisiz oluyor --
# olculdu: 3 dilin UCU DE %100 "Failed to find C compiler" ile dusuyordu.
# Tam "-devel" imaja (~3 GB) gecmek yerine tek paket yetiyor.
RUN if ! command -v python3 >/dev/null 2>&1; then \
      apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-dev gcc libc6-dev && rm -rf /var/lib/apt/lists/*; \
    else \
      apt-get update && apt-get install -y --no-install-recommends \
        python3-dev gcc libc6-dev && rm -rf /var/lib/apt/lists/*; \
    fi

# Ubuntu 22.04'un apt'tan gelen pip'i (22.0.2) --break-system-packages
# bayragini tanimiyor (bu bayrak pip 23.0 ile geldi). Once pip'i
# yukseltiyoruz ki asagidaki komutlar iki tabanda da calissin.
RUN python3 -m pip install --upgrade pip

WORKDIR /app
COPY requirements.txt .

# torch AYRI kuruluyor: indeks tabana gore yukarida secildi.
#
# SURUM SABIT: pinlenmemis "torch" pip'e "en uygun surumu sen sec" demek --
# PyTorch indeksi zaman icinde yeni surumler ekledikce cozumleyici (resolver)
# farkli calisip nvidia-cudnn-cu12 gibi bagimliliklarda cakisma cikarabiliyor
# (olculdu: ayni Dockerfile once basariyla 2.6.0+cu124 kurmustu, sonraki
# build'de ayni satir "ResolutionImpossible" ile patladi). Sabit surum bu
# belirsizligi kapatiyor.
RUN python3 -m pip install --break-system-packages torch --index-url ${TORCH_INDEX} \
 && python3 -m pip install --break-system-packages -r requirements.txt

# --- Modeller imaja gomuluyor ---
# Calisma aninda indirme KAPALI AGDA SESSIZCE BASARISIZ OLUR. Burada
# indirilip asagida offline bayraklariyla kilitleniyor.
#
# app ONCE kopyalaniyor: indirme betigi model adlarini app.config'ten
# okuyor. Ayri listelenselerdi ikisi zamanla ayrisir ve eksik model ancak
# calisma aninda fark edilirdi.
COPY app ./app
COPY download_models.py .
RUN STT_MODEL=${STT_MODEL} STT_TARGET_LANGS=${STT_TARGET_LANGS} \
    python3 download_models.py

# Model eksikse SESSIZCE indirmeye calismak yerine acikca patlasin.
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1

EXPOSE 8100
CMD ["python3", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
