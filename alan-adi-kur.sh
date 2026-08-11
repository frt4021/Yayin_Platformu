#!/usr/bin/env bash
#
# Yayın Merkezi — alan adıyla erişim kurulumu.
#
#   ./alan-adi-kur.sh            .env'deki PUBLIC_HOST için hosts satırı üretir
#   ./alan-adi-kur.sh --yaz      satırı /etc/hosts'a ekler (sudo i

# Neden ayrı script: /etc/hosts'a yazmak sudo istiyor ve bunu yapılandırma
# adımına gömmek, ./yapilandir.sh'ı gereksiz yere ayrıcalık isteyen bir
# script hâline getirirdi.
#
# Erişecek HER makinede çalıştırılmalı — hosts dosyası yereldir. Kalıcı
# çözüm ağdaki DNS'e bir A kaydı eklemek.

set -euo pipefail

KOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DOSYASI="$KOK/.env"

kirmizi() { printf '\033[31m%s\033[0m\n' "$*"; }
yesil()   { printf '\033[32m%s\033[0m\n' "$*"; }
gri()     { printf '\033[90m%s\033[0m\n' "$*"; }

[ -f "$ENV_DOSYASI" ] || { kirmizi ".env yok. Önce ./yapilandir.sh çalıştırın."; exit 1; }

ALAN="$(grep -m1 '^PUBLIC_HOST=' "$ENV_DOSYASI" | cut -d= -f2- || true)"
[ -n "$ALAN" ] || {
  kirmizi "PUBLIC_HOST boş."
  gri "  Alan adıyla erişim için:  PUBLIC_HOST=yayın.com ./yapilandir.sh --zorla"
  exit 1
}

# Punycode ŞART: tarayıcı IDN alan adını ağa çıkarken çevirir ve hosts
# araması o hâlde yapılır. Unicode yazılan satır hiçbir zaman eşleşmez.
PUNY="$(python3 -c "import sys;print(sys.argv[1].encode('idna').decode())" "$ALAN")"

IP="$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[\d.]+' || true)"
[ -n "$IP" ] || IP="$(hostname -I 2>/dev/null | awk '{print $1}')"

SATIR="$IP	$PUNY"

echo
gri "  alan adı : $ALAN"
gri "  punycode : $PUNY"
gri "  sunucu   : $IP"
echo
echo "  Gereken hosts satırı:"
echo
echo "    $SATIR"
echo

if [ "${1:-}" = "--yaz" ]; then
  if grep -q "[[:space:]]$PUNY\$" /etc/hosts 2>/dev/null; then
    yesil "  /etc/hosts zaten içeriyor — dokunulmadı."
  else
    printf '%s\n' "$SATIR" | sudo tee -a /etc/hosts >/dev/null
    yesil "  /etc/hosts güncellendi."
  fi
  echo
  gri  "  Doğrulama:  curl -I http://$PUNY/"
else
  gri "  Eklemek için:  $0 --yaz"
  gri "  Başka makinelerde: yukarıdaki satırı o makinenin hosts dosyasına ekleyin."
  gri "    Linux/macOS : /etc/hosts"
  gri "    Windows     : C:\\Windows\\System32\\drivers\\etc\\hosts"
fi
echo
