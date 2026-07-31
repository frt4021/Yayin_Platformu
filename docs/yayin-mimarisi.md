# Canlı Yayın Akış Mimarisi

Bu belge yayının kaynaktan izleyicinin ekranına kadar izlediği yolu, her
adımda eklenen gecikmeyi ve gecikmeyi değiştiren ayarları anlatır.

Ölçümler 31 Temmuz 2026'da, 8 çekirdekli / 16 GB geliştirme makinesinde,
16 eşzamanlı yayın altında alındı. **Ölçülen** ve **hesaplanan** değerler
ayrı ayrı işaretlendi.

---

## 1. Genel bakış

```
   Kaynak yayın                MediaMTX                    İzleyici
  (HLS/RTSP/SRT/UDP)      (:8554 / :8888 / :9997)        (tarayıcı)
        │                        │                            │
        │  ①  çekme (pull)       │                            │
        ├───────────────────────►│                            │
        │                        │  ②  paketleme (LL-HLS)     │
        │                        ├──── segment + part ───────►│
        │                        │                       ③ tampon
        │                        │                       ④ çözme
        │                        │                            ▼
        │                        │                          ekran
        │                        │
        │                   ⑤ REST API :9997
        │                        ▲
        │                        │
                            Backend (:8081)
                         kanal ekle / sil / durum
```

Kritik nokta: **video backend'den geçmez.** Backend yalnızca MediaMTX'e
"şu kaynağı şu path'te yayınla" der. Video trafiği tarayıcı ile MediaMTX
arasında doğrudan akar; bu yüzden backend izleyici sayısıyla ölçeklenmez.

---

## 2. Bileşenler ve portlar

| Bileşen | Port | Görev | Dışarı açık mı |
|---|---|---|---|
| MediaMTX RTSP | 8554 | Yayın alma (push) ve verme | evet |
| MediaMTX HLS | 8888 | İzleyiciye dağıtım + gömülü oynatıcı | evet |
| MediaMTX API | 9997 | Path yönetimi, durum sorgulama | evet (IP kısıtlı) |
| MediaMTX WebRTC | 8889 | Düşük gecikmeli alternatif | **hayır** — compose'da yayınlanmamış |
| MediaMTX metrics | 9998 | Prometheus | hayır — `metrics: false` |
| MediaMTX pprof | 9999 | Profilleme | hayır — `pprof: false` |
| MediaMTX playback | 9996 | Kayıttan oynatma | hayır — `playback: false` |
| Backend (Quarkus) | 8081 | Kanal CRUD, kimlik, yetki | evet |
| Frontend (Vite) | 3000 | Arayüz | evet |

API erişimi `mediamtx.yml`'da `127.0.0.1`, `::1` ve `172.16.0.0/12` ile
sınırlı. İzleme (`read`/`playback`) ise **herkese açık** — bkz. §8.

---

## 3. Adım adım akış

### ① Kaynağın çekilmesi

Backend `POST /v3/config/paths/add/<path>` ile MediaMTX'e şunu yazar:

```json
{ "source": "https://...", "sourceOnDemand": false }
```

`sourceOnDemand: false` seçildi: MediaMTX kaynağa **hemen** bağlanır ve
izleyici olmasa da yayını çeker. Bu, "sistem yeniden başladığında açık
kanallar kendiliğinden ayağa kalksın" gereksiniminin karşılığıdır.
`true` olsaydı path tanımlı olur ama ilk izleyici gelene kadar yayın
akmazdı.

Bedeli: kanal başına sürekli bant genişliği ve bağlantı tüketimi.

### ② Paketleme (LL-HLS)

MediaMTX gelen akışı yeniden kodlamaz (`stream copy`), yalnızca fMP4
segmentlerine böler. Aktif ayarlar:

| Ayar | Değer | Anlamı |
|---|---|---|
| `hlsVariant` | `lowLatency` | LL-HLS: segmentler tamamlanmadan "part" olarak yayınlanır |
| `hlsSegmentDuration` | `1s` | Hedef segment süresi (gerçekleşen GOP'a yuvarlanır) |
| `hlsPartDuration` | `200ms` | Part hedef süresi |
| `hlsSegmentCount` | `7` | Playlistte tutulan segment sayısı |
| `hlsAlwaysRemux` | `false` | HLS üretimi ilk izleyici gelince başlar |

Gerçek playlistten (`kanal01`, 720p25, 2 sn GOP):

```
#EXT-X-TARGETDURATION:2
#EXT-X-PART-INF:PART-TARGET=0.24000
#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=0.60000
#EXTINF:1.96000
```

Segment süresi 1 sn istenmesine rağmen **1.96 sn** oldu: segment sınırları
anahtar kareye (GOP) hizalanmak zorunda, kaynağın GOP'u 2 saniye. Segment
süresini gerçekten kısaltmak için **kaynağın GOP'u** kısaltılmalı — MediaMTX
ayarını değiştirmek tek başına işe yaramaz.

### ③ İzleyici tamponu

`hls.js` ayarları (`HlsPlayer.tsx`):

| Ayar | Değer | Gerekçe |
|---|---|---|
| `lowLatencyMode` | `true` | LL-HLS partlarını kullanır |
| `capLevelToPlayerSize` | `true` | Karo boyutuna uyan rendition; 4x4 gridde 16 kez 1080p çözmeyi önler |
| `maxBufferLength` | `10 sn` | 16 yayın × uzun tampon belleği şişirir |
| `liveSyncDurationCount` | `3` | Canlı kenardan 3 segment geride durur |

### ④ Çözme ve gösterim

Karo başına bir H.264 çözücü. 16 karo = 16 eşzamanlı çözücü — sistemin
**asıl darboğazı burasıdır**, sunucu değil (§7).

---

## 4. Gecikme bütçesi

### Ölçülen

| Adım | Değer | Yöntem |
|---|---|---|
| Paketleme (playlist kenarı) | **~3.9 sn** | `EXT-X-PROGRAM-DATE-TIME` ile duvar saati farkı, 6 örneklem: 3.92 / 3.94 / 3.96 / 3.98 / 3.99 sn |
| Part indirme (yerel ağ) | **< 1 ms** | 66 KB'lık part, `curl` ile 0.0005 sn |

Paketleme gecikmesinin ~3.9 sn çıkması beklenen davranıştır: playlistin
canlı kenarı, tamamlanmış son segmentin başlangıcını gösterir ve segment
süresi 1.96 sn'dir — yani iki segmentlik bir birikim (2 × 1.96 ≈ 3.9)
doğal olarak oluşur.

### Hesaplanan (uçtan uca)

Yerel ağda, LL-HLS partları kullanıldığında:

| Adım | Katkı |
|---|---|
| ① Kaynak → MediaMTX | kaynağa bağlı (yerel RTSP'te ~0.1 sn, internetten HLS'te 2–10 sn) |
| ② Paketleme | ~3.9 sn (ölçülen) |
| ③ Ağ | < 0.01 sn (LAN) / 0.05–0.2 sn (WAN) |
| ④ Oynatıcı tamponu + çözme | 0.6–2 sn (`PART-HOLD-BACK` 0.6 sn taban) |
| **Toplam (yerel kaynak)** | **~4.5 – 6 sn** |
| **Toplam (internetten HLS kaynak)** | **~7 – 15 sn** |

> Bu değerler hesaplanmıştır; gerçek cam-cama gecikme ölçülmedi. Ölçmek
> için kaynağa duvar saati damgası basılıp (`ffmpeg -vf drawtext`) ekrandaki
> değer duvar saatiyle karşılaştırılmalıdır.

---

## 5. Gecikmeyi düşürmek

Etkisi en büyükten küçüğe:

1. **Kaynağın GOP'unu kısaltın.** Segment süresi GOP'a hizalanıyor; 2 sn
   GOP → en iyi ihtimalle ~2 sn segment. 1 sn GOP paketleme gecikmesini
   kabaca yarıya indirir. Bedeli: bit hızı artışı (~%5–10).

2. **WebRTC'ye geçin.** MediaMTX'te açık ama `:8889` compose'da
   yayınlanmamış. Sub-saniye gecikme verir; bedeli, HLS'in CDN
   dostluğundan ve geniş uyumluluğundan vazgeçmek.

3. **`hlsPartDuration`'ı düşürün** (200ms → 100ms). Küçük kazanç, daha
   fazla HTTP isteği.

4. **`hlsAlwaysRemux: yes`.** Gecikmeyi değil **açılış süresini** düşürür:
   şu an ilk izleyici gelene kadar HLS üretilmiyor, o kişi birkaç saniye
   bekliyor. Sürekli yayında olması beklenen bir yayın merkezinde açılmalı.
   Bedeli: izleyici yokken de segment üretmenin CPU maliyeti.

---

## 6. Dağıtım (bir kaynak → çok izleyici)

MediaMTX kaynağa **tek** bağlantı açıp N izleyiciye dağıtır. Ölçülen:

| | Değer |
|---|---|
| Kaynaktan alınan | +10.8 MB |
| İzleyicilere gönderilen | +126 MB |
| Çoğaltma oranı | ~12× (16 okuyucu oturumu) |

Yani upstream sabit, downstream izleyici sayısıyla çarpılır. **Sınır bant
genişliğidir, kanal sayısı değil:**

| İzleyici (6.7 Mbps yayında) | Toplam downstream |
|---|---|
| 10 | 67 Mbps |
| 100 | 670 Mbps |
| ~150 | ~1 Gbps — hat dolar |

Daha fazlası için MediaMTX önüne CDN veya nginx önbelleği gerekir;
`hlsCDNSecret` ayarı bunun içindir.

---

## 7. Ölçülen kaynak kullanımı (16 eşzamanlı yayın)

Düzenek: 16 × ffmpeg stream-copy → RTSP push → `src01..16`, ardından
`kanal01..16` bunları pull ediyor (toplam 32 path).

| | Değer |
|---|---|
| MediaMTX CPU | **%13.3** (8 çekirdekte ~0.13 çekirdek) |
| MediaMTX RAM | **352 MB** |
| ffmpeg yayıncıları | %0 CPU (stream copy) |
| Yük ortalaması | 4.81 / 8 çekirdek |
| HLS manifesti alınabilen | 16 / 16 |

Bu ölçüm gerçekte olacağından **ağırdır**: MediaMTX burada hem 16 yayını
alıyor hem kendi içinden 16 kez tekrar çekiyor. Gerçek kurulumda tek yön
olduğu için yük kabaca yarısıdır.

**Darboğaz sunucu değil tarayıcıdır.** 16 karo = 16 eşzamanlı H.264
çözücü. `capLevelToPlayerSize` her karonun kendi boyutuna uygun rendition
çekmesini sağlar, ama kaynak tek renditionlı ise bu ayarın yapacağı bir şey
yoktur — 16 kez tam çözünürlük çözülür. Çok-renditionlı kaynaklarda grid
belirgin biçimde hafifler.

---

## 8. Gözlem araçları

MediaMTX'in **yönetim arayüzü yoktur.** Elde olanlar:

### Gömülü oynatıcı (her path için)

```
http://localhost:8888/<path>/
```

MediaMTX bu adreste hazır bir HTML oynatıcı sayfası döndürür. Kanalı
uygulamadan bağımsız denemek için en hızlı yol.

### Manifest dosyaları

```
http://localhost:8888/<path>/index.m3u8          ← ana playlist (varyantlar)
http://localhost:8888/<path>/<varyant>.m3u8      ← medya playlisti (segment/part listesi)
```

Medya playlisti gecikme teşhisi için asıl kaynaktır: `EXT-X-TARGETDURATION`,
`PART-TARGET`, `PART-HOLD-BACK` ve `PROGRAM-DATE-TIME` buradadır.

### REST API

```bash
# Tüm path'lerin anlık durumu
curl -s localhost:9997/v3/paths/list?itemsPerPage=100 | python3 -m json.tool

# Tek path: hazır mı, kaç izleyici, kaç bayt
curl -s localhost:9997/v3/paths/get/kanal01 | python3 -m json.tool

# Yapılandırma (kanalın tanımı)
curl -s localhost:9997/v3/config/paths/get/kanal01 | python3 -m json.tool

# Genel ayarlar
curl -s localhost:9997/v3/config/global/get | python3 -m json.tool
```

`/v3/config/paths/...` **tanımı**, `/v3/paths/...` **çalışma zamanı
durumunu** verir. Bir path'in yapılandırmada var olması yayının aktığı
anlamına gelmez; `ready` alanına bakılmalıdır.

### Diske ne yazılıyor

Varsayılan kurulumda MediaMTX diske **hiçbir şey yazmaz**: HLS segmentleri
bellekte tutulur, kayıt kapalıdır. Üretilen dosyaları görebilmek için iki
ayar açıldı:

```yaml
hlsDirectory: /hls          # HLS segmentleri diske yazılır
pathDefaults:
  record: no                # kayıt varsayılan kapalı, path bazında açılır
  recordPath: /recordings/%path/%Y-%m-%d_%H-%M-%S-%f
  recordFormat: fmp4
  recordSegmentDuration: 1m
  recordDeleteAfter: 24h
```

Host tarafındaki karşılıkları (compose bind mount):

```
src/main/docker/mediamtx-data/hls/          ← HLS segmentleri
src/main/docker/mediamtx-data/recordings/   ← DVR kayıtları
```

#### HLS dizini

```
hls/kanal01/
  a22edbcd40bb_video1_seg7.mp4    510 KB
  a22edbcd40bb_video1_seg8.mp4    472 KB
  a22edbcd40bb_audio2_seg7.mp4     16 KB
  ...
```

Üç şeye dikkat:

1. **`.m3u8` dosyası diskte yoktur.** Playlist her istekte bellekte
   üretilir — izleyiciye özel `?session=` parametresi ve LL-HLS'in
   `EXT-X-PART` satırları oturuma göre değiştiği için dosyaya yazılamaz.
   Diskte yalnızca medya segmentleri bulunur.

2. **`_init.mp4` de diskte yoktur.** fMP4 başlatma segmenti bellekten
   servis edilir.

3. **Bu yüzden tek bir segment tek başına oynatılamaz.** Doğrudan
   `ffprobe` ile açmayı denerseniz:

   ```
   trun track id unknown, no tfhd was found
   Invalid data found when processing input
   ```

   Segment, init segmentindeki kodek/track tanımlarına muhtaçtır. Oynatmak
   için init + segment birlikte gerekir; pratikte playlist üzerinden
   erişilmelidir.

4. Ses ve video **ayrı dosyalarda** (`video1_*`, `audio2_*`). Oynatıcı
   ikisini playlist üzerinden eşler.

5. Dizin **yalnızca ilk izleyici geldiğinde** oluşur — `hlsAlwaysRemux:
   false` olduğu için HLS üretimi talep üzerine başlar. 16 kanaldan yalnızca
   istenmiş olanların klasörü vardır.

6. Dosyalar **root** kullanıcısına aittir; MediaMTX konteyneri root olarak
   çalışıyor. Host'tan silmek için `sudo` gerekir.

Segment sayısı `hlsSegmentCount: 7` ile sınırlı — dizin sürekli döner,
büyümez.

#### Kayıt dizini

Kayıt path bazında açılır:

```bash
curl -X PATCH localhost:9997/v3/config/paths/patch/kanal01 \
     -H 'Content-Type: application/json' -d '{"record": true}'
```

Üretilen dosyalar:

```
recordings/kanal01/
  2026-07-31_07-56-50-978283.mp4   15.7 MB
  2026-07-31_07-57-52-108521.mp4   15.7 MB
```

HLS segmentlerinin aksine kayıt dosyaları **kendi kendine yeterlidir** —
init bilgisi dosyanın içindedir, doğrudan oynatılabilir. `ffprobe` çıktısı:

```
codec_name=h264   width=1280  height=720
codec_name=aac
duration=62.358722   size=15729547
```

`recordPath` içindeki yer tutucular: `%path` kanal adı, `%Y-%m-%d_%H-%M-%S-%f`
segmentin başlangıç zamanı. `recordDeleteAfter` süresi dolan dosyaları
MediaMTX kendisi siler.

Kaydı kapatmak:

```bash
curl -X PATCH localhost:9997/v3/config/paths/patch/kanal01 \
     -H 'Content-Type: application/json' -d '{"record": false}'
```

> **Disk uyarısı — ölçülen değerlerle:** 2 Mbps'lik bir kanal dakikada
> **15.7 MB** yazıyor. Kanal başına saatte ~0.94 GB, 16 kanalda saatte
> **~15 GB**, `recordDeleteAfter: 24h` ile kalıcı ~360 GB. Tüm kanallarda
> kayıt açmadan önce bu hesap yapılmalı; `pathDefaults.record` bu yüzden
> kapalı bırakıldı.

### Kapalı olan, açılabilecek uçlar

`mediamtx.yml`'a eklenirse:

```yaml
metrics: yes            # :9998  Prometheus — kanal başına bayt, izleyici, hata sayacı
pprof: yes              # :9999  CPU/bellek profili
playback: yes           # :9996  kayıttan oynatma (kayıt açıldığında)
```

Compose'da ilgili portların da yayınlanması gerekir. Grafana ile izleme
planlıyorsanız başlangıç noktası `metrics`tir.

---

## 9. Yeniden başlatma davranışı

MediaMTX path yapılandırmasını **yalnızca bellekte** tutar; konteyner
yeniden başlayınca tüm kanallar kaybolur. Kalıcı tanım `channels`
tablosundadır.

```
Uygulama açılışı → StartupEvent → ChannelRestorer
                                     └─ aktif kanalları oku → MediaMTX'e yeniden yaz
```

Hata durumunda uygulama yine de açılır: MediaMTX henüz hazır değilse
backend'in tamamen çökmesi yerine kanalların yayında olmaması tercih
edilmiştir. Yönetici durumu kanal listesindeki `streaming` alanından görür
ve `POST /api/channels/restore` ile yeniden dener.

MediaMTX **backend'den bağımsız** yeniden başlatılırsa bu otomatik
çalışmaz; `restore` ucu elle tetiklenmelidir.

---

## 10. Bilinen sınırlar

| Konu | Durum |
|---|---|
| **İzleme kimlik doğrulaması yok** | `:8888`'e erişebilen herkes, uygulamaya hiç giriş yapmadan tüm kanalları izleyebilir. `mediamtx.yml`'da `read`/`playback` izni `ips: []` ile herkese açık. |
| **İzleyici sayısı geç düşer** | `hlsMuxerCloseAfter: 1m` — arayüzdeki sayı "şu an izleyen" değil, "son bir dakikada izleyen". |
| **Kapasite sınırı yalnızca uygulama katmanında** | `channels.max-active=16` servis katmanında uygulanır; veritabanına doğrudan yazılan kayıtlar bu denetimi atlar. |
| **WebRTC erişilemez** | MediaMTX'te açık ama `:8889` compose'da yayınlanmamış. |
| **Cam-cama gecikme ölçülmedi** | §4'teki toplam değerler hesaplanmıştır. |
