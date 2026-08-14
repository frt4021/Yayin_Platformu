#!/usr/bin/env bash
# config.pbtxt'lerdeki ${WHISPER_INSTANCES} gibi yer tutucuları .env'den gelen
# gerçek sayılarla doldurur, SONRA tritonserver'ı başlatır.
#
# NEDEN BURADA (build zamaninda degil): instance_group.count'u degistirmek
# icin imaji YENIDEN KURMAK gerekmesin -- .env'i degistirip
# `docker compose up -d triton` (container'i YENIDEN BASLATMAK, rebuild
# DEGIL) yeterli olsun. Agirliklarin build zamaninda gomulmesiyle (Dockerfile)
# CELISMIYOR: o degismiyor, sadece config.pbtxt'teki sayi degisiyor.
#
# IDEMPOTENT: zaten doldurulmus bir config.pbtxt'te yer tutucu KALMADIGI icin
# envsubst onu oldugu gibi birakir -- container'i defalarca yeniden baslatmak
# guvenli.
set -euo pipefail

: "${WHISPER_INSTANCES:=1}"
: "${MARIAN_TR_INSTANCES:=1}"
: "${MARIAN_DE_INSTANCES:=1}"
: "${MARIAN_RU_INSTANCES:=1}"
export WHISPER_INSTANCES MARIAN_TR_INSTANCES MARIAN_DE_INSTANCES MARIAN_RU_INSTANCES

for config in /models/*/config.pbtxt; do
  envsubst '${WHISPER_INSTANCES} ${MARIAN_TR_INSTANCES} ${MARIAN_DE_INSTANCES} ${MARIAN_RU_INSTANCES}' \
    < "$config" > "${config}.tmp"
  mv "${config}.tmp" "$config"
done

# --exit-on-error=false SART: varsayilanla (true) bir model (orn. VRAM
# yetmedigi icin whisper) yuklenemezse Triton TUM SURECI kapatiyor -- saglikli
# modeller (Marian) de dahil, "unless-stopped" onu tekrar tekrar baslatip
# sonsuz restart dongusune giriyor (olculdu, RestartCount=36). Bu bayrakla
# sadece yuklenemeyen model UNAVAILABLE kalir, digerleri servise devam eder;
# o modele istek gelince Triton temiz bir hata doner (TritonClient.java
# bunu zaten null donup loglayarak karsiliyor -- SttClient'taki ayni desen).
exec tritonserver --model-repository=/models \
  --http-port=8000 --grpc-port=8001 --metrics-port=8002 \
  --cache-config=local,size=134217728 \
  --exit-on-error=false
