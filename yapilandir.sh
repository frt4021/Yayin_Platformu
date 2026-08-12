#!/usr/bin/env bash
#
# Yayın Merkezi — yapılandırma (.env üretimi).
#
#   ./yapilandir.sh           .env yoksa üret
#   ./yapilandir.sh --zorla   var olanı ÜZERİNE YAZ
#
# Başlatmadan AYRI bir adım: makinenin donanımı otomatik bulunuyor ama tespit
# her zaman doğru olmayabilir (birden fazla GPU, sürücü eksik, sunucuda farklı
# bir kart). Bu script üretip duruyor; kullanıcı .env'i gözden geçirip
# gerekirse düzeltiyor, sonra ./baslat.sh çalıştırıyor.

set -euo pipefail

KOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DOSYASI="$KOK/.env"

kirmizi() { printf '\033[31m%s\033[0m\n' "$*"; }
yesil()   { printf '\033[32m%s\033[0m\n' "$*"; }
mavi()    { printf '\033[34m%s\033[0m\n' "$*"; }
sari()    { printf '\033[33m%s\033[0m\n' "$*"; }
gri()     { printf '\033[90m%s\033[0m\n' "$*"; }
baslik()  { echo; mavi "── $* ─────────────────────────────────"; }

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

# Alan adını punycode'a çevirir.
#
# IDN alan adları (yayın.com) ağa çıkarken punycode'a dönüşür
# (xn--yayn-nza.com). nginx'in server_name'i ve hosts satırı bu hâlde
# olmalı; Unicode yazılan hiçbir yerde eşleşmez.
punycode() {
  python3 -c "import sys; print(sys.argv[1].encode('idna').decode())" "$1" 2>/dev/null || echo "$1"
}

# Hangi donanım kodlayıcısı kullanılabilir.
#
# Tespit HOST'a bakıyor çünkü konteynerlere aygıtı geçiren de host. Kodlamanın
# kendisi mediamtx ve video-worker konteynerlerinde çalışıyor.
kodlayici_bul() {
  if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then
    echo "NVENC"
  elif [ -e /dev/dri/renderD128 ]; then
    echo "VAAPI"
  else
    echo "YAZILIM"
  fi
}

donanim_raporu() {
  gri "  --- bulunan donanım ---"
  if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then
    nvidia-smi -L 2>/dev/null | sed 's/^/    /'
  else
    gri "    NVIDIA: yok (nvidia-smi çalışmıyor)"
  fi
  if [ -e /dev/dri/renderD128 ]; then
    gri "    /dev/dri/renderD128: var (Intel/AMD)"
  else
    gri "    /dev/dri/renderD128: yok"
  fi
}

env_uret() {
  local ip kodlayici
  ip="$(lan_adresi)"
  kodlayici="$(kodlayici_bul)"

  # Portlar tek yerde: hem PORT_* satirlarina hem de TARAYICIYA giden
  # adreslere ayni degerler yaziliyor. Ikisi elle tutulsaydi, portu
  # degistiren kisi URL'i guncellemeyi unutur ve yayin sessizce kirilirdi.
  # Alan adı verilmişse tarayıcıya giden adresler ondan türüyor ve frontend
  # 80'e alınıyor -- alan adında port yazılmaz.
  local alan="" ; local p_frontend=3000
  if [ -n "${PUBLIC_HOST:-}" ]; then
    alan="$(punycode "$PUBLIC_HOST")"
    p_frontend=80
  fi

  local p_backend=8090 p_keycloak=8080
  local p_minio_api=9000 p_minio_konsol=9001
  # PORT_PLAYBACK yok: DVR kaydi MediaMTX'ten alindi (MinIO'ya tasindi) ve
  # playback sunucusu kapatildi. Bkz. mediamtx.yml "playback: no".
  local p_hls=8888 p_rtsp=8554 p_mediamtx_api=9997
  local p_postgres=5433 p_redis=6379

  # Kodlayıcıya göre konteyner aygıt ayarları.
  local runtime="runc" nv_devices="" nv_caps="" media_dev="/dev/null:/dev/null"
  local worker_dev="/dev/null:/dev/null" videos_enc="YAZILIM"

  # STT varsayilani CPU + small: GPU yoksa large-v3 hic calismaz, small ise
  # mimariyi dogrulamaya yeter.
  local stt_device="cpu" stt_runtime="runc" stt_model="small" stt_compute="int8"
  # Gecikme degerleri: CPU varsayilanlari. NVENC dalinda eziliyor.
  local vad_segment_ms=6000 stt_batch=8 stt_concurrency=2

  case "$kodlayici" in
    NVENC)
      runtime="nvidia"; nv_devices="all"; nv_caps="video,compute,utility"
      videos_enc="NVENC"
      # STT de ayni karti kullanacak. Ayri birakilsaydi NVIDIA'li bir
      # makinede video NVENC'e gecer ama STT sessizce CPU'da kalirdi --
      # large-v3 CPU'da ~0,3-0,5x gercek zaman, yani tek kanali bile
      # tasimaz ve sebebi hicbir yerde gorunmezdi.
      stt_device="cuda"; stt_runtime="nvidia"; stt_model="large-v3"
      stt_compute="int8_float16"
      # Gecikmeyi belirleyen degerler de GPU'ya gore. Bunlar CPU'da
      # birakilirsa GPU alinmis ama gecikme CPU ayarinda kalmis olur:
      #   VAD_MAX_SEGMENT_MS 6000 -> CPU'da KALITEYI korumak icin secilmisti
      #   (kisa pencere Whisper'a baglam birakmiyor). GPU'da kisaltmanin
      #   maliyeti dusuk ve gecikmenin en buyuk parcasi bu.
      # DIKKAT: bunlar OLCULMUS degil, baslangic noktasi.
      # Bkz. docs/altyazi-gpu-olcum.md
      vad_segment_ms=4000; stt_batch=16; stt_concurrency=4
      ;;
    VAAPI)
      media_dev="/dev/dri:/dev/dri"
      # Worker'a aygıt geçirmiyoruz: önizleme klibi zaten saniyeler içinde
      # kodlanıyor ve aygıt bağımlılığı eklemeye değmez.
      ;;
  esac

  # HLS ve CORS: alan adi varsa VEKIL uzerinden (tek origin), yoksa dogrudan
  # mediamtx portundan. Tek origin CORS'u tamamen ortadan kaldiriyor ve
  # izleyicinin 8888'e erisebilmesini gereksiz kiliyor.
  local cors_origins
  # HLS adresi GORELI: ana bilgisayar adini tarayici kendi bulundugu
  # adresten aliyor. Boylece localhost, LAN IP ve alan adi -- ucu de
  # kendiliginden dogru calisiyor ve hangi adresten girildigi onemsiz.
  local hls_base="/hls"
  # PORT SONEKI. Tarayici Origin basligini "scheme://host:port" olarak
  # gonderiyor ve 80 disindaki HER portta ":port" kismi yer aliyor.
  #
  # Eskiden alan adi verildiginde origin PORTSUZ yaziliyordu ve frontend 80
  # disinda bir porta alindiginda hicbir origin eslesmiyor, butun API
  # cagrilari CORS'a takiliyordu. 80'de gorunmuyordu cunku orada tarayici
  # port yazmiyor.
  local sonek=""
  [ "$p_frontend" != "80" ] && sonek=":$p_frontend"

  # Uc erisim yolu da yaziliyor: alan adi, localhost ve LAN IP. Hangisinden
  # girilecegi onceden bilinemiyor.
  if [ -n "$alan" ]; then
    cors_origins="http://$alan$sonek,http://localhost$sonek,http://$ip$sonek"
  else
    cors_origins="http://localhost$sonek,http://$ip$sonek"
  fi

  cat > "$ENV_DOSYASI" <<EOF
# Yayın Merkezi — yapilandir.sh tarafından üretildi.
# Değiştirdikten sonra ./baslat.sh ile başlatın.

QUARKUS_PROFILE=prod

# --- Veritabanı ---
# ÜRETİMDE DEĞİŞTİRİN. İlk açılışta oluşturulur; sonradan değiştirmek için
# ./baslat.sh --sifirla gerekir.
POSTGRES_USER=app_user
POSTGRES_PASSWORD=yayin_db_parola
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=keycloak_db_parola

# --- Keycloak ---
# Bu secret realm-export.json içine de gömülü; ikisi AYNI olmak zorunda.
# admin1 kullanıcısının şifresi de aynı dosyada gömülü (12345678) ve
# kalıcıdır — ilk girişte değiştirme istenmez.
KEYCLOAK_CLIENT_SECRET=12345678
KEYCLOAK_CLIENT_ID=Yayın_App
KEYCLOAK_REALM=YayinYonetimi
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin

# --- Nesne depolama ---
MINIO_ROOT_USER=minio_admin
MINIO_ROOT_PASSWORD=minio_admin_parola

# --- TARAYICIDA açılan adresler ---
# Makinenin LAN adresi kullanılıyor: hem bu bilgisayardan hem ağdaki
# cihazlardan aynı adres çalışsın diye. Makine IP değiştirirse burası da
# değişmeli (ya da .env silinip yapilandir.sh yeniden çalıştırılmalı).
MINIO_PUBLIC_URL=http://$ip:$p_minio_api
MEDIAMTX_HLS_BASE_URL=$hls_base
CORS_ALLOWED_ORIGINS=$cors_origins
PUBLIC_HOST=${PUBLIC_HOST:-}

# --- Host portlari ---
# Yalnizca HOST tarafi; konteyner ici portlar sabit ve degismiyor.
# Bir portu degistirirseniz YUKARIDAKI adresleri de elden gecirin:
#   PORT_FRONTEND  -> CORS_ALLOWED_ORIGINS
#   PORT_MINIO_API -> MINIO_PUBLIC_URL
#   PORT_HLS       -> MEDIAMTX_HLS_BASE_URL
# Bu ucu tarayici kullaniyor; uyusmazlarsa yayin ve indirme sessizce kirilir.
PORT_FRONTEND=$p_frontend
PORT_BACKEND=$p_backend
PORT_KEYCLOAK=$p_keycloak
PORT_MINIO_API=$p_minio_api
PORT_MINIO_CONSOLE=$p_minio_konsol
PORT_HLS=$p_hls
PORT_RTSP=$p_rtsp
PORT_MEDIAMTX_API=$p_mediamtx_api
# Host'ta 5432'yi makinede kurulu PostgreSQL tutabilir; 5433 secildi.
PORT_POSTGRES=$p_postgres
PORT_REDIS=$p_redis

# --- Donanım kodlayıcı (otomatik tespit: $kodlayici) ---
#
# NVIDIA'ya geçmek için bu bloğu şöyle yapın:
#   CHANNELS_ENCODER=NVENC
#   VIDEOS_ENCODER=NVENC
#   CONTAINER_RUNTIME=nvidia
#   NVIDIA_VISIBLE_DEVICES=all
#   NVIDIA_DRIVER_CAPABILITIES=video,compute,utility
#   MEDIA_DEVICE=/dev/null:/dev/null
#   WORKER_MEDIA_DEVICE=/dev/null:/dev/null
#
# Intel/AMD (VAAPI) için:
#   CHANNELS_ENCODER=VAAPI
#   CONTAINER_RUNTIME=runc
#   MEDIA_DEVICE=/dev/dri:/dev/dri
#
# Donanım yoksa üçünü de YAZILIM bırakın (CPU, birkaç kanaldan fazlasında yetmez).
CHANNELS_ENCODER=$kodlayici
VIDEOS_ENCODER=$videos_enc
CONTAINER_RUNTIME=$runtime
NVIDIA_VISIBLE_DEVICES=$nv_devices
NVIDIA_DRIVER_CAPABILITIES=$nv_caps
MEDIA_DEVICE=$media_dev
WORKER_MEDIA_DEVICE=$worker_dev

# --- Depolama: kota ve temizlik ---
# Süreler GÜN ya da SAAT olarak: P30D = 30 gün · 720h = aynı · 0 = KAPALI
# Varsayılan olarak kullanıcı verisi silinmiyor; baskıyı kota kuruyor.
STORAGE_USER_QUOTA_BYTES=21474836480
STORAGE_CLIP_RETENTION=0
STORAGE_SCREENSHOT_RETENTION=0
STORAGE_FAILED_CLIP_RETENTION=P7D
STORAGE_SWEEP_INTERVAL=1h

# --- VAD (ses etkinligi tespiti) — Faz 5.1 ---
# Canli altyazi hatti. Varsayilan ACIK.
#
# DIKKAT: pratikte GPU istiyor. CPU'da olculen uretim gecikmesi 22-32 sn;
# izleyici HLS yuzunden yalnizca 6-12 sn geride oldugu icin altyazi ona
# YETISEMIYOR ve ekranda hic gorunmuyor. Kapsama olcumu bunu loglara
# yaziyor (ALTYAZI KAPSAMA satirlari).
#
# CPU'da denemek bosuna yuk ise kapatin: VAD_ENABLED=false
VAD_ENABLED=true

# Model IMAJA GOMULU olmali; calisma aninda indirme kapali agda sessizce
# basarisiz olur.
VAD_MODEL_PATH=/models/silero_vad.onnx

# DIKKAT: kozmetik bir alan DEGIL, modelin girdi bicimini belirliyor.
#   v4 -> 1536 ornek kare, baglam YOK
#   v5 ->  512 ornek kare, 64 ornek baglam
# Kod su an v5e gore yazili. Yanlis surumde ya ONNX patlar (iyi senaryo)
# ya da SESSIZCE bos altyazi uretir -- olculdu: v5 modeline baglamsiz
# 512 ornek verilince konusma orani %97 yerine %0 cikiyor.
VAD_MODEL_VERSION=v5

# Ayni anda VAD calistirilacak kanal ust siniri. Olculen: kanal basina
# ~%0,8 CPU, 20 kanal ~%20 CPU ve ~1 GB RAM.
VAD_MAX_CHANNELS=20
VAD_SEGMENT_DIR=/vad-bolutler

# --- STT (konusma tanima) — ayri servis ---
# Model varyasyonu: tiny|base|small|medium|large-v3
# "Yayina basilabilir" kalite large-v3 istiyor; small ve alti Turkce ve
# Rusca tarafinda ozel isim ve sayilarda belirgin hata veriyor.
# Gelistirmede small yeterli: mimariyi dogrulamaya yarar.
STT_MODEL=$stt_model

# cpu | cuda — bu makinede GPU yok. large-v3 CPU'da ~0,3-0,5x gercek zaman,
# yani tek kanali bile tasimaz.
STT_DEVICE=$stt_device

# float16 | int8_float16 | int8
# int8_float16 bellegi yariya indirip ~%30 hiz veriyor ama KALITE ETKISI
# OLCULMELI, varsayilmamali. Kart geldiginde ilk olcum bu olmali.
STT_COMPUTE_TYPE=$stt_compute

STT_BEAM_SIZE=5
# Yigin cozumleme: pencereler tek tek gonderilirse GPU surekli bosta bekler.
STT_BATCH_SIZE=$stt_batch
# Es zamanli cozumleme. GPU'da VRAM'e bagli -- olcerek artirin.
STT_MAX_CONCURRENCY=$stt_concurrency

# Hedef diller. Whisper pivotu sagladigi icin yalnizca EN->X modelleri
# gerekiyor; kaynak dil kumesi genislese bile bu set SABIT kalir.
STT_TARGET_LANGS=tr,de,ru

STT_URL=http://stt-worker:8100
VAD_STT_ENABLED=true
PORT_STT=8100

# GPU'ya gecerken IKISI BIRDEN degismeli:
#   STT_DEVICE=cuda    -> taban imaj ve torch surumu bundan turuyor (build)
#   STT_RUNTIME=nvidia -> GPU'yu konteynere acar (calisma zamani)
# Yalnizca biri degistirilirse GPU ya gorunmez ya kullanilamaz.
STT_RUNTIME=$stt_runtime

# --- Canli altyazi gecikmesi ---
#
# KALITE <-> GECIKME EKSENI. Ucu de denenebilir; hangisinin dogru oldugu
# DONANIMA ve kanal sayisina bagli, OLCULMEDEN bilinemez:
#
#   Kalite oncelikli  : VAD_MAX_SEGMENT_MS=6000  STT_MODEL=large-v3
#   Denge             : VAD_MAX_SEGMENT_MS=4000  STT_MODEL=large-v3
#   Gecikme oncelikli : VAD_MAX_SEGMENT_MS=3000  STT_MODEL=medium
#
# Nasil olculecegi: docs/altyazi-gpu-olcum.md
#
# Bolut penceresi gecikmenin EN BUYUK parcasi: cozumleme bolut kapanmadan
# baslamiyor. Kisaltmak gecikmeyi dusuruyor ama Whisper'a birakilan baglami
# da azaltiyor ve metin kalitesi dusuyor.
VAD_MAX_SEGMENT_MS=$vad_segment_ms
VAD_MIN_SILENCE_MS=400
VAD_MIN_EMIT_MS=0
# Zorla kesimde onceki bolutle ortusme: cumle ortasindan kesilince baglam
# tamamen kaybolmasin diye.
VAD_OVERLAP_MS=800

# --- Altyazi kapsama olcumu ---
# Izleyicinin canli kenardan geride olma VARSAYIMI. Tarayici gercek degeri
# olcup bildirdiginde bunun yerine o kullaniliyor; rapor satirinda
# "(olculdu)" ya da "(varsayim)" yaziyor.
ALTYAZI_BUTCE_MS=8000
ALTYAZI_RAPOR_ARALIGI=60s


# VAD ses cekme adresi (ic ag).
MEDIAMTX_RTSP_URL=rtsp://mediamtx:8554

# --- Yol ---
# DVR kayıtları. Üretimde büyük diski gösterin:
# 16 kanal × 7 gün × 6 Mbps ≈ 7,3 TB
# DVR_PATH=/mnt/dvr
EOF
}

# ---------------------------------------------------------------------------

zorla="hayir"
case "${1:-}" in
  --zorla) zorla="evet" ;;
  "") ;;
  *) echo "Kullanım: $0 [--zorla]"; exit 1 ;;
esac

baslik "Yapılandırma"

if [ -f "$ENV_DOSYASI" ] && [ "$zorla" = "hayir" ]; then
  sari "  .env zaten var — dokunulmadı."
  gri  "  Yeniden üretmek için: $0 --zorla"
  echo
  gri  "  Mevcut kodlayıcı ayarı:"
  grep -E "^(CHANNELS_ENCODER|VIDEOS_ENCODER|CONTAINER_RUNTIME)=" "$ENV_DOSYASI" | sed 's/^/    /'
  exit 0
fi

# Veritabanı ve MinIO parolaları, volume ilk oluşturulurken içine gömülüyor.
# Üzerine yeni parolalarla bir .env yazmak, var olan bir kurulumda bağlantıyı
# koparır — servis ayağa kalkar ama "authentication failed" verir.
if [ "$zorla" = "evet" ] && docker volume ls -q 2>/dev/null | grep -q '^yayin-merkezi_postgres_data$'; then
  sari "  DİKKAT: bu makinede zaten kurulu bir veritabanı var."
  gri  "  Parolalar volume ilk oluşturulurken gömüldü; yeni .env farklı parola"
  gri  "  yazarsa bağlantı kopar ('authentication failed')."
  echo
  gri  "  Mevcut parolaları korumak isterseniz üretim sonrası şu satırları"
  gri  "  eski değerleriyle geri yazın:"
  gri  "    POSTGRES_PASSWORD · KEYCLOAK_DB_PASSWORD · MINIO_ROOT_PASSWORD"
  echo
  read -r -p "  Yine de üzerine yazılsın mı? (evet yazın): " onay
  [ "$onay" = "evet" ] || { echo "  Vazgeçildi."; exit 0; }
  # Eskisini yedekle: parolalar geri yazilabilsin.
  cp "$ENV_DOSYASI" "$ENV_DOSYASI.yedek"
  gri "  Eski dosya saklandı: .env.yedek"
  echo
fi

donanim_raporu
echo
gri "  LAN adresi : $(lan_adresi)"
gri "  kodlayıcı  : $(kodlayici_bul)"

env_uret
yesil "  .env üretildi: $ENV_DOSYASI"

# nginx'in server_name'i IMAJA GOMULU. Alan adi degistiginde .env yetmiyor;
# frontend imaji da yeniden kurulmali. Bunu sessizce birakmak, "alan adi
# ayarladim ama calismiyor" ile sonuclanirdi.
if [ -n "${PUBLIC_HOST:-}" ]; then
  gomulu="$(grep -oP 'server_name \K[^;]+' "$KOK/frontend/yayin-frontend/nginx.conf" 2>/dev/null || true)"
  istenen="$(punycode "$PUBLIC_HOST")"
  case " $gomulu " in
    *" $istenen "*) : ;;
    *)
      echo
      sari "  ! nginx.conf içindeki server_name bu alan adını içermiyor."
      gri  "    gömülü : $gomulu"
      gri  "    istenen: $istenen"
      gri  "    frontend/yayin-frontend/nginx.conf dosyasını düzenleyip"
      gri  "    imajı yeniden kurun:  docker compose build frontend"
      gri  "    (sondaki \"_\" hepsini yakaladığı için çalışır ama sanal sunucu"
      gri  "     ayrımı yapılamaz.)"
      ;;
  esac
fi

baslik "Şimdi ne yapmalı"
cat <<'EOF'
  1. .env dosyasını gözden geçirin — özellikle:

       CHANNELS_ENCODER   kanal rendition'ları (mediamtx konteynerinde)
       VIDEOS_ENCODER     küçük resim ve önizleme (video-worker'da)

     Tespit yanlışsa dosyanın içindeki örneklere bakarak düzeltin.
     NVIDIA için host'ta nvidia-container-toolkit kurulu olmalı.

  2. Üretimde parolaları değiştirin (POSTGRES_PASSWORD, MINIO_ROOT_PASSWORD,
     KEYCLOAK_ADMIN_PASSWORD).

  3. Başlatın:

       ./baslat.sh
EOF
echo
