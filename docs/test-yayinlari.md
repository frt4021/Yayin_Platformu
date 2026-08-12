# Test Yayınları — 20 kanal, 10 radyo

Geliştirme ve yük testi için kullanılabilecek **herkese açık** canlı yayınlar.

> **Hepsi denenerek doğrulandı** — 12 Ağustos 2026.
> Yöntem: TV için playlist çekilip `#EXTM3U` başlığı arandı; radyo için
> akıştan 64 KB okundu. Listeye yalnızca cevap verenler alındı; denenip
> çalışmayanlar en altta.

---

## Kanallar (HLS)

Kaynak türü: **HLS**. Kanal eklerken `sourceUrl` alanına yapıştırılıyor.

### TRT (13)

| # | Ad | Adres |
|---|---|---|
| 1 | TRT Haber | `https://tv-trthaber.medya.trt.com.tr/master.m3u8` |
| 2 | TRT 1 | `https://tv-trt1.medya.trt.com.tr/master.m3u8` |
| 3 | TRT Spor | `https://tv-trtspor1.medya.trt.com.tr/master.m3u8` |
| 4 | TRT Spor Yıldız | `https://tv-trtspor2.medya.trt.com.tr/master.m3u8` |
| 5 | TRT Belgesel | `https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8` |
| 6 | TRT Çocuk | `https://tv-trtcocuk.medya.trt.com.tr/master.m3u8` |
| 7 | TRT Müzik | `https://tv-trtmuzik.medya.trt.com.tr/master.m3u8` |
| 8 | TRT Avaz | `https://tv-trtavaz.medya.trt.com.tr/master.m3u8` |
| 9 | TRT Kurdî | `https://tv-trtkurdi.medya.trt.com.tr/master.m3u8` |
| 10 | TRT Arabi | `https://tv-trtarabi.medya.trt.com.tr/master.m3u8` |
| 11 | TRT World | `https://tv-trtworld.medya.trt.com.tr/master.m3u8` |
| 12 | TRT Türk | `https://tv-trtturk.medya.trt.com.tr/master.m3u8` |
| 13 | Red Bull TV | `https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8` |

### Uluslararası (7)

| # | Ad | Adres |
|---|---|---|
| 14 | DW English | `https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8` |
| 15 | DW Arabia | `https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/index.m3u8` |
| 16 | DW Español | `https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8` |
| 17 | Al Jazeera English | `https://live-hls-web-aje.getaj.net/AJE/index.m3u8` |
| 18 | France 24 English | `https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8` |
| 19 | NHK World Japan | `https://nhkwlive-ojp.akamaized.net/hls/live/2003458/nhkwlive-ojp-en/index.m3u8` |
| 20 | NASA TV | `https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8` |

### Yedek (4)

Yukarıdakilerden biri düşerse:

| Ad | Adres |
|---|---|
| Arirang TV | `https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8` |
| CGTN | `https://live.cgtn.com/1000/prog_index.m3u8` |
| CNA | `https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43aeb4a48ec97f66ebbe/index.m3u8` |
| NASA TV Media | `https://ntv2.akamaized.net/hls/live/2013923/NASA-NTV2-HLS/master.m3u8` |

---

## Radyolar (10)

Kaynak türü: **KÖPRÜ** (ffmpeg MP3 → AAC çeviriyor) ya da doğrudan.

| # | Ad | Adres | Biçim |
|---|---|---|---|
| 1 | SomaFM Groove Salad | `https://ice1.somafm.com/groovesalad-128-mp3` | MP3 128k |
| 2 | SomaFM Drone Zone | `https://ice1.somafm.com/dronezone-128-mp3` | MP3 128k |
| 3 | SomaFM Deep Space One | `https://ice1.somafm.com/deepspaceone-128-mp3` | MP3 128k |
| 4 | SomaFM Secret Agent | `https://ice1.somafm.com/secretagent-128-mp3` | MP3 128k |
| 5 | Radio Paradise Main | `https://stream.radioparadise.com/mp3-192` | MP3 192k |
| 6 | Radio Paradise Mellow | `https://stream.radioparadise.com/mellow-192` | MP3 192k |
| 7 | Radio Paradise Rock | `https://stream.radioparadise.com/rock-192` | MP3 192k |
| 8 | FIP (Radio France) | `https://icecast.radiofrance.fr/fip-midfi.mp3` | MP3 |
| 9 | France Inter | `https://icecast.radiofrance.fr/franceinter-midfi.mp3` | MP3 |
| 10 | France Info | `https://icecast.radiofrance.fr/franceinfo-midfi.mp3` | MP3 |

Yedek: `France Musique` — `https://icecast.radiofrance.fr/francemusique-midfi.mp3`,
`Venice Classic Radio` — `https://uk2.streamingpulse.com/ssl/vcr1`

**Konuşmalı radyolar altyazı testi için daha uygun:** France Inter ve France
Info sürekli konuşma taşıyor; SomaFM ve Radio Paradise ağırlıklı müzik ve VAD
neredeyse hiç konuşma bölütü üretmiyor.

---

## Dikkat: segment boyutu sınırı

Bu listedeki adreslerin çoğu **master playlist**. MediaMTX master verildiğinde
**en yüksek bant genişliğini** seçiyor ve o varyantın segmentleri
`gohlslib`'in sınırını aşarsa **yayın hiç başlamıyor** — kullanıcı hata
görmüyor, kanal sessizce ölü kalıyor. Tek belirti log'daki
`max recorded size exceeded` satırı.

Uygulama bunu `CHANNELS_HLS_MAX_SEGMENT_BYTES` (varsayılan 3,5 MB) ile
kendisi süzüyor. Yine de bir kanal "aktif ama akmıyor" görünüyorsa ilk
bakılacak yer burası.

Ölçülen: TRT 720p (3,01 MB/segment) çalışıyor, TRT 1080p (4,29 MB) düşüyor.

---

## Toplu ekleme

Arayüzden tek tek eklemek yerine API ile:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/realms/YayinYonetimi/protocol/openid-connect/token \
  -d "client_id=$KEYCLOAK_CLIENT_ID" -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d "grant_type=password" -d "username=admin1" -d "password=12345678" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -s -X POST http://localhost:8090/api/channels \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "name": "TRT Haber",
        "sourceUrl": "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
        "sourceType": "HLS",
        "active": true,
        "dvrEnabled": true,
        "renditions": ""
      }'
```

> **`CHANNELS_MAX_ACTIVE` sınırına dikkat.** `.env`'de 24; 20 kanal + radyolar
> eklerken aşılırsa istek açık bir hatayla reddediliyor.

---

## Denenip çalışmayanlar

Aynı hataya tekrar düşülmesin diye:

| Ad | Sonuç |
|---|---|
| TRT 3 / TBMM (`tv-trt3…`) | bağlanamadı |
| TRT EBA İlkokul (`tv-e-ilkokul…`) | bağlanamadı |
| Sky News (`skynews2-plutolive-vo…`) | HTTP 400 |
| France 24 (`f24hls-i.akamaihd.net`) | HTTP 400 — üstteki `static.france24.com` adresi çalışıyor |
| Al Jazeera (`…/AJE/01.m3u8`) | bağlanamadı — `…/AJE/index.m3u8` çalışıyor |
| Bloomberg (Rakuten/Wurl) | bağlanamadı |
| Euronews (Zattoo) | bağlanamadı |
| ABC News Live (uplynk) | HTTP 404 |

---

## Bu listeyi yeniden doğrulamak

Adresler zamanla değişiyor. Yeniden denemek için:

```bash
# TV
curl -sL -m 12 -o /tmp/pl.m3u8 -w '%{http_code}\n' "<adres>" && head -1 /tmp/pl.m3u8

# Radyo — 64 KB okunabiliyorsa akıyor demektir
curl -sL -m 8 -r 0-65535 -o /dev/null -w '%{size_download}\n' "<adres>"
```
