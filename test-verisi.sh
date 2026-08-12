#!/usr/bin/env bash
#
# Yayın Merkezi — test verisi yükleyici.
#
#   ./test-verisi.sh            20 kanal + 10 radyo ekle
#   ./test-verisi.sh --temizle  eklenenleri sil
#   ./test-verisi.sh --liste    ne ekleneceğini göster, hiçbir şey yapma
#
# Ayrı bir script: kurulumun parçası değil, isteyenin çalıştırdığı bir
# yardımcı. baslat.sh'e bayrak olarak eklenmişti ve orada durması normal
# kurulumu test verisiyle karıştırıyordu.
#
# NEDEN SQL DEĞİL, API
#   Doğrudan veritabanına satır yazmak ÇALIŞMIYOR. Denendi:
#   MediaMTX kayıtlarında 203 kez "max recorded size exceeded" çıktı ve
#   kanalların çoğu hiç akmadı.
#
#   Sebebi: kanal API'den eklenirken applySourceProbe çalışıyor ve master
#   playlist'teki varyantlardan segment boyutu sınırına UYANI seçip
#   resolved_source_url'e yazıyor. SQL bu adımı atlıyor; MediaMTX master'ı
#   alıp en yüksek bit hızını seçiyor ve gohlslib'in sınırını aşıyor.
#
#   API ayrıca MediaMTX'e yazmayı da kendisi yapıyor -- ayrı bir "restore"
#   adımına gerek kalmıyor.
#
# Adresler 12 Ağustos 2026'da denenerek doğrulandı; liste ve yeniden
# doğrulama yöntemi docs/test-yayinlari.md.

set -uo pipefail

KOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DOSYASI="$KOK/.env"

kirmizi() { printf '\033[31m%s\033[0m\n' "$*"; }
yesil()   { printf '\033[32m%s\033[0m\n' "$*"; }
sari()    { printf '\033[33m%s\033[0m\n' "$*"; }
mavi()    { printf '\033[34m%s\033[0m\n' "$*"; }
gri()     { printf '\033[90m%s\033[0m\n' "$*"; }
baslik()  { echo; mavi "── $* ─────────────────────────────────"; }

env_al() {
  local anahtar="$1" varsayilan="${2:-}" deger
  deger="$(grep -m1 "^${anahtar}=" "$ENV_DOSYASI" 2>/dev/null | cut -d= -f2-)"
  echo "${deger:-$varsayilan}"
}

# ------------------------------------------------------------------ veri
#
# Biçim:  ad|adres|path|dvr
# dvr yalnızca ilk beşte açık: yirmisinde birden açmak 7 günde ~4,5 TB eder
# (20 × 7 gün × 3 Mbps) ve test kurulumunda depoyu doldurur.
KANALLAR=(
  "TRT Haber|https://tv-trthaber.medya.trt.com.tr/master.m3u8|trt-haber|true"
  "TRT 1|https://tv-trt1.medya.trt.com.tr/master.m3u8|trt-1|true"
  "TRT Spor|https://tv-trtspor1.medya.trt.com.tr/master.m3u8|trt-spor|true"
  "TRT Belgesel|https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8|trt-belgesel|true"
  "TRT World|https://tv-trtworld.medya.trt.com.tr/master.m3u8|trt-world|true"
  "TRT Spor Yildiz|https://tv-trtspor2.medya.trt.com.tr/master.m3u8|trt-spor-yildiz|false"
  "TRT Cocuk|https://tv-trtcocuk.medya.trt.com.tr/master.m3u8|trt-cocuk|false"
  "TRT Muzik|https://tv-trtmuzik.medya.trt.com.tr/master.m3u8|trt-muzik|false"
  "TRT Avaz|https://tv-trtavaz.medya.trt.com.tr/master.m3u8|trt-avaz|false"
  "TRT Kurdi|https://tv-trtkurdi.medya.trt.com.tr/master.m3u8|trt-kurdi|false"
  "TRT Arabi|https://tv-trtarabi.medya.trt.com.tr/master.m3u8|trt-arabi|false"
  "TRT Turk|https://tv-trtturk.medya.trt.com.tr/master.m3u8|trt-turk|false"
  "Red Bull TV|https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8|redbull|false"
  "DW English|https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8|dw-en|false"
  "DW Arabia|https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/index.m3u8|dw-ar|false"
  "DW Espanol|https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8|dw-es|false"
  "Al Jazeera English|https://live-hls-web-aje.getaj.net/AJE/index.m3u8|aljazeera-en|false"
  "France 24 English|https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8|france24-en|false"
  "Arirang TV|https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8|arirang|false"
  "CGTN|https://live.cgtn.com/1000/prog_index.m3u8|cgtn|false"
)

# Biçim:  ad|adres|path|bitrate|sira
# Hepsi KOPRU: kaynaklar MP3 ve tarayıcı HLS içinde MP3 oynatamıyor.
# ffmpeg AAC'ye çeviriyor -- ölçülen maliyet köprü başına ~%2,6 CPU.
RADYOLAR=(
  "France Inter|https://icecast.radiofrance.fr/franceinter-midfi.mp3|radyo-france-inter|128k|1"
  "France Info|https://icecast.radiofrance.fr/franceinfo-midfi.mp3|radyo-france-info|128k|2"
  "FIP|https://icecast.radiofrance.fr/fip-midfi.mp3|radyo-fip|128k|3"
  "France Musique|https://icecast.radiofrance.fr/francemusique-midfi.mp3|radyo-france-musique|128k|4"
  "Radio Paradise Main|https://stream.radioparadise.com/mp3-192|radyo-rp-main|192k|5"
  "Radio Paradise Mellow|https://stream.radioparadise.com/mellow-192|radyo-rp-mellow|192k|6"
  "Radio Paradise Rock|https://stream.radioparadise.com/rock-192|radyo-rp-rock|192k|7"
  "SomaFM Groove Salad|https://ice1.somafm.com/groovesalad-128-mp3|radyo-soma-groove|128k|8"
  "SomaFM Drone Zone|https://ice1.somafm.com/dronezone-128-mp3|radyo-soma-drone|128k|9"
  "SomaFM Secret Agent|https://ice1.somafm.com/secretagent-128-mp3|radyo-soma-agent|128k|10"
)

# ------------------------------------------------------------------ yardımcı

TOKEN=""

# İstekleri KONTEYNER İÇİNDEN atıyoruz, host'tan değil.
#
# Sebebi issuer uyuşmazlığı: host'tan alınan token'ın "iss" alanı
# http://localhost:8080/... oluyor, backend ise http://keycloak:8080/...
# bekliyor ve token'ı 401 ile reddediyor. Ölçüldü -- host'tan 401, ağ
# içinden 200.
api() {
  local yontem="$1" yol="$2" govde="${3:-}"
  if [ -n "$govde" ]; then
    docker exec backend sh -c \
      "curl -s -m 60 -X $yontem -H 'Authorization: Bearer $TOKEN' \
       -H 'Content-Type: application/json' -d '$govde' http://localhost:8081$yol" 2>/dev/null
  else
    docker exec backend sh -c \
      "curl -s -m 60 -X $yontem -H 'Authorization: Bearer $TOKEN' http://localhost:8081$yol" 2>/dev/null
  fi
}

giris_yap() {
  local realm cid secret
  realm="$(env_al KEYCLOAK_REALM YayinYonetimi)"
  cid="$(env_al KEYCLOAK_CLIENT_ID '')"
  secret="$(env_al KEYCLOAK_CLIENT_SECRET '')"

  TOKEN="$(docker exec backend sh -c "curl -s -m 15 -X POST \
      'http://keycloak:8080/realms/$realm/protocol/openid-connect/token' \
      -d 'client_id=$cid' -d 'client_secret=$secret' -d 'grant_type=password' \
      -d 'username=${TEST_KULLANICI:-admin1}' -d 'password=${TEST_SIFRE:-12345678}'" 2>/dev/null \
    | sed 's/.*"access_token":"//;s/".*//')"

  if [ -z "$TOKEN" ] || [ "${#TOKEN}" -lt 40 ]; then
    kirmizi "  Giriş yapılamadı (${TEST_KULLANICI:-admin1})."
    gri    "  Başka bir kullanıcıysa:  TEST_KULLANICI=... TEST_SIFRE=... $0"
    return 1
  fi
}

on_kosul() {
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx backend; then
    kirmizi "  backend çalışmıyor."
    gri    "  Önce:  ./baslat.sh"
    return 1
  fi
  [ -f "$ENV_DOSYASI" ] || { kirmizi "  .env yok — önce ./yapilandir.sh"; return 1; }
}

# ------------------------------------------------------------------ işlemler

liste() {
  baslik "Eklenecekler"
  gri "  ${#KANALLAR[@]} kanal:"
  for k in "${KANALLAR[@]}"; do
    IFS='|' read -r ad _ path dvr <<<"$k"
    printf "    %-22s %-18s %s\n" "$ad" "$path" "$([ "$dvr" = true ] && echo 'DVR açık' || echo '')"
  done
  echo
  gri "  ${#RADYOLAR[@]} radyo:"
  for r in "${RADYOLAR[@]}"; do
    IFS='|' read -r ad _ path _ _ <<<"$r"
    printf "    %-22s %s\n" "$ad" "$path"
  done
}

yukle() {
  on_kosul || return 1
  giris_yap || return 1

  baslik "Kanallar"
  local eklendi=0 atlandi=0 hata=0
  for k in "${KANALLAR[@]}"; do
    IFS='|' read -r ad url path dvr <<<"$k"
    local yanit
    yanit="$(api POST /api/channels \
      "{\"name\":\"$ad\",\"sourceUrl\":\"$url\",\"mediamtxPath\":\"$path\",\"active\":true,\"dvrEnabled\":$dvr,\"renditions\":\"\"}")"

    if echo "$yanit" | grep -q '"id"'; then
      yesil "  + $ad"; eklendi=$((eklendi+1))
    elif echo "$yanit" | grep -qiE "zaten|already|kullanımda"; then
      gri   "  · $ad (zaten var)"; atlandi=$((atlandi+1))
    else
      kirmizi "  ✗ $ad"
      gri     "      $(echo "$yanit" | head -c 160)"
      hata=$((hata+1))
    fi
  done

  baslik "Radyolar"
  local r_eklendi=0 r_atlandi=0 r_hata=0
  for r in "${RADYOLAR[@]}"; do
    IFS='|' read -r ad url path bitrate sira <<<"$r"
    local yanit
    yanit="$(api POST /api/radios \
      "{\"name\":\"$ad\",\"sourceUrl\":\"$url\",\"sourceKind\":\"KOPRU\",\"mediamtxPath\":\"$path\",\"bitrate\":\"$bitrate\",\"active\":true,\"logoUrl\":null,\"sortOrder\":$sira}")"

    if echo "$yanit" | grep -q '"id"'; then
      yesil "  + $ad"; r_eklendi=$((r_eklendi+1))
    elif echo "$yanit" | grep -qiE "zaten|already|kullanımda"; then
      gri   "  · $ad (zaten var)"; r_atlandi=$((r_atlandi+1))
    else
      kirmizi "  ✗ $ad"
      gri     "      $(echo "$yanit" | head -c 160)"
      r_hata=$((r_hata+1))
    fi
  done

  baslik "Özet"
  echo "  kanal : $eklendi eklendi, $atlandi atlandı, $hata hata"
  echo "  radyo : $r_eklendi eklendi, $r_atlandi atlandı, $r_hata hata"
  echo
  # Kaynaklar herkese acik ve zaman zaman dusuyor; hepsinin akmasi
  # garanti degil. Bu bir kurulum hatasi DEGIL.
  gri "  Kanalların yayına girmesi birkaç saniye sürer. Akmayanlar olabilir —"
  gri "  kaynaklar herkese açık ve zaman zaman düşüyor (docs/test-yayinlari.md)."
  gri "  Yayın durumu:  curl -s localhost:9997/v3/paths/list | python3 -m json.tool"
}

temizle() {
  on_kosul || return 1
  giris_yap || return 1

  baslik "Temizlik"
  sari "  Bu script'in eklediği kanal ve radyolar silinecek."
  gri  "  Başka yollarla eklenenlere DOKUNULMAZ — yalnızca yukarıdaki path'ler."
  echo
  read -r -p "  Devam edilsin mi? [e/H] " cevap
  case "$cevap" in e|E|evet|Evet) ;; *) gri "  Vazgeçildi."; return 1 ;; esac

  local silindi=0
  for k in "${KANALLAR[@]}"; do
    IFS='|' read -r ad _ path _ <<<"$k"
    local id
    id="$(api GET /api/channels | python3 -c "
import sys,json
try:
    for c in json.load(sys.stdin):
        if c.get('mediamtxPath')=='$path': print(c['id']); break
except Exception: pass" 2>/dev/null)"
    if [ -n "$id" ]; then
      # Kanal silme SIFRE istiyor (POST /silme). Klip, ekran goruntusu ve
      # DVR nesneleri de siliniyor -- test verisi, saklanacak bir sey yok.
      api POST "/api/channels/$id/silme" \
        "{\"password\":\"${TEST_SIFRE:-12345678}\",\"deleteClips\":true,\"deleteScreenshots\":true,\"deleteDvr\":true}" >/dev/null
      gri "  - $ad"; silindi=$((silindi+1))
    fi
  done

  local r_silindi=0
  for r in "${RADYOLAR[@]}"; do
    IFS='|' read -r ad _ path _ _ <<<"$r"
    local id
    id="$(api GET /api/radios | python3 -c "
import sys,json
try:
    for c in json.load(sys.stdin):
        if c.get('mediamtxPath')=='$path': print(c['id']); break
except Exception: pass" 2>/dev/null)"
    if [ -n "$id" ]; then
      api DELETE "/api/radios/$id" >/dev/null
      gri "  - $ad"; r_silindi=$((r_silindi+1))
    fi
  done

  echo
  yesil "  $silindi kanal, $r_silindi radyo silindi."
}

# ------------------------------------------------------------------ akış

case "${1:-}" in
  --liste)   liste ;;
  --temizle) temizle ;;
  "")        yukle ;;
  *)
    echo "Kullanım: $0 [--liste | --temizle]"
    exit 1
    ;;
esac
