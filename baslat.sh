#!/usr/bin/env bash
#
# Yayın Merkezi — tek komutla kurulum ve başlatma.
#
#   ./baslat.sh              kur ve başlat
#   ./baslat.sh --yeniden    imajları yeniden kurarak başlat
#   ./baslat.sh --durdur     durdur
#   ./baslat.sh --sifirla    durdur ve TÜM VERİYİ sil
#
# .env ÜRETİLMEZ — o ayrı bir adım:
#
#   ./yapilandir.sh    donanımı ve LAN adresini bulup .env üretir
#   ./baslat.sh        .env'i kullanarak ayağa kaldırır
#
# Ayrı olmasının sebebi: donanım tespiti her zaman doğru olmayabilir ve
# kullanıcının başlatmadan önce .env'i düzeltebilmesi gerekiyor.

set -euo pipefail

KOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="$KOK/docker-compose.yaml"
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
  # .env URETILMIYOR: yapilandirma ayri bir adim. Sebep, donanim tespitinin
  # her zaman dogru olmamasi -- NVIDIA'li bir sunucuda kullanicinin
  # baslatmadan ONCE araya girip duzeltebilmesi gerekiyor.
  if [ ! -f "$ENV_DOSYASI" ]; then
    kirmizi "  .env bulunamadı."
    echo
    gri "  Önce yapılandırın (donanım ve LAN adresi otomatik bulunur):"
    echo "      ./yapilandir.sh"
    gri "  Sonra .env'i gözden geçirip buraya dönün."
    exit 1
  fi
  gri "  .env bulundu"
  grep -E "^(CHANNELS_ENCODER|VIDEOS_ENCODER)=" "$ENV_DOSYASI" | sed 's/^/    /' || true

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
