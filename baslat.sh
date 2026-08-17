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
sari()    { printf '\033[33m%s\033[0m\n' "$*"; }
mavi()    { printf '\033[34m%s\033[0m\n' "$*"; }
gri()     { printf '\033[90m%s\033[0m\n' "$*"; }

baslik() { echo; mavi "── $* ─────────────────────────────────"; }

# .env'den tek bir alan okur.
#
# Dosyayi "source" ETMIYORUZ: icinde tirnaksiz degerler ve yorumlar var,
# kaynaklamak kabuk degiskenlerini beklenmedik sekilde ezebilir.
env_al() {
  local anahtar="$1" varsayilan="${2:-}" deger
  deger="$(grep -m1 "^${anahtar}=" "$ENV_DOSYASI" 2>/dev/null | cut -d= -f2-)"
  echo "${deger:-$varsayilan}"
}

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
  # Port .env'den: alan adiyla calisirken 80, aksi halde 3000.
  local fport alan puny ip
  fport="$(env_al PORT_FRONTEND 3000)"
  hazir_bekle "frontend"  "curl -sf http://localhost:$fport/"

  # --- Ters proxy yollari ---
  #
  # Servislerin TEK TEK ayakta olmasi yetmiyor: kullanici hicbirine dogrudan
  # gitmiyor, hepsine frontend'deki nginx uzerinden ulasiyor. Vekillik yanlis
  # yapilandirilmissa backend saglikli gorunur ama arayuz bos kalir -- bu
  # projede tam olarak yasandi (/hls onek kirpma ve 302 yonlendirme sorunu:
  # "yayinda ama baglanmiyor").
  #
  # Bu yuzden yollarin KENDISI deneniyor.
  local vekil_sorun=0
  vekil_dene() {
    local ad="$1" yol="$2" beklenen="$3"
    local kod
    kod="$(curl -so /dev/null -w '%{http_code}' "http://localhost:$fport$yol" 2>/dev/null || echo 000)"
    if echo "$kod" | grep -qE "$beklenen"; then
      yesil "  $ad ($yol) — $kod"
    else
      kirmizi "  $ad ($yol) — $kod, beklenen $beklenen"
      vekil_sorun=1
    fi
  }

  baslik "Ters proxy yolları"
  # Backend kimlik istiyor: 401 de "vekillik calisiyor" demek.
  vekil_dene "API      " "/api/channels" "401|200"
  vekil_dene "API belge" "/docs"         "200|30[0-9]"
  # HLS'te path yoksa MediaMTX 404 doner -- yine de vekilligin calistigini
  # gosterir. 502/504 ise nginx MediaMTX'e ULASAMIYOR demek.
  vekil_dene "HLS      " "/hls/"         "200|30[0-9]|404"

  if [ "$vekil_sorun" -eq 1 ]; then
    echo
    sari "  ! Bir vekil yolu beklenmedik cevap verdi."
    gri  "    502/504 -> nginx hedefe ulaşamıyor (servis ayakta mı?)"
    gri  "    404 (/api) -> nginx.conf'taki location bloğu eksik ya da yanlış"
    gri  "    Frontend imajı nginx.conf'u GÖMÜLÜ taşıyor; değiştirdiyseniz:"
    gri  "               docker compose build frontend"
    echo
  fi

  ip="$(grep -m1 '^MINIO_PUBLIC_URL=' "$ENV_DOSYASI" | sed 's|.*//||;s|:.*||')"
  alan="$(grep -m1 '^PUBLIC_HOST=' "$ENV_DOSYASI" | cut -d= -f2- || true)"

  # --- triton saglik denetimi ---
  #
  # Iki ayri ariza belirtisi ogrenildi (16 Agustos oturumu,
  # docs/altyazi-hata-analizi-16-agustos.md):
  #
  #   1. "restarting" -- model(ler) belleğe sığmıyor, cekirdek sureci
  #      olduruyor (OOM), Docker yeniden baslatiyor. Log'da OOM
  #      GORUNMEYEBILIR cunku surec disaridan oldurulmus olur.
  #   2. "unhealthy" (Up ama saglik kontrolu FAIL) -- FARKLI bir sebep:
  #      Docker'in varsayilan dosya tanitici limiti (1024) whisper+3
  #      Marian'in Python stub sureclerine yetmeyip "Too many open files"
  #      ile accept() cokebiliyor. docker-compose.yaml'daki triton
  #      servisine ulimits.nofile eklenerek COZULDU; bu kontrol yalnizca
  #      eski bir compose dosyasiyla calisiliyorsa ya da baska bir ortama
  #      tasindiginda ayni hatanin tekrarlanmadigindan emin olmak icin.
  local triton_durum triton_saglik
  triton_durum="$(docker inspect -f '{{.State.Status}}' triton 2>/dev/null || echo yok)"
  triton_saglik="$(docker inspect -f '{{.State.Health.Status}}' triton 2>/dev/null || echo yok)"
  if [ "$triton_durum" = "restarting" ]; then
    echo
    sari "  ! triton yeniden başlama döngüsünde."
    gri  "    En olası sebep: model(ler) belleğe sığmıyor (whisper + 3 marian)."
    gri  "    GPU'da  -> VRAM yetmiyor. WHISPER_INSTANCES/MARIAN_*_INSTANCES'i"
    gri  "               düşürün (.env, sadece 'docker compose up -d triton' yeter,"
    gri  "               REBUILD gerekmez) ya da STT_MODEL=medium / STT_COMPUTE_TYPE=int8."
    gri  "    Model/dil degisikligi sonrasi imaj YENİDEN KURULMALI (agirliklar imaja gömülü):"
    gri  "               docker compose build triton"
    gri  "    Ayrıntılı teşhis geçmişi: docs/altyazi-hata-analizi-16-agustos.md"
    echo
    gri  "    Son loglar:"
    docker logs triton --tail 5 2>&1 | sed 's/^/      /'
    echo
  elif [ "$triton_saglik" = "unhealthy" ]; then
    echo
    sari "  ! triton ayakta ama sağlık kontrolü başarısız (unhealthy)."
    gri  "    Olası sebep 1 -- dosya tanıtıcısı tükendi:"
    gri  "               docker exec triton sh -c 'ls /proc/1/fd | wc -l; cat /proc/1/limits | grep \"open files\"'"
    gri  "               1024/1024 gibi doluysa docker-compose.yaml'daki triton"
    gri  "               servisinin ulimits.nofile ayarı eksik/kaybolmuş demektir."
    gri  "    Olası sebep 2 -- bir model yüklenemedi (STT_TARGET_LANGS ile"
    gri  "               export edilen diller uyuşmuyor olabilir):"
    gri  "               docker logs triton --tail 200 | grep \"successfully loaded\\|failed to load\""
    gri  "    Ayrıntılı teşhis geçmişi: docs/altyazi-hata-analizi-16-agustos.md"
    echo
    gri  "    Son loglar:"
    docker logs triton --tail 5 2>&1 | sed 's/^/      /'
    echo
  fi

  baslik "Hazır"
  if [ -n "$alan" ]; then
    puny="$(python3 -c "import sys;print(sys.argv[1].encode('idna').decode())" "$alan" 2>/dev/null || echo "$alan")"
    echo "  Arayüz      : http://$alan"
    # hosts satiri yoksa alan adi cozulmez ve kullanici "acilmiyor" der.
    # Sessiz kalmak yerine dogrudan cozumu gosteriyoruz.
    if ! grep -q "[[:space:]]$puny\$" /etc/hosts 2>/dev/null; then
      sari "  ! Bu makinenin /etc/hosts dosyasında $puny yok — adres açılmaz."
      gri  "    Çözüm: ./alan-adi-kur.sh --yaz"
    fi
  else
    echo "  Arayüz      : http://localhost:$fport"
    [ -n "$ip" ] && echo "  Ağdan       : http://$ip:$fport"
  fi
  # Adresler VEKIL uzerinden yaziliyor: kullanicinin gidecegi yer burasi ve
  # dogrudan port yazmak, ters proxy'yi atlayan ve alan adiyla calismayan bir
  # aliskanlik yaratiyordu.
  local taban
  if [ -n "$alan" ]; then taban="http://$alan"; else taban="http://localhost:$fport"; fi
  echo "  API belgesi : $taban/docs"
  echo "  HLS yayını  : $taban/hls/<kanal>/index.m3u8"
  echo
  gri  "  Doğrudan (yalnızca teşhis — normal kullanımda gerekmez):"
  gri  "    backend   http://localhost:8090"
  gri  "    Keycloak  http://localhost:8080  (admin / admin)"
  gri  "    MinIO     http://localhost:9001"
  echo
  gri "  İlk giriş: Keycloak'ta tanımlı kullanıcı, şifre 12345678"
  gri "  Loglar   : docker compose -f $COMPOSE logs -f backend"
  local imaj_etiketi
  imaj_etiketi="$(grep -m1 '^IMAGE_TAG=' "$ENV_DOSYASI" 2>/dev/null | cut -d= -f2-)"
  gri "  İmaj etiketi (backend/frontend/video-worker/triton): ${imaj_etiketi:-latest}"
  echo
}

case "${1:-}" in
  --durdur)      durdur ;;
  --sifirla)     sifirla ;;
  --yeniden)     baslat evet ;;
  "")            baslat hayir ;;
  *)
    echo "Kullanım: $0 [--yeniden | --durdur | --sifirla]"
    exit 1
    ;;
esac
