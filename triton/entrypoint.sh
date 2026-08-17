#!/usr/bin/env bash
# config.pbtxt'lerdeki ${WHISPER_INSTANCES}/${MARIAN_INSTANCE_COUNT} gibi yer
# tutucuları .env'den gelen gerçek sayılarla doldurur, SONRA tritonserver'ı
# başlatır.
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
#
# TAM DINAMIK MARIAN (17 Agustos): eskiden her dil icin ayri, sabit isimli
# bir env degiskeni (MARIAN_TR_INSTANCES, MARIAN_DE_INSTANCES, ...) gerekiyordu
# -- yeni bir dil eklemek bu script'i de elle guncellemek demekti. Artik
# /models/marian_en_* dizinleri NE KADAR VARSA hepsi taraniyor, her biri icin
# MARIAN_INSTANCES'taki (format: "tr=2,fr=3") esleme aranip bulunamazsa
# MARIAN_INSTANCES_DEFAULT'a dusuluyor. Yeni bir dil eklemek artik bu
# script'e HIC DOKUNMADAN calisir.
set -euo pipefail

: "${WHISPER_INSTANCES:=1}"
: "${MARIAN_INSTANCES_DEFAULT:=1}"

export WHISPER_INSTANCES
envsubst '${WHISPER_INSTANCES}' < /models/whisper/config.pbtxt > /models/whisper/config.pbtxt.tmp
mv /models/whisper/config.pbtxt.tmp /models/whisper/config.pbtxt

# MARIAN_INSTANCES'i "dil=sayi" ciftlerine ayristir.
declare -A marian_sayilari
IFS=',' read -ra ciftler <<< "${MARIAN_INSTANCES:-}"
for cift in "${ciftler[@]}"; do
  cift="$(echo -n "$cift" | tr -d '[:space:]')"
  [ -z "$cift" ] && continue
  dil="${cift%%=*}"
  sayi="${cift#*=}"
  marian_sayilari["$dil"]="$sayi"
done

for dizin in /models/marian_en_*/; do
  [ -e "${dizin}config.pbtxt" ] || continue
  dil="$(basename "$dizin")"
  dil="${dil#marian_en_}"
  MARIAN_INSTANCE_COUNT="${marian_sayilari[$dil]:-$MARIAN_INSTANCES_DEFAULT}"
  export MARIAN_INSTANCE_COUNT
  envsubst '${MARIAN_INSTANCE_COUNT}' < "${dizin}config.pbtxt" > "${dizin}config.pbtxt.tmp"
  mv "${dizin}config.pbtxt.tmp" "${dizin}config.pbtxt"
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
