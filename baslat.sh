#!/usr/bin/env bash
#
# Yayın Merkezi — tek komutla kurulum ve başlatma.
#
#   ./baslat.sh              kur ve başlat
#   ./baslat.sh --yeniden    imajları yeniden kurarak başlat
#   ./baslat.sh --durdur     durdur
#   ./baslat.sh --sifirla    durdur ve TÜM VERİYİ sil
#
# Yeni bir makinede hiçbir şey ayarlamaya gerek yok: .env yoksa üretilir,
# makinenin LAN adresi ve GPU'su kendiliğinden bulunur.

set -euo pipefail

KOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="$KOK/src/main/docker/docker-compose.yaml"
ENV_DOSYASI="$KOK/.env"

kirmizi() { printf '\033[31m%s\033[0m\n' "$*"; }
yesil()   { printf '\033[32m%s\033[0m\n' "$*"; }
mavi()    { printf '\033[34m%s\033[0m\n' "$*"; }
gri()     { printf '\033[90m%s\033[0m\n' "$*"; }

baslik() { echo; mavi "── $* ─────────────────────────────────"; }

# ---------------------------------------------------------------- ön koşullar

on_kosullar() {
  local eksik=0
  for komut in docker java; do
    if ! command -v "$komut" >/dev/null 2>&1; then
      kirmizi "  eksik: $komut"
      eksik=1
    fi
  done
  if ! docker compose version >/dev/null 2>&1; then
    kirmizi "  eksik: docker compose (v2)"
    eksik=1
  fi
  if ! docker info >/dev/null 2>&1; then
    kirmizi "  Docker çalışmıyor ya da bu kullanıcı erişemiyor."
    gri    "  Deneyin: sudo usermod -aG docker \$USER  (sonra oturumu yeniden açın)"
    eksik=1
  fi
  [ "$eksik" -eq 0 ] || { echo; kirmizi "Ön koşullar eksik, çıkılıyor."; exit 1; }
  yesil "  docker, docker compose, java — tamam"
}

# ------------------------------------------------------------------- keşifler

# Makinenin LAN adresi. Bu adres TARAYICIDA açılıyor: HLS ve MinIO adresleri
# bundan türüyor. "localhost" yazılsaydı ağdaki başka bir cihaz onu KENDİ
# makinesi sanardı ve ne yayın ne indirme çalışırdı.
lan_adresi() {
  local ip
  ip="$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[\d.]+' || true)"
  [ -n "$ip" ] || ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [ -n "$ip" ] || ip="localhost"
  echo "$ip"
}

# Hangi donanım kodlayıcısı kullanılabilir.
#
# Kodlama mediamtx ve video-worker konteynerlerinde yapılıyor; buradaki tespit
# host'a bakıyor çünkü konteynerlere aygıtı geçiren de host.
kodlayici_bul() {
  if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then
    echo "NVENC"
  elif [ -e /dev/dri/renderD128 ]; then
    echo "VAAPI"
  else
    echo "YAZILIM"
  fi
}

# --------------------------------------------------------------------- .env

env_uret() {
  local ip kodlayici
  ip="$(lan_adresi)"
  kodlayici="$(kodlayici_bul)"

  gri "  LAN adresi : $ip"
  gri "  kodlayıcı  : $kodlayici"

  # Kodlayıcıya göre konteyner aygıt ayarları.
  local runtime="runc" nv_devices="" nv_caps="" media_dev="/dev/null:/dev/null"
  local worker_dev="/dev/null:/dev/null" videos_enc="YAZILIM"

  case "$kodlayici" in
    NVENC)
      runtime="nvidia"; nv_devices="all"; nv_caps="video,compute,utility"
      videos_enc="NVENC"
      ;;
    VAAPI)
      media_dev="/dev/dri:/dev/dri"
      # Worker'a aygıt geçirmiyoruz: önizleme klibi zaten saniyeler içinde
      # kodlanıyor ve aygıt bağımlılığı eklemeye değmez.
      ;;
  esac

  cat > "$ENV_DOSYASI" <<EOF
# Yayın Merkezi — baslat.sh tarafından üretildi.
# Elle düzenlenebilir; script mevcut dosyanın ÜZERİNE YAZMAZ.

QUARKUS_PROFILE=prod

# --- Veritabanı ---
POSTGRES_USER=app_user
POSTGRES_PASSWORD=yayin_db_parola
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=keycloak_db_parola

# --- Keycloak ---
# Bu secret realm-export.json içine de gömülü; ikisi AYNI olmak zorunda.
KEYCLOAK_CLIENT_SECRET=12345678
KEYCLOAK_CLIENT_ID=Yayın_App
KEYCLOAK_REALM=YayinYonetimi
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
# İlk yönetici kullanıcının şifresi (realm import sırasında uygulanır).
KEYCLOAK_BOOTSTRAP_PASSWORD=12345678

# --- Nesne depolama ---
MINIO_ROOT_USER=minio_admin
MINIO_ROOT_PASSWORD=minio_admin_parola

# --- TARAYICIDA açılan adresler ---
# Makinenin LAN adresi kullanılıyor: hem bu bilgisayardan hem ağdaki
# cihazlardan aynı adres çalışsın diye. Makine IP değiştirirse burası da
# değişmeli (ya da .env silinip script yeniden çalıştırılmalı).
MINIO_PUBLIC_URL=http://$ip:9000
MEDIAMTX_HLS_BASE_URL=http://$ip:8888
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://$ip:3000

# --- Donanım kodlayıcı (otomatik tespit: $kodlayici) ---
CHANNELS_ENCODER=$kodlayici
VIDEOS_ENCODER=$videos_enc
CONTAINER_RUNTIME=$runtime
NVIDIA_VISIBLE_DEVICES=$nv_devices
NVIDIA_DRIVER_CAPABILITIES=$nv_caps
MEDIA_DEVICE=$media_dev
WORKER_MEDIA_DEVICE=$worker_dev
EOF
  yesil "  .env üretildi"
}

# ------------------------------------------------------------------ komutlar

durdur() {
  baslik "Durduruluyor"
  docker compose -f "$COMPOSE" down
  yesil "Durduruldu."
}

sifirla() {
  baslik "SIFIRLAMA"
  kirmizi "  Tüm veritabanları, MinIO içeriği ve DVR kayıtları SİLİNECEK."
  read -r -p "  Emin misiniz? (evet yazın): " onay
  [ "$onay" = "evet" ] || { echo "  Vazgeçildi."; exit 0; }
  docker compose -f "$COMPOSE" down -v
  rm -rf "$KOK/src/main/docker/mediamtx-data/recordings"/* \
         "$KOK/src/main/docker/mediamtx-data/hls"/* 2>/dev/null || true
  yesil "Sıfırlandı."
}

hazir_bekle() {
  local ad="$1" komut="$2" sure="${3:-120}"
  printf '  %-22s' "$ad"
  for ((i = 0; i < sure; i++)); do
    if eval "$komut" >/dev/null 2>&1; then
      yesil "hazır (${i}s)"
      return 0
    fi
    sleep 1
  done
  kirmizi "ZAMAN AŞIMI (${sure}s)"
  return 1
}

baslat() {
  local yeniden="${1:-hayir}"

  baslik "Ön koşullar"
  on_kosullar

  baslik "Yapılandırma"
  if [ -f "$ENV_DOSYASI" ]; then
    gri "  .env zaten var, korunuyor (yeniden üretmek için silin)"
  else
    env_uret
  fi

  baslik "Uygulama paketleniyor"
  gri "  ./mvnw package — ilk çalıştırmada bağımlılıklar inecek, sürebilir"
  (cd "$KOK" && ./mvnw -B -q package -DskipTests)
  yesil "  jar hazır"

  baslik "İmajlar kuruluyor"
  if [ "$yeniden" = "evet" ]; then
    docker compose -f "$COMPOSE" build --no-cache
  else
    docker compose -f "$COMPOSE" build
  fi

  baslik "Servisler başlatılıyor"
  docker compose -f "$COMPOSE" up -d

  baslik "Hazır olması bekleniyor"
  hazir_bekle "postgres"  "docker exec postgres pg_isready -U app_user"
  hazir_bekle "minio"     "curl -sf http://localhost:9000/minio/health/live"
  hazir_bekle "keycloak"  "curl -sf http://localhost:8080/realms/YayinYonetimi" 180
  hazir_bekle "mediamtx"  "curl -sf http://localhost:9997/v3/config/global/get"
  # Backend 401 döner (kimlik ister) — yanıt vermesi yeterli.
  hazir_bekle "backend"   "curl -so /dev/null -w '%{http_code}' http://localhost:8090/api/channels | grep -qE '401|200'" 180
  hazir_bekle "frontend"  "curl -sf http://localhost:3000/"

  local ip
  ip="$(grep -oP 'MEDIAMTX_HLS_BASE_URL=http://\K[^:]+' "$ENV_DOSYASI" || echo localhost)"

  baslik "Hazır"
  echo "  Arayüz      : http://localhost:3000"
  [ "$ip" != "localhost" ] && echo "  Ağdan       : http://$ip:3000"
  echo "  API belgesi : http://localhost:8090/docs"
  echo "  Keycloak    : http://localhost:8080  (admin / admin)"
  echo "  MinIO       : http://localhost:9001"
  echo
  gri "  İlk giriş: Keycloak'ta tanımlı kullanıcı, şifre 12345678"
  gri "  Loglar   : docker compose -f $COMPOSE logs -f backend"
  echo
}

case "${1:-}" in
  --durdur)  durdur ;;
  --sifirla) sifirla ;;
  --yeniden) baslat evet ;;
  "")        baslat hayir ;;
  *)
    echo "Kullanım: $0 [--yeniden | --durdur | --sifirla]"
    exit 1
    ;;
esac
