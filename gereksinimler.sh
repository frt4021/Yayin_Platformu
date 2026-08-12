#!/usr/bin/env bash
#
# Yayın Merkezi — gereksinim denetimi ve kurulumu.
#
#   ./gereksinimler.sh          yalnızca DENETLER, hiçbir şey kurmaz
#   ./gereksinimler.sh --kur    eksikleri kurar (sudo ister)
#   ./gereksinimler.sh --kur --gpu   NVIDIA container toolkit'i de kurar
#
# NEDEN VARSAYILAN "YALNIZCA DENETLE"
#   Bu script Docker deposu ekliyor, sistem paketleri kuruyor ve kullanıcıyı
#   docker grubuna alıyor -- yani makineyi kalıcı olarak değiştiriyor.
#   Çalıştıran kişinin ne olacağını ÖNCE görmesi gerekiyor. Sessizce kurmak,
#   "sadece bir bakayım" diyen birinin makinesini değiştirmek olurdu.
#
# NE KURMUYOR (bilerek)
#   Node.js  -- gerekmiyor. Ön yüz, imajın içinde node:22-alpine ile
#               derleniyor (frontend/yayin-frontend/Dockerfile).
#   ffmpeg   -- gerekmiyor. Yalnızca konteynerlerin içinde kullanılıyor;
#               backend ve video-worker imajları kendi ffmpeg'ini taşıyor.
#   NVIDIA sürücüsü -- kurulumu çekirdek modülü ve yeniden başlatma
#               gerektiriyor; script bunu üstlenmemeli. Varlığı denetleniyor,
#               yoksa nasıl kurulacağı söyleniyor.
#
# Sıra:
#   ./gereksinimler.sh --kur   → makineyi hazırlar
#   ./yapilandir.sh            → donanımı bulup .env üretir
#   ./baslat.sh                → ayağa kaldırır

set -euo pipefail

KOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

KUR=0
GPU=0
for arg in "$@"; do
  case "$arg" in
    --kur) KUR=1 ;;
    --gpu) GPU=1 ;;
    -h|--yardim|--help)
      sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "Bilinmeyen seçenek: $arg" >&2; exit 2 ;;
  esac
done

kirmizi() { printf '\033[31m%s\033[0m\n' "$*"; }
yesil()   { printf '\033[32m%s\033[0m\n' "$*"; }
sari()    { printf '\033[33m%s\033[0m\n' "$*"; }
mavi()    { printf '\033[34m%s\033[0m\n' "$*"; }
gri()     { printf '\033[90m%s\033[0m\n' "$*"; }

baslik() { echo; mavi "── $* ─────────────────────────────────"; }

# Eksik zorunlu gereksinimler; sonunda özet ve çıkış kodu buradan.
EKSIK=()
# Eksik ama uygulamayı durdurmayanlar.
UYARI=()

var()   { yesil "  ✓ $*"; }
yok()   { kirmizi "  ✗ $*"; EKSIK+=("$1"); }
zayif() { sari   "  ! $*"; UYARI+=("$1"); }

# ------------------------------------------------------------------ dağıtım

PAKET_YONETICISI=""
tespit_dagitim() {
  if   command -v apt-get >/dev/null 2>&1; then PAKET_YONETICISI="apt"
  elif command -v dnf     >/dev/null 2>&1; then PAKET_YONETICISI="dnf"
  elif command -v pacman  >/dev/null 2>&1; then PAKET_YONETICISI="pacman"
  fi
}

# sudo gerekiyorsa kullan; root isek gerekmiyor.
SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"

calistir() {
  gri "    \$ $*"
  # shellcheck disable=SC2068
  $@
}

# ------------------------------------------------------------------ denetimler

denetle_docker() {
  baslik "Docker"

  if ! command -v docker >/dev/null 2>&1; then
    yok "docker — kurulu değil"
    return
  fi
  var "docker $(docker --version 2>/dev/null | sed 's/Docker version //;s/,.*//')"

  # Compose V2 EKLENTI olarak geliyor ("docker compose"), eski "docker-compose"
  # ayri bir ikili. Bu proje v2 sozdizimi kullaniyor.
  if docker compose version >/dev/null 2>&1; then
    var "docker compose $(docker compose version --short 2>/dev/null)"
  else
    yok "docker-compose-v2 — 'docker compose' eklentisi yok"
  fi

  # Kurulu olmasi yetmiyor, DAEMON'a erisebilmemiz de gerekiyor.
  if docker info >/dev/null 2>&1; then
    var "docker daemon erişilebilir"
  elif id -nG 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
    zayif "docker-oturum — grup üyeliği var ama oturuma yansımamış; çıkıp girin ya da: newgrp docker"
  else
    yok "docker-grup — kullanıcı docker grubunda değil"
  fi
}

denetle_java() {
  baslik "Java"

  if ! command -v java >/dev/null 2>&1; then
    yok "java — kurulu değil (JDK 21+ gerekiyor)"
    return
  fi

  # Cikti bicimi:  openjdk version "21.0.11" 2026-04-21
  #
  # Surum TIRNAK ICINDEN aliniyor. Once satirin tamamindan sayi cekmeyi
  # denedim ve acgozlu eslesme SONDAKI TARIHI yakaladi ("2026") -- yani
  # Java 8 kurulu bir makinede bile denetim gecerdi. Sessiz ve tehlikeli
  # bir hata; olculdu ve duzeltildi.
  local sur ham
  ham="$(java -version 2>&1 | head -1 | sed -E 's/^[^"]*"([^"]+)".*/\1/')"
  case "$ham" in
    # Eski bicim: 1.8.0_392 -> 8. Ilk bileseni almak 1 verirdi.
    1.*) sur="$(echo "$ham" | cut -d. -f2)" ;;
    *)   sur="$(echo "$ham" | cut -d. -f1)" ;;
  esac
  if [ "${sur:-0}" -ge 21 ] 2>/dev/null; then
    var "java $sur"
  else
    yok "java-21 — bulunan sürüm $sur, en az 21 gerekiyor"
    return
  fi

  # javac SART: ./mvnw package derleme yapiyor. Yalnizca JRE kuruluysa java
  # calisir ama derleme "no compiler is provided" ile duser -- ve bu hata
  # mesaji sebebini soylemedigi icin bulmasi zor.
  if command -v javac >/dev/null 2>&1; then
    var "javac (JDK) — derleme yapılabilir"
  else
    yok "jdk — yalnızca JRE var; ./mvnw package derleme için JDK istiyor"
  fi
}

denetle_araclar() {
  baslik "Yardımcı araçlar"

  if command -v curl >/dev/null 2>&1; then
    var "curl"
  else
    yok "curl — baslat.sh servis sağlığını bununla yokluyor"
  fi

  if command -v git >/dev/null 2>&1; then
    var "git"
  else
    zayif "git — sürüm etiketleme ve güncelleme için gerekiyor"
  fi

  # python3 yalnizca IDN alan adi donusumu icin ve baslat.sh'te yedegi var.
  if command -v python3 >/dev/null 2>&1; then
    var "python3 — IDN alan adı dönüşümü"
  else
    zayif "python3 — 'yayın.com' gibi Türkçe alan adı punycode'a çevrilemez"
  fi
}

denetle_donanim() {
  baslik "Donanım hızlandırma (isteğe bağlı)"

  local bulundu=0

  if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then
    var "NVIDIA sürücüsü: $(nvidia-smi -L | head -1 | sed 's/(UUID.*//')"
    bulundu=1
    # Surucu yetmiyor: konteynerin GPU'yu gorebilmesi icin toolkit gerekiyor.
    if docker info 2>/dev/null | grep -qi "nvidia"; then
      var "nvidia-container-toolkit — docker runtime'da kayıtlı"
    else
      zayif "nvidia-toolkit — sürücü var ama Docker GPU'yu göremiyor (CHANNELS_ENCODER=NVENC ve STT GPU'su çalışmaz)"
    fi
  else
    gri "  · NVIDIA: yok"
  fi

  if [ -e /dev/dri/renderD128 ]; then
    var "/dev/dri/renderD128 — VAAPI (Intel/AMD) kullanılabilir"
    bulundu=1
  else
    gri "  · /dev/dri/renderD128: yok"
  fi

  if [ "$bulundu" -eq 0 ]; then
    zayif "donanim-kodlayici — hiçbiri yok; rendition üretimi yazılımda olur (ölçüldü: %14 yerine %142 CPU)"
  fi
}

denetle_kapasite() {
  baslik "Kapasite"

  local bos_gb ram_gb cekirdek
  bos_gb="$(df -BG --output=avail "$KOK" 2>/dev/null | tail -1 | tr -dc '0-9')"
  ram_gb="$(free -g 2>/dev/null | awk '/^Mem:/{print $2}')"
  cekirdek="$(nproc 2>/dev/null || echo '?')"

  gri "  · çekirdek: $cekirdek   RAM: ${ram_gb:-?} GB   boş disk: ${bos_gb:-?} GB"

  # Imajlar: backend 1,37 GB + video-worker 1,37 GB + stt-worker ~9,8 GB
  # + mediamtx 921 MB + altyapi. Olculdu.
  if [ -n "$bos_gb" ] && [ "$bos_gb" -lt 25 ]; then
    zayif "disk — ${bos_gb} GB boş; yalnızca imajlar ~15 GB (stt-worker tek başına 9,8 GB)"
  fi
  if [ -n "$ram_gb" ] && [ "$ram_gb" -lt 8 ]; then
    zayif "ram — ${ram_gb} GB; stt-worker modelleri yükleyemeden yeniden başlar"
  fi
}

# ------------------------------------------------------------------ kurulum

kur_docker() {
  gri "  Docker resmi deposundan kuruluyor (dağıtım paketi çoğu yerde eski ve"
  gri "  compose v2 eklentisini getirmiyor)."
  case "$PAKET_YONETICISI" in
    apt)
      calistir $SUDO apt-get update
      calistir $SUDO apt-get install -y ca-certificates curl gnupg
      calistir $SUDO install -m 0755 -d /etc/apt/keyrings
      # shellcheck disable=SC2086
      curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        | $SUDO gpg --batch --yes --dearmor -o /etc/apt/keyrings/docker.gpg
      calistir $SUDO chmod a+r /etc/apt/keyrings/docker.gpg
      # shellcheck disable=SC1091
      . /etc/os-release
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/${ID} ${VERSION_CODENAME} stable" \
        | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
      calistir $SUDO apt-get update
      calistir $SUDO apt-get install -y docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin
      ;;
    dnf)
      calistir $SUDO dnf -y install dnf-plugins-core
      calistir $SUDO dnf config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo
      calistir $SUDO dnf -y install docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin
      ;;
    pacman)
      calistir $SUDO pacman -Sy --noconfirm docker docker-compose docker-buildx
      ;;
  esac
  calistir $SUDO systemctl enable --now docker
}

kur_docker_grup() {
  gri "  Kullanıcı docker grubuna ekleniyor."
  calistir $SUDO groupadd -f docker
  calistir $SUDO usermod -aG docker "$USER"
  sari "  Grup üyeliği MEVCUT OTURUMA yansımaz — çıkıp girin ya da: newgrp docker"
}

kur_java() {
  gri "  JDK 21 kuruluyor (JRE değil: ./mvnw package derleme yapıyor)."
  case "$PAKET_YONETICISI" in
    apt)    calistir $SUDO apt-get install -y openjdk-21-jdk ;;
    dnf)    calistir $SUDO dnf -y install java-21-openjdk-devel ;;
    pacman) calistir $SUDO pacman -Sy --noconfirm jdk21-openjdk ;;
  esac
}

kur_arac() {
  case "$PAKET_YONETICISI" in
    apt)    calistir $SUDO apt-get install -y "$@" ;;
    dnf)    calistir $SUDO dnf -y install "$@" ;;
    pacman) calistir $SUDO pacman -Sy --noconfirm "$@" ;;
  esac
}

kur_nvidia_toolkit() {
  gri "  NVIDIA container toolkit kuruluyor."
  gri "  NOT: bu SÜRÜCÜ kurmuyor. Sürücü yoksa önce o kurulmalı."
  case "$PAKET_YONETICISI" in
    apt)
      curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
        | $SUDO gpg --batch --yes --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
      curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
        | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
        | $SUDO tee /etc/apt/sources.list.d/nvidia-container-toolkit.list >/dev/null
      calistir $SUDO apt-get update
      calistir $SUDO apt-get install -y nvidia-container-toolkit
      ;;
    dnf)
      curl -fsSL https://nvidia.github.io/libnvidia-container/stable/rpm/nvidia-container-toolkit.repo \
        | $SUDO tee /etc/yum.repos.d/nvidia-container-toolkit.repo >/dev/null
      calistir $SUDO dnf -y install nvidia-container-toolkit
      ;;
    pacman)
      calistir $SUDO pacman -Sy --noconfirm nvidia-container-toolkit
      ;;
  esac
  calistir $SUDO nvidia-ctk runtime configure --runtime=docker
  calistir $SUDO systemctl restart docker
  sari "  .env'de CONTAINER_RUNTIME=nvidia ve MEDIA_DEVICE=/dev/null:/dev/null olmalı."
}

# ------------------------------------------------------------------ akış

echo
mavi "Yayın Merkezi — gereksinim denetimi"
tespit_dagitim

denetle_docker
denetle_java
denetle_araclar
denetle_donanim
denetle_kapasite

baslik "Özet"

if [ ${#EKSIK[@]} -eq 0 ]; then
  yesil "  Zorunlu gereksinimlerin hepsi tamam."
else
  kirmizi "  Eksik zorunlu gereksinim: ${#EKSIK[@]}"
  for e in "${EKSIK[@]}"; do kirmizi "    · $e"; done
fi
if [ ${#UYARI[@]} -gt 0 ]; then
  sari "  Uyarı: ${#UYARI[@]} (uygulama çalışır ama etkilenir)"
fi

# --kur verilmediyse burada bitiyoruz.
if [ "$KUR" -eq 0 ]; then
  if [ ${#EKSIK[@]} -gt 0 ]; then
    echo
    gri "  Kurmak için:  ./gereksinimler.sh --kur"
    [ "$GPU" -eq 0 ] && gri "  NVIDIA toolkit de gerekiyorsa:  ./gereksinimler.sh --kur --gpu"
    exit 1
  fi
  echo
  gri "  Sıradaki adım:  ./yapilandir.sh   sonra   ./baslat.sh"
  exit 0
fi

# --- kurulum ---

if [ -z "$PAKET_YONETICISI" ]; then
  echo
  kirmizi "  Desteklenmeyen dağıtım: apt, dnf ya da pacman bulunamadı."
  gri "  Elle kurun:  https://docs.docker.com/engine/install/"
  exit 1
fi

if [ ${#EKSIK[@]} -eq 0 ] && [ "$GPU" -eq 0 ]; then
  echo
  yesil "  Kurulacak bir şey yok."
  exit 0
fi

baslik "Kurulum"
gri "  Dağıtım: $PAKET_YONETICISI"
sari "  Bu adım sistem paketleri kuruyor ve sudo isteyecek."
echo
read -r -p "  Devam edilsin mi? [e/H] " cevap
case "$cevap" in
  e|E|evet|Evet) ;;
  *) gri "  Vazgeçildi."; exit 1 ;;
esac

# Sira onemli: docker deposu eklenirken apt guncelleniyor, sonraki
# kurulumlar ondan faydalaniyor.
for e in "${EKSIK[@]}"; do
  case "$e" in
    docker|docker-compose-v2) kur_docker ;;
  esac
done
for e in "${EKSIK[@]}"; do
  case "$e" in
    docker-grup) kur_docker_grup ;;
    java|java-21|jdk)     kur_java ;;
    curl)                 kur_arac curl ;;
  esac
done

[ "$GPU" -eq 1 ] && kur_nvidia_toolkit

baslik "Sonuç"
gri "  Denetim yeniden çalıştırılıyor…"
echo
exec "${BASH_SOURCE[0]}"
