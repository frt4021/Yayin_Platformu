# Yayın Merkezi — Modül İç İşleyişi

Bu belge [teknik-referans.md](teknik-referans.md)'nin derinleştirilmiş hâli.
Orada **ne** ve **neden** var; burada **nasıl** var — sınıf sınıf, adım adım.

Amaç: "bu paket ne yapıyor, tam olarak nasıl çalışıyor" sorusuna kod açmadan
cevap verebilmek.

---

## İçindekiler

1. [`channel` — kanal yaşam döngüsü](#1-channel--kanal-yaşam-döngüsü)
2. [`media` — kodlayıcı soyutlaması](#2-media--kodlayıcı-soyutlaması)
3. [`dvr` — geçmişe erişim](#3-dvr--geçmişe-erişim)
4. [`clip` — klip, kayıt, planlı kayıt](#4-clip--klip-kayıt-planlı-kayıt)
5. [`video` — kütüphane](#5-video--kütüphane)
6. [`radio` — ses yayınları](#6-radio--ses-yayınları)
7. [`VAD` — ses etkinliği tespiti](#7-vad--ses-etkinliği-tespiti)
8. [`stt-worker` — konuşma tanıma ve çeviri](#8-stt-worker--konuşma-tanıma-ve-çeviri)
9. [`subtitle` — altyazı deposu ve canlı akış](#9-subtitle--altyazı-deposu-ve-canlı-akış)
10. [`user` + `auth` — kimlik](#10-user--auth--kimlik)
11. [`storage` — kota ve temizlik](#11-storage--kota-ve-temizlik)
12. [`screenshot` — kare yakalama](#12-screenshot--kare-yakalama)
13. [`exception` — hata haritalama](#13-exception--hata-haritalama)
14. [Modellerin iç işleyişi](#14-modellerin-iç-işleyişi)

---

## 1. `channel` — kanal yaşam döngüsü

9 sınıf. Sistemin giriş kapısı: dış bir yayını alıp MediaMTX'te yayınlanabilir
hâle getiriyor.

### 1.1 Kanal ekleme — adım adım

```
POST /api/channels
   │
   ├─ 1. Doğrulama (Hibernate Validator)
   │     mediamtxPath: ^[A-Za-z0-9_-]+$
   │
   ├─ 2. requireCapacity()
   │     aktif kanal >= channels.max-active ise 409
   │
   ├─ 3. SourceProbe.probe(sourceUrl)      ← en kritik adım
   │     ├─ HTTP değilse: atla (RTSP/SRT/UDP'de playlist yok)
   │     ├─ playlist indir (8 sn zaman aşımı)
   │     ├─ master mı? değilse atla
   │     ├─ varyantları ayrıştır
   │     └─ segment sınırına sığan EN YÜKSEK varyantı seç
   │
   ├─ 4. Veritabanına yaz
   │     effectiveSourceUrl = probe sonucu
   │     sourceWidth/Height  = seçilen varyantın boyutu
   │
   ├─ 5. Rendition doğrulaması
   │     kaynak 720p ise 1080p rendition reddedilir
   │
   └─ 6. MediaMtxService.applyPath()
         ├─ rendition çıkış path'lerini ÖNCE oluştur
         └─ kaynak path'i yaz (POST /v3/config/paths/add)
```

**Adım 5 neden sonra:** rendition doğrulaması için kaynağın gerçek
çözünürlüğü gerekiyor ve o ancak probe'dan sonra biliniyor.

**Adım 6'da sıra neden önemli:** MediaMTX **tanımsız bir path'e yayın kabul
etmiyor**. Transcode ffmpeg'i `kanal1_720p`'ye basmaya çalıştığında o path
yoksa `400 Bad Request` alıyor ve rendition hiç oluşmuyor.

### 1.2 `SourceProbe` — segment sınırı sorunu

Bu sınıf iki ölçülmüş sorunu çözüyor.

**Sorun:** MediaMTX'e master playlist verildiğinde **en yüksek bant
genişlikli** varyantı seçiyor. O varyantın segmentleri gohlslib'in ~4 MB
sınırını aşarsa yayın **hiç başlamıyor** ve tek belirti log'daki
`max recorded size exceeded` satırı oluyor — kullanıcı hata görmüyor.

**Ölçüm:**

| Kaynak | Segment | Sonuç |
|---|---|---|
| TRT 720p | 3,01 MB | çalışıyor |
| TRT 1080p | 4,29 MB | **düşüyor** |

`hlsSegmentMaxSize=500M` denendi, **işe yaramadı** — o ayar HLS *sunucusunu*
etkiliyor, kaynak okuyucusunu değil.

**Belirleyici olan bant genişliği değil, segment başına bayt:**

```
DW      6,2 Mbps · 1080p · 2 sn segment  → sorunsuz
TRT     6,9 Mbps · 1080p · 6 sn segment  → düşüyor
```

**Tahmin formülü:**

```
segment_bayt ≈ (BANDWIDTH / 8) × EXT-X-TARGETDURATION
```

`BANDWIDTH` master playlist'ten, `TARGETDURATION` varyant playlist'i
indirilerek. Varyant playlist'i okunamazsa 6 saniye varsayılıyor (HLS'te en
yaygın değer).

**Seçim mantığı:** varyantlar kaliteden düşüğe sıralı; sınıra sığan **ilk**
(yani en yüksek) alınıyor. Hiçbiri sığmazsa en düşüğü deneniyor — kesin çözüm
değil ama "hiçbir şey yapma"dan iyi ve kullanıcı sebebi notta görüyor.

**Hata fırlatmıyor.** Kaynak o an erişilemiyorsa kanal yine kaydedilebilmeli;
yayın kaynakları geçici olarak düşer ve bu yüzden kanal düzenlemeyi
engellemek arızayı çözmeyi imkânsızlaştırırdı.

### 1.3 `TranscodeCommand` — rendition üretimi

MediaMTX **transcode yapmıyor** (119 ayarının hiçbiri kodlamayla ilgili
değil). Çözünürlük düşürmenin tek yolu `runOnAvailable` kancasıyla ffmpeg
çalıştırmak.

Üretilen komut:

```bash
ffmpeg -hide_banner -loglevel warning -nostdin \
  <kodlayıcı girdi argümanları> \
  -rtsp_transport tcp -i rtsp://127.0.0.1:$RTSP_PORT/$MTX_PATH \
  <rendition 1 çıkışı> <rendition 2 çıkışı> ...
```

**Tek süreçte çoklu çıkış.** Kaynak bir kez çözülüyor, her rendition için ayrı
kodlama yapılıyor. Rendition başına ayrı ffmpeg süreci açılsaydı aynı akış N
kez çözülürdü — ölçümde **çözme, kodlamadan pahalıydı**.

`$MTX_PATH` ve `$RTSP_PORT` MediaMTX'in kancaya geçirdiği değişkenler. Kaynak
`127.0.0.1`'den okunuyor çünkü komut MediaMTX konteynerinin içinde çalışıyor.

### 1.4 `ChannelRestorer` — açılışta geri yükleme

MediaMTX path'leri **bellekte**. Kap yeniden başlatıldığında hepsi kayboluyor.

`ChannelRestorer` açılışta `Channel.listActive()` üzerinden hepsini yeniden
yazıyor. Tek tek hata yönetiliyor: bir kanalın kaynağı erişilemez olduğunda
diğerlerinin de ayağa kalkmaması saçma olurdu.

> **Bilinen boşluk:** yalnızca *backend* açılışında çalışıyor. MediaMTX tek
> başına yeniden başlatılırsa path'ler kaybolur ve hiçbir şey fark etmez.
> Bu oturumda bir kez yaşandı — tüm yayınlar sessizce durdu.

---

## 2. `media` — kodlayıcı soyutlaması

Tek sınıf: `VideoEncoder` enum'u. Üç durum, her biri kendi ffmpeg
argümanlarını biliyor.

| | Girdi argümanları | Ölçekleme | Kodek |
|---|---|---|---|
| `NVENC` | `-hwaccel cuda -hwaccel_output_format cuda` | `scale_cuda` | `h264_nvenc -preset p4` |
| `VAAPI` | `-vaapi_device /dev/dri/renderD128` | yazılım | `h264_vaapi` |
| `YAZILIM` | — | `scale` | `libx264` |

**NVENC tam GPU hattı:** çözme NVDEC'te, ölçekleme `scale_cuda` ile CUDA'da,
kodlama NVENC'te — kareler **hiç sistem belleğine inmiyor**. En verimli yol.
`channels.gpu-full-pipeline=false` ile yazılım ölçeklemeye düşülebiliyor
(sürücü ya da kaynak sorun çıkarırsa).

**VAAPI'de ölçekleme bilerek yazılımda:** tam VAAPI hattı (`-hwaccel vaapi`
ile çözme) denendi ve bu geliştirme makinesindeki kaynakta çalışmadı.

**İki ayrı ayar var** (`channels.encoder`, `videos.encoder`) çünkü kodlama iki
farklı konteynerde yapılıyor ve ikisi farklı aygıtlara erişebilir.

**Neden elle seçiliyor:** kodlama **başka bir konteynerde** yapıldığı için
backend oradaki donanımı göremez ve kendiliğinden doğru seçimi yapamaz.

**Ölçülen maliyet:** VAAPI ~%34 CPU/rendition, `YAZILIM` bunun birkaç katı.
Karşılaştırma: MediaMTX'in kendisi 12 kanal + 15 radyoyu %25 CPU ile taşıyor.
Yani ölçeklemenin önündeki duvar kanal sayısı değil, **transcode**.

---

## 3. `dvr` — geçmişe erişim

3 sınıf. MediaMTX'in playback sunucusuna (`:9996`) vekillik ediyor.

### 3.1 Üç işlem

| Metot | MediaMTX ucu | Ne yapıyor |
|---|---|---|
| `timeline()` | `GET /list?path=` | Kayıt bulunan aralıklar |
| `stream()` | `GET /get?path=&start=&duration=&format=` | Aralığı MP4/fMP4 olarak |
| `recordedSpans()` | `GET /list?path=` | Kırpma ve doğrulama için |

### 3.2 `requireChannel` vs `requireDvrChannel`

İki farklı kanal yükleyici var ve ayrımları önemli:

| Metot | Kullanan | DVR şartı |
|---|---|---|
| `requireChannel` | `stream()` | **yok** |
| `requireDvrChannel` | `timeline()`, `recordedSpans()` | var |

**Neden `stream()` muaf:** geriye sarması kapalı bir kanalda da diskte kayıt
**bulunabilir** — manuel ve planlı kayıt iş süresince kaydı açıyor. Şartı
korumak, var olan kaydı okumayı da engelliyordu.

### 3.3 `clampToRecorded` — kırpma

Kullanıcının düğmeye bastığı an ile MediaMTX'in yazmaya başladığı an aynı
değil. Ölçülen bölütler:

```
14:28:31 → 14:28:39   (9 sn)
14:28:45 → 14:29:21   (36 sn)
```

Algoritma: istenen aralıkla kesişen tüm bölütleri gez, **en geniş örtüşmeyi**
al. Aralık birden fazla bölüte yayılmış olabilir (kayıt sırasında kaynak
koptu); MediaMTX boşluklu aralığı sorunsuz veriyor — ölçüldü, 10 dakikalık
boşluklu pencereden 57 MB geçerli MP4 çıktı.

Hiç örtüşme yoksa `Optional.empty()` dönüyor ve çağıran anlaşılır bir hata
veriyor.

---

## 4. `clip` — klip, kayıt, planlı kayıt

19 sınıf, sistemin en büyük paketi. Üç farklı ürün aynı hattı kullanıyor.

### 4.1 Üçünün farkı yalnızca aralığın belirlenmesi

| | Aralık nasıl belirlenir | Origin |
|---|---|---|
| Aralık seçimi | çizelgeden sürükleyerek | `ARALIK` |
| Manuel kayıt | başlat → durdur | `MANUEL_KAYIT` |
| Planlı kayıt | baştan verilir | `MANUEL_KAYIT` |

Üçü de aynı `clips` tablosuna, aynı kuyruğa, aynı işçiye gidiyor.

### 4.2 Klip üretimi — tam akış

```
1. ClipService.create()
   ├─ süre doğrulaması (clips.max-duration-minutes, varsayılan 120)
   ├─ kota denetimi (QuotaService.requireRoom)
   ├─ DVR şartı — YALNIZCA origin=ARALIK için
   ├─ tam kapsama denetimi — YALNIZCA origin=ARALIK için
   ├─ clips satırı (BEKLIYOR)
   └─ ClipQueuedEvent ateşle

2. ClipQueue.publish()        Redis LPUSH bekleyen listesine

3. ClipConsumer               BLMOVE ile bloklanarak bekliyor
   └─ ClipWorker.claim()      BEKLIYOR → ISLENIYOR (kapasite denetimiyle)

4. ClipWorker.loadJob()       @Transactional
   ├─ objectKey üret: <kullanıcı>/<kanal>/<id>.mp4
   └─ recordingPath çöz       ← transaction İÇİNDE

5. ClipWorker.process()       transaction DIŞINDA
   ├─ DvrService.streamPath()  MediaMTX'ten akış
   └─ ClipStorage.put()        MinIO'ya AKIŞ HALİNDE

6. markReady()                HAZIR + objectKey + sizeBytes
7. ClipQueue.ack()            işleniyor listesinden düş
```

**Adım 4-5 ayrımı kritik:** `process()` bilerek transaction dışında.
2 saatlik bir klip 6 Mbps'te ~5,4 GB eder; o süre boyunca veritabanı
bağlantısı ve satır kilidi tutulamaz. Bu yüzden gereken her şey (`recordingPath`,
`objectKey`) transaction içinde çözülüp `ClipJob` record'una taşınıyor.

> Bu ayrımı bozmak gerçek bir hataya yol açtı: `stream()`'e kanal kimliği
> verilince `process()` veritabanına dokundu ve `ContextNotActiveException`
> aldı. Çözüm, path'i `loadJob`'da çözüp taşımak.

### 4.3 `ClipQueue` — Redis deseni

İki liste:

```
<key>:bekleyen     yeni işler LPUSH ile
<key>:isleniyor    işçi aldığında BLMOVE ile buraya
```

**`BLMOVE` neden `BRPOP` değil:** tek adımda taşıma. `BRPOP` kullanılsaydı
işçi işi aldıktan hemen sonra çökerse iş **hiçbir listede olmaz** ve Redis
tarafında iz bırakmadan kaybolurdu.

**`publish()` hata yutuyor:** Redis erişilemezse iş `clips` tablosunda
`BEKLIYOR` olarak durmaya devam eder ve süpürücü onu bulur. İstisna fırlatmak,
kullanıcının klip isteğini Redis yüzünden reddetmek olurdu — oysa iş
kaydedilmiş durumda.

**`clearProcessing()`:** açılışta `isleniyor` listesini temizliyor. Bir işçi
süreç ortasında ölürse iş orada asılı kalır; veritabanı süpürücüsü zaten
toparlıyor, bu yalnızca Redis'in sınırsız büyümesini engelliyor.

### 4.4 İki katmanlı talep (claim)

**Redis yolu** — normal: `claim(clipId)` tek işi `BEKLIYOR → ISLENIYOR` yapıyor.

**Süpürücü yolu** — güvenlik ağı: `claimBatch()` `FOR UPDATE SKIP LOCKED` ile
toplu alıyor.

```java
find("status = ?1 order by createdAt", BEKLIYOR)
    .withLock(LockModeType.PESSIMISTIC_WRITE)
    .page(0, slots)
```

`SKIP LOCKED` sayesinde iki işçi aynı anda çalışsa bile aynı satırı almıyor.
**Tekilliği garanti eden şey bu adım**, Redis değil — Redis en-az-bir-kez
teslim ettiği için aynı iş iki kez bildirilebilir.

### 4.5 Kapasite sınırı

```java
if (Clip.count("status", ISLENIYOR) >= concurrency) return false;
```

Sınır dolu olduğunda iş `BEKLIYOR` bırakılıyor. Sınırsız olsaydı onlarca klip
aynı anda MediaMTX'ten çekilir, disk ve ağ doyar, **canlı yayın etkilenirdi**.

### 4.6 `ChannelRecordingGate` — geçici kayıt

DVR'ı kapalı kanalda kayıt gerektiğinde MediaMTX'te kaydı açıp iş bitince
kapatıyor.

```
acquire(channel)
  ├─ requireLive()          kanal gerçekten yayında mı
  ├─ dvrEnabled ise         false dön (dokunma)
  └─ applyPath(record=true) → true dön

release(channelId, açılmıştı)
  ├─ açılmadıysa            çık
  ├─ ActiveRecording.anyTemporaryOn()   ┐ başka iş var mı
  ├─ ScheduledRecording.anyTemporaryOn()┘
  └─ applyPath(record=channel.dvrEnabled)
```

**Kapatma kararı tek soruya bakıyor:** *bu kanalda kaydı kendisi açmış başka
bir iş kaldı mı?* Manuel ve planlı kayıt aynı kanalda çakışabildiği için, her
biri kendi başına kapatsaydı biri bitince diğerinin aralığı ortasından
kesilirdi.

**`@Transactional` (REQUIRED, REQUIRES_NEW değil):** planlı kayıtta emrin
durumu dış transaction'da değişiyor; ayrı bir transaction onu henüz göremez ve
"hâlâ süren iş var" sanıp kaydı hiç kapatmazdı.

**Kanal kimlikle alınıyor, nesneyle değil:** çağıranlar `finally` içinden,
kendi transaction'ları kapandıktan sonra çağırıyor. Oraya bir `Channel`
taşımak lazy proxy'yi oturumsuz bırakıyor ve `LazyInitializationException`
veriyordu — yaşandı, kayıt durduruluyor ama uç 500 dönüyordu.

### 4.7 Manuel kayıt — durdurma sırası

```
stop()
 ├─ tx1: satırı sil, başlangıcı ve kanalId'yi al
 ├─ end = now()
 ├─ gate.release()          ← ÖNCE kapat: son bölüm tamamlansın
 ├─ clampToRecorded()       ← diskte gerçekten olana kırp
 └─ tx2: clipService.create()
```

**Ayrı transaction'lar:** aynı işlemde olduklarında klip doğrulaması
başarısız olunca rollback tetikleniyor, satır geri geliyor ve kullanıcı kaydı
**bir daha hiç durduramıyordu**. Yaşandı.

**Planlı kayıtta sıra ters** — `release` en sonda (`finally`): emir hâlâ
`KAYITTA` olduğu için erken bırakılsa kapı onu "süren iş" sayıp kaydı hiç
kapatmazdı.

### 4.8 Planlı kayıt

`ScheduledRecordingScheduler` 30 saniyede bir yokluyor:

```
BEKLIYOR + (başlangıç - 15 sn) geldi  → begin()   → KAYITTA
KAYITTA  + bitiş geçti                → complete() → TAMAMLANDI | BASARISIZ
```

**15 saniye pay:** MediaMTX kaydı segment sınırında başlatıyor; tam anında
açılırsa ilk saniyeler eksik kalabiliyor. Erken açmak birkaç saniyelik segment
demek, geç açmanın telafisi yok.

**Neden yoklama, neden zamanlanmış görev değil:** her emir için bir
`ScheduledExecutorService` görevi açılsaydı sunucu yeniden başladığında hepsi
kaybolurdu. Veritabanını yoklamak, açılışta geçmiş aralıkları toplamayı da
bedava veriyor.

---

## 5. `video` — kütüphane

9 sınıf. Klip hattının aynası ama **iki fark** var: dosya kullanıcıdan geliyor
ve ffmpeg gerekiyor.

### 5.1 İki adımlı yükleme

```
1. POST /api/videos
   ├─ kota denetimi (beklenen boyutla)
   ├─ videos satırı (YUKLENIYOR)
   ├─ objectKey üret: <kullanıcı>/<uuid>/kaynak.<uzantı>
   └─ imzalı PUT adresi döndür (15 dakika)

2. Tarayıcı doğrudan MinIO'ya PUT eder      ← backend'den geçmiyor

3. POST /api/videos/{id}/tamamlandi
   ├─ MinIO'da nesne var mı (stat)
   ├─ boyutu kaydet
   └─ ISLENIYOR + kuyruğa
```

**Dosya backend'den geçmiyor.** 5 GB'lık bir video Quarkus üzerinden akıtılsaydı
bellek ve bağlantı süresi sorun olurdu.

**Anahtar sunucuda üretiliyor.** İstemcinin verdiği dosya adından türetilseydi
yol ayracı veya başka bir kaydın anahtarı gönderilebilir, imzalı adres o
nesneyi ezmeye yarardı.

**İçerik tipi imzaya dahil edilmiyor:** tarayıcının gönderdiği `Content-Type`
imzadakiyle birebir eşleşmezse yükleme reddediliyor ve teşhisi zor bir
kırılganlık oluyor. Bedeli yanlış içerik tipiyle kaydedilme; işçi bunu
düzeltiyor.

### 5.2 `VideoWorker` — işleme

```
1. Kaynağı MinIO'dan geçici dosyaya indir
2. MediaTools.probe()         ffprobe: süre, çözünürlük, kodek
3. needsFastStart()           moov atom başta mı
   └─ değilse remuxFastStart()  -movflags +faststart
4. thumbnail()                belirli saniyeden JPEG
5. previewClip()              5 sn, 480p, sessiz klip
6. Hepsini MinIO'ya yükle
7. HAZIR
```

**`+faststart` neden:** MP4'te `moov` atomu dosyanın sonundaysa tarayıcı
oynatmaya başlamadan **tüm dosyayı indirmek zorunda**. Remux bunu başa
taşıyor — yeniden kodlama yok, yalnızca kopyalama.

**Önizleme klibi neden asıl video değil:** kart üzerinde fareyle beklenince
oynayan klip. Asıl videoyu oynatmak 1080p bir kaynakta birkaç saniye için
birkaç megabayt indirmek olurdu; klip ~200-400 KB.

### 5.3 `handleFailure` — bir hata ve düzeltmesi

Yükleme zaman aşımında kaydın *yenilenip yenilenmediğini* anlamak gerekiyor.
İlk sürüm `thumbnailKey != null` bakıyordu — ama kullanıcı yükleme sırasında
küçük resim koyabildiği için doğrulanmamış dosyalar `HAZIR` işaretlenirdi.
`completedAt != null`'a çevrildi.

---

## 6. `radio` — ses yayınları

5 sınıf. Kanallardan ayrı çünkü radyoda rendition merdiveni ve DVR yok, buna
karşılık **köprü** modu var.

### 6.1 İki kaynak türü

| | Ne yapılıyor | Maliyet |
|---|---|---|
| `DOGRUDAN` | Adres MediaMTX `source` alanına | ~0 |
| `KOPRU` | ffmpeg çekip AAC'ye kodluyor | **%2,6 CPU** (ölçüldü) |

**Neden köprü gerekiyor:** MediaMTX `http(s)` kaynaklarını **HLS sayıyor**,
düz MP3 okuyamıyor. Ölçümde `source` olarak verilen bir MP3 adresi
`hlsSource` olarak sınıflandı ve `bytesReceived` **hiç artmadı**.

**Neden kullanıcıdan açıkça alınıyor:** yanlış tahminin cezası görünür bir
hata değil, **hiç başlamayan bir yayın**. Otomatik tahmin de güvenilir değil —
Icecast adreslerinin çoğunda uzantı yok (`/canli`, `/stream/1`), HLS
adreslerinin hepsi `.m3u8` ile bitmiyor.

### 6.2 Komut enjeksiyonu savunması

Köprü komutu **kabukta** çalışıyor (MediaMTX `runOnInit` kancası), dolayısıyla
adres doğrudan gömülemez. İki katman:

1. Adres tek tırnak içine alınıyor
2. Tek tırnak içeren adresler **reddediliyor**

İkincisi olmasaydı `'; komut; '` biçiminde bir adres tırnaktan çıkıp medya
sunucusu konteynerinde komut çalıştırabilirdi.

İzinli karakter kümesi RFC 3986'nın yaygın alt kümesi; boşluk, tırnak, ters
tırnak, `;`, `|`, `$`, `<`, `>` ve parantezler dışarıda.

**Kanallarda bu risk yok:** orada adres MediaMTX'in `source` alanına yazılıyor
ve kabuğa hiç uğramıyor.

---

## 7. `VAD` — ses etkinliği tespiti

8 sınıf. Sesi alıp konuşma bölütlerine ayırıyor.

### 7.1 Tam akış

```
AudioStream          ffmpeg → 512 örneklik kareler (32 ms)
      ↓
SileroVad            her kareye konuşma olasılığı [0,1]
      ↓
SpeechSegmenter      histerezisli durum makinesi → bölütler
      ↓
VadService           kuyruk → SttClient → HTTP → stt-worker
                     ve/veya WAV olarak diske
```

### 7.2 `AudioStream` — ses çıkarma

```bash
ffmpeg -v error -rtsp_transport tcp -allowed_media_types audio \
       -i rtsp://mediamtx:8554/<path> \
       -vn -ac 1 -ar 16000 -f s16le -
```

| Bayrak | Neden |
|---|---|
| `-rtsp_transport tcp` | UDP'de paket kaybı sessiz ses boşluğu yapar; VAD onu sessizlik sanar |
| `-allowed_media_types audio` | **Ölçüldü: %1,5 → %0,8 CPU.** Video track'i RTSP'de hiç SETUP edilmiyor |
| `-ac 1 -ar 16000` | Silero ve Whisper'ın istediği biçim |
| `-f s16le -` | Ham PCM, stdout'a |

**İki tuzak:**

`stderr ayrı iş parçacığında okunmalı` — okunmazsa boru dolar, ffmpeg yazarken
bloke olur ve süreç **sessizce donar**. Belirtisi "ffmpeg çalışıyor ama kare
gelmiyor". Son 20 satır saklanıyor; süreç ölünce sebebi orada.

`readFully kullanılmalı` — `read()` kısa dönebilir ve eksik okunan bir kare
**sonraki tüm kareleri kaydırır**. Model bunu belli etmez, yalnızca skorlar
bozulur.

**Zaman çıpası:** duvar saati **kullanılmıyor**. Her karede `Instant.now()`
çağrılsaydı ağ tıkanmasında zaman kayar ve bir daha toparlamazdı. Bunun yerine:

```
mutlak_an = çıpa + (okunan_örnek / 16000)
```

### 7.3 `SileroVad` — model çağrısı

```java
girdi = context(64) + frame(512)          // 576 örnek
tensörler: input[1,576], state[2,1,128], sr(scalar int64)
out = session.run(...)
p = out[0][0]
state = out[1]
context = frame'in son 64 örneği
```

**Kanal başına ayrı nesne.** LSTM durumu ve bağlam kareler arasında taşınıyor;
iki kanal aynı nesneyi kullanırsa iki yayının sesi birbirine karışır — **ve
hata alınmaz**, sonuçlar sessizce bozulur.

**Tensörler yerel bellek.** `try-with-resources` olmadan 32 ms'de bir sızdırır
ve süreç saatler içinde şişer.

**Açılışta imza doğrulaması:** girdi adları `input`/`state`/`sr` mi, durum
tensörü `[2,?,128]` mi. Tutmuyorsa açıkça patlıyor — v4 modeli h/c kullanıyor
ve bu sınıfla çalışmaz.

### 7.4 `SpeechSegmenter` — durum makinesi

```
KAPALI ──(N kare p>0,50)──► AÇIK ──(600 ms p<0,35)──► BEKLEYEN
   ▲                          ▲                          │
   │                          └───(p>0,50 ile devam)─────┤
   └────────(süre ≥ MIN_EMIT_MS, yayınla)────────────────┘
```

| Parametre | Değer | Neden |
|---|---|---|
| Açma / kapatma eşiği | 0,50 / 0,35 | Tek eşik sınırda titrer |
| En kısa konuşma | 250 ms | Öksürük, kapı sesi elenir |
| Kapatma sessizliği | 600 ms | Cümle içi duraklama bölmesin |
| Kenar payı | 250 ms | İlk/son hece kırpılmasın |
| En uzun bölüt | 25 sn | Whisper penceresi taşmasın |
| En kısa yayın | 5 sn | Kısa parça Whisper'da bağlamsız kalır |

**`BEKLEYEN` durumunun gerekçesi:** kapanan kısa bir bölüt hemen
yayınlanmıyor; sessizlik biriktirilmeye devam ediyor ve konuşma yeniden
başlarsa **aynı bölüt** olarak sürüyor. Böylece kısa parçalar birleşiyor ama
**zaman damgaları dürüst kalıyor** — aradaki sessizlik seste gerçekten var.

Alternatif (iki kısa bölütü sonradan yapıştırmak) damgaları yalancı yapardı.

**Geriye dönük halka tampon:** konuşmanın başladığı anlaşıldığında o ses
çoktan geçmiştir — hem 250 ms pay hem kararı verdiren 250 ms. İkisi de
500 ms'lik halka tampondan geri alınıyor.

**Zorla kesim:** 25 saniyeyi aşan bölüt kesiliyor ve son 2 saniye yeni bölüte
devrediliyor. Örtüşme, Whisper'ın iki parçadaki metni hizalayabilmesi için.

### 7.5 `VadService` — kuyruk ve yaşam döngüsü

**Yoklama** (30 sn): `MediaMtxService.pathStates()` ile hazır kanallar
alınıyor, işçiler eşitleniyor.

MediaMTX'e ulaşılamazsa **işçiler kapatılmıyor** — `pathStates()` boş harita
dönüyor ve bunu "hiçbir kanal yayında değil" saymak anlık bir aksaklıkta tüm
kanalların altyazısını keserdi.

**STT kuyruğu ayrı:** çözümleme 25 sn bölüt için ~6,5 sn sürüyor (ölçüldü).
`onSegment` doğrudan STT'yi çağırsaydı kare döngüsü dururdu, ffmpeg borusu
dolar, kareler kaybolurdu.

Kuyruk **sınırlı (64) ve dolduğunda bölüt düşürülüyor**, uyarıyla. Sınırsız
olsaydı bellek sessizce büyürdü; beklemek ise yakalamayı durdurmak demek.

---

## 8. `stt-worker` — konuşma tanıma ve çeviri

Python/FastAPI. Üç modül.

### 8.1 Uçlar

| Uç | Ne yapıyor |
|---|---|
| `POST /transcribe?channel=&start=&end=` | Ham PCM gövdede, sonuç JSON |
| `GET /health` | Model yüklendi mi, hangi diller |
| `GET /metrics` | Toplam sayaçlar, **gerçek zaman katı** |

**Zaman damgaları kullanılmıyor, geri veriliyor.** Eşleştirmeyi çağıran
yapıyor; burada tutulmalarının sebebi günlükte bir bölütü yayındaki anına
bağlayabilmek.

### 8.2 `Transcriber`

```python
audio = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768.0
with semaphore:                      # STT_MAX_CONCURRENCY
    segments, info = pipeline.transcribe(
        audio,
        task="translate",            # her dil → İngilizce
        beam_size=...,
        batch_size=...,
        vad_filter=False,            # Silero zaten yaptı
    )
    text = " ".join(s.text for s in segments)   # KILIT İÇİNDE
```

**Kilit tüketimi kapsamalı:** `transcribe()` **tembel bir üreteç** döndürüyor;
tüketilmeden hiçbir iş yapılmıyor. Kilit tüketimden önce bırakılsaydı
eşzamanlılık sınırı hiçbir şeyi sınırlamazdı.

**`vad_filter=False`:** Silero zaten Java tarafında çalıştı. İkinci kez VAD
koşmak boşa CPU ve bölüt sınırlarını bozar.

**`local_files_only=True`:** model eksikse sessizce indirmeye çalışmak yerine
açıkça patlasın.

### 8.3 `Translator`

```python
cümlelere böl (regex: (?<=[.!?])\s+)
900 karakteri aşan "cümle"yi kelime sınırından böl
tek yığında çevir (padding, truncation, max_length=512)
sonuçları birleştir
```

**Cümlelere bölme neden:** Marian modelleri cümle düzeyinde eğitildi; uzun
paragraf verilince sonu **sessizce kırpılıyor**.

**Tek yığın neden:** cümle cümle çevirmek her cümle için ayrı bir ileri geçiş
demek; kısa cümlelerde ek yük işin kendisinden büyük oluyor.

**Hata izolasyonu:** bir dilin çevirisi patlarsa o dil sonuçta yer almıyor,
diğerleri üretiliyor. Tek dilin hatası tüm altyazıyı düşürmemeli.

### 8.4 Model adı eşlemesi

```python
TRANSLATION_MODELS = {
    "tr": "Helsinki-NLP/opus-mt-tc-big-en-tr",
    "de": "Helsinki-NLP/opus-mt-en-de",
    "ru": "Helsinki-NLP/opus-mt-en-ru",
}
```

**Formülle üretilemez.** Ölçüldü: `opus-mt-en-tr` → HTTP 401 (yok),
`opus-mt-tc-big-en-tr` → 200. Türkçe yalnızca Tatoeba Challenge varyantında
var ve o model diğerlerinden büyük (~1 GB / ~300 MB).

İndirme betiği model adlarını `app.config`'ten okuyor — ayrı listelenselerdi
ikisi zamanla ayrışır ve eksik model ancak çalışma anında fark edilirdi.

### 8.5 Java ↔ Python sınırı

`SttClient` **HTTP/1.1 zorunlu kılıyor**:

> JDK istemcisi varsayılan olarak HTTP/2 deniyor ve şifresiz bağlantıda bunu
> `Upgrade` başlığıyla yapıyor. uvicorn (h11) yalnızca HTTP/1.1 konuşuyor ve
> bu el sıkışmada **POST gövdesi düşüyor** — sunucuya boş gövde ulaşıyor,
> hata da vermiyor.

Yaşandı: STT her bölüte `400 Boş gövde` döndü, oysa bölütler 380 KB'ydi ve
aynı veri `curl` ile sorunsuz gidiyordu.

---

## 9. `subtitle` — altyazı deposu ve canlı akış

5 sınıf. Veri modeli kararları ve WebSocket dağıtımı burada.

### 9.1 Neden JSONB

```sql
metinler JSONB   -- {"en": "...", "tr": "...", "de": "...", "ru": "..."}
```

Bir bölütün tüm dilleri **birlikte** üretiliyor ve birlikte okunuyor. Dil
başına ayrı satır her sorguda dört kat birleştirme ve tutarsız kalma riski
getirirdi.

**Quarkus tuzağı:** Hibernate'in JSON sütunları için REST'in `ObjectMapper`'ını
kullanması **reddediliyor** — REST için yapılan bir özelleştirme (alan
gizleme, null atlama) veritabanına yazılanı sessizce bozabilir, veri kaybına
kadar gidebilir. Uygulama açılışta patlıyor; doğru davranış. Çözüm:

```properties
quarkus.hibernate-orm.mapping.format.global=ignore
```

### 9.2 Kesişim sorgusu

```sql
where channel_id = ? and baslangic < :to and bitis > :from
```

Aralıkla **kesişen** her bölüt dönüyor, yalnızca tamamen içinde kalanlar
değil: oynatıcı 3 saniyelik pencere soruyor ve o pencereye taşan bir cümle de
gösterilmeli.

### 9.3 Tekillik

```sql
CREATE UNIQUE INDEX altyazi_tekil ON altyazilar (channel_id, baslangic);
```

STT yeniden denenirse ya da işçi iki kez açılırsa çift kayıt oluşur ve arayüz
altyazıyı çift gösterirdi. Servis ayrıca yazmadan önce `exists()` kontrolü
yapıyor.

---

### 9.4 Canlı akış — neden WebSocket

İlk sürüm **yoklamaydı**: oynatıcı saniyede bir `GET /api/channels/{id}/altyazilar`
çağırıyordu. İki sorunu vardı:

- 20 karo açıkken saniyede 20 istek — hepsi çoğu zaman boş dönüyor,
- altyazı en iyi ihtimalle bir tik (1 sn) geç görünüyor.

WebSocket'te altyazı **üretilir üretilmez** gidiyor ve boşta hiç trafik yok.

### 9.5 Mimari — neden araya Redis giriyor

```
video-worker                     backend                    tarayıcı
(altyazıyı üretir)          (WebSocket sunar)
     │                              │                          │
     ├─ veritabanına yaz            │                          │
     │                              │                          │
     └─ Redis PUBLISH ─────────────►│ SUBSCRIBE                │
        altyazi:<channelId>         └── sendText ─────────────►│
```

**Doğrudan çağrı yapılamıyor** çünkü üreten ve sunan **ayrı konteynerler**:
`video-worker` ffmpeg'e sahip olduğu için VAD orada, WebSocket ise
tarayıcının konuştuğu `backend`'de. Ortak bir bildirim kanalı gerekiyor ve
Redis zaten klip kuyruğu için var.

**Redis doğruluk kaynağı değil.** Sıra bilinçli:

```java
subtitles.kaydet(...)     // 1. veritabanı
broadcaster.publish(...)  // 2. yayın
```

Ters sırada olsaydı izleyici altyazıyı görür ama **sayfayı yenilediğinde
kaybolurdu**. Redis erişilemezse canlı akış kesilir, veri kaybolmaz —
`publish` bu yüzden hatayı yutuyor.

### 9.6 Kanal başına abonelik

```java
pubsub.subscribe("altyazi:" + channelId, mesaj -> yayinla(channelId, mesaj))
```

Tek bir kanala abone olup mesajları süzmek de mümkündü. Seçilmedi: 20 kanal
çalışırken tek izleyicinin açtığı bir karo yüzünden **20 kanalın tüm
altyazısı** bu sürece akardı.

**Abonelik yaşam döngüsü:**

```
ilk izleyici bağlandı  → SUBSCRIBE
son izleyici ayrıldı   → UNSUBSCRIBE
```

Kimse izlemiyorken Redis'ten veri çekmenin anlamı yok.

### 9.7 Gönderim ve oturum temizliği

```java
session.getAsyncRemote().sendText(mesaj, sonuc -> {
    if (sonuc.getException() != null) set.remove(session);
});
```

**Asenkron gönderim:** yavaş bir istemci diğerlerini bekletmemeli. Eşzamanlı
gönderimde tek bir donmuş tarayıcı o kanalın tüm izleyicilerini kilitlerdi.

**Oturumlar iki yerde temizleniyor** — `onClose` ve gönderim hatası. Sebebi:
`onClose` **her zaman tetiklenmiyor**. Ağ kablosu çekildiğinde tarayıcı
kapanış çerçevesi gönderemez ve sunucu bunu ancak yazmaya çalışınca anlar.
Yalnızca `onClose`'a güvenilseydi ölü oturumlar birikir ve bellek sızardı.

Ayrıca her yayın öncesi `session.isOpen()` kontrol ediliyor.

### 9.8 nginx — atlanması kolay üç ayar

```nginx
location /ws/ {
    proxy_pass http://backend:8081;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
}
```

| Ayar | Atlanırsa |
|---|---|
| `proxy_http_version 1.1` | WebSocket HTTP/1.1 gerektiriyor; 1.0'da el sıkışma olmaz |
| `Upgrade` / `Connection` | nginx **hop-by-hop başlıkları düşürüyor** — el sıkışma sessizce HTTP'ye dönüyor, bağlantı kuruluyor gibi görünüyor ama mesaj gelmiyor |
| `proxy_read_timeout 3600s` | Varsayılan 60 sn. Sessiz bir yayında mesaj gelmediği için bağlantı **her dakika kopardı** |

Üçüncüsü özellikle sinsi: altyazı akarken sorun görünmez, kanal sessizleşince
bağlantı düşer ve yeniden bağlanma döngüsü başlar.

### 9.9 Tarayıcı tarafı — iki kaynak

```
açılışta   REST  /api/channels/{id}/altyazilar?from=&to=   ← geçmiş
sürekli    WS    /ws/altyazi/{id}                          ← canlı
```

**Geçmiş neden gerekli:** WebSocket yalnızca bağlandıktan sonrasını taşıyor.
Sonradan açılan bir karo, o ana kadar üretilmiş altyazıları göremezdi.

**Tekilleştirme:** aynı bölüt iki yoldan gelebiliyor. Anahtar olarak `id`
kullanılıyor; WebSocket olayında `id` olmadığı için `baslangic`'a düşülüyor —
kanal içinde zaten tekil (`altyazi_tekil` indeksi bunu garanti ediyor).

**Yeniden bağlanma:** üstel geri çekilme, 1 sn → 30 sn. Sunucu yeniden
başlarken saniyede bir bağlanmaya çalışmak boşuna yük.

### 9.10 Eşleştirme — mesajın geldiği an değil

```js
const now = handle.playingDate().getTime()
const eslesen = cache
  .filter(s => Date.parse(s.baslangic) <= now && Date.parse(s.bitis) > now)
  .sort((a, b) => Date.parse(b.baslangic) - Date.parse(a.baslangic))[0]
```

**Belirleyici olan mesajın taşıdığı zaman damgası**, ne zaman geldiği değil.
İzleyici HLS yüzünden 6-12 saniye geride; "geldi, göster" mantığı altyazıyı
o kadar erken gösterirdi.

Birden fazla bölüt eşleşirse **en son başlayan** seçiliyor: zorla kesim
sonrası örtüşen bölütlerde yeni olan doğru.

**Önbellek budanıyor:** sınırsız büyürse saatler sonra binlerce kayıt her
tikte süzülürdü.

### 9.11 Bilinen eksik — kimlik doğrulaması yok

`/ws/altyazi/{channelId}` adresini bilen bağlanabiliyor. HLS yayınında da
aynı durum var.

Korumayı tek başına buraya koymak **yanıltıcı bir güvenlik hissi** verirdi:
altyazı, korunmayan bir yayının türevi. İkisi birlikte çözülmeli — token'ı
sorgu parametresiyle taşımak ya da imzalı kısa ömürlü bir bilet üretmek.

---

## 10. `user` + `auth` — kimlik

10 sınıf.

### 10.1 Neden yerel kullanıcı tablosu var

Keycloak kimliğin doğruluk kaynağı, ama:

- Klip/video/ekran görüntüsü sahipliği yabancı anahtar istiyor
- Her sorguda Keycloak'a gitmek kabul edilemez
- Kullanıcı silinse bile içeriğin kime ait olduğu bilinmeli

`users` tablosu Keycloak'ın **aynası**, `keycloak_id` üzerinden eşlenik.

### 10.2 `UserProvisioningFilter`

Her kimliği doğrulanmış istekte yerel kaydın varlığını garanti ediyor.

**Neden filtre, neden her uçta değil:** kayıt kaynağı fark etmeksizin (Keycloak
konsolundan eklenen kullanıcı da olabilir) var olmasını garanti eden **tek
nokta**. Her uç kendi başına kontrol etseydi biri unutulduğunda sessizce
bozulurdu.

**Maliyet:** istek başına `keycloak_id` unique index'i üzerinden bir SELECT.
Yazma yalnızca kayıt eksikse veya ad/rol değişmişse.

**Hata isteği düşürmüyor:** kullanıcı Keycloak'ta doğrulanmış durumda; yerel
aynanın gecikmesi okuma uçlarını engellememeli.

### 10.3 Roller

Keycloak'ta **client rolleri** (realm rolü değil): `Yönetici`, `Moderatör`,
`İzleyici`. `Roles.effective()` yetki gücüne göre azalan sırada tek rol
seçiyor — kullanıcıda birden fazla rol varsa en güçlüsü geçerli.

---

## 11. `storage` — kota ve temizlik

3 sınıf.

### 11.1 `QuotaService`

```java
usage = Clip.totalBytesOf(uid) + Screenshot.totalBytesOf(uid) + Video.totalBytesOf(uid)
```

**Ayrı sayaç tablosu yok.** Boyutlar zaten `size_bytes` sütunlarında; toplam
sorguyla alınıyor. Ayrı bir sayaç, her silme ve eklemeden sonra tutarlı kalması
gereken **ikinci bir doğruluk kaynağı** olurdu ve er geç kayardı.

**Kota dolunca yeni iş reddediliyor, var olan silinmiyor.** Sessizce silmek
kullanıcının verisini habersiz yok etmek olurdu.

**Klip ve kayıtta boyut önceden bilinmiyor** (dosya arka planda üretiliyor), o
yüzden orada yalnızca "kota zaten dolu mu" sorulabiliyor. Yükleme ve ekran
görüntüsünde beklenen boyut biliniyor ve hesaba katılıyor.

### 11.2 `RetentionSweeper`

Süreler `Duration` olarak ayrıştırılıyor — `P30D`, `720h`, `PT12H` hepsi
geçerli. `0` = kapalı.

**Varsayılan olarak kullanıcı verisi silinmiyor.** Klip ve ekran görüntüsü
kullanıcının kendi arşivi; baskıyı kota kursun, saat değil.

Silinmesi varsayılan olan tek şey **başarısız klipler** (`P7D`): dosyaları
zaten yok, yalnızca kullanıcı sebebini görsün diye bekletiliyorlar.

Tur başına **200 kayıt** sınırı — büyük birikimde tek işlem kilitlenmesin.

### 11.3 `StoragePaths`

Slug üretimi: Türkçe harfler elle eşleniyor (`ı→i`, `ğ→g`, `ş→s`, `ç→c`,
`ö→o`, `ü→u`), sonra aksanlar NFD ile ayrıştırılıp atılıyor, kalan güvensiz
karakterler `-` oluyor.

Ad tamamen ayıklanırsa (yalnızca noktalama) kimliğe düşülüyor — anahtarın
`//` ile başlaması nesneyi erişilemez kılardı.

---

## 12. `screenshot` — kare yakalama

3 sınıf.

**Kare tarayıcıda yakalanıyor**, sunucuda değil:

```js
canvas.width = video.videoWidth
ctx.drawImage(video, 0, 0)
canvas.toBlob(...)
```

**Neden:** sunucudan yakalansaydı HLS gecikmesi yüzünden 6-20 saniye ilerideki
bir kare gelirdi. Kullanıcı gördüğü kareyi istiyor.

**Neden çalışıyor:** video hls.js tarafından **MSE ile besleniyor** ve MSE
kaynağı canvas'ı "tainted" yapmıyor, dolayısıyla `toBlob` çalışıyor. Doğrudan
`<video src="...">` olsaydı çapraz kaynak yüzünden canvas kirlenir ve okuma
engellenirdi.

**Ayrı kova** (`ekran-goruntuleri`): galerinin kendi saklama politikası var ve
karışık bir kovada listeleme maliyeti gereksizce artardı.

---

## 13. `exception` — hata haritalama

6 sınıf. Üç eşleyici:

| Eşleyici | Ne yakalıyor | Ne dönüyor |
|---|---|---|
| `AppExceptionMapper` | `AppException` | Kodu ve mesajı olduğu gibi |
| `ValidationExceptionMapper` | Bean Validation | 400 + alan hataları |
| `GenericExceptionMapper` | Diğer her şey | 500 + genel mesaj |

**`GenericExceptionMapper` mesajı gizliyor:** istisna metni yığın izi, SQL
parçası veya iç yol içerebilir. Kullanıcıya "Beklenmeyen bir hata oluştu"
dönüyor, ayrıntı loga yazılıyor.

`ErrorResponse` biçimi sabit: `timestamp`, `status`, `error`, `message`,
`path`, `fieldErrors`. Frontend tek yerde ayrıştırıyor.

---

## 14. Modellerin iç işleyişi

### 14.1 Silero-VAD nasıl çalışıyor

Küçük bir sinir ağı: **STFT tabanlı öznitelik çıkarımı → evrişim katmanları →
LSTM → sigmoid**. Çıktı tek sayı: o 32 ms'lik karede konuşma olma olasılığı.

**LSTM neden var:** konuşma bağlamsal. Tek bir 32 ms'lik kare tek başına
konuşma mı gürültü mü ayırt edilemez; LSTM önceki karelerin özetini taşıyor.
Bu yüzden `state` kareler arasında aktarılmak zorunda.

**64 örneklik bağlam neden:** evrişim katmanının alıcı alanı (receptive field)
kare sınırının ötesine uzanıyor. v5'te bu bağlam **girdiye açıkça ekleniyor**;
v4'te model içeride hallediyordu. Verilmezse evrişim sıfırlarla dolu bir
geçmiş görüyor ve çıkışı sistematik olarak sıfıra yaklaşıyor — **hata değil,
yanlış cevap**.

**Neden bu kadar hızlı (199×):** model 2,2 MB. Karşılaştırma: Whisper `small`
~500 MB, `large-v3` ~3 GB. VAD'ın işi çok daha basit — "konuşma var mı",
"ne söylendi" değil.

### 14.2 Whisper nasıl çalışıyor

**Kodlayıcı-çözücü Transformer.**

```
ses (30 sn pencere)
  → log-Mel spektrogram (80 kanal)
  → Kodlayıcı (Transformer)      ← sesin temsili
  → Çözücü (Transformer)         ← metin üretimi, token token
```

**30 saniyelik sabit pencere:** Whisper her zaman 30 saniyelik girdi bekliyor.
Daha kısa ses **sıfırla dolduruluyor**. Bu, kısa bölütlerin neden nispeten
pahalı olduğunu açıklıyor — 5 saniyelik ses de 30 saniyelik pencere kadar iş.

> Bölütleyicideki `MIN_EMIT_MS = 5 sn` ve `MAX_SEGMENT_MS = 25 sn` bu yüzden.
> 25 saniye, 30'luk pencereye kenar payıyla sığıyor.

**Görev belirteci (`task` token):** çözücüye ilk verilen özel belirteçlerden
biri. `<|transcribe|>` kaynak dilde yazıyor, `<|translate|>` İngilizce'ye
çeviriyor. **Aynı model, aynı geçiş** — çeviri ek maliyet getirmiyor.

**Dil tespiti:** çözücünün ilk adımında dil belirteçleri üzerinde bir olasılık
dağılımı çıkıyor; en yükseği seçiliyor. Bu yüzden "tespit" ayrı bir geçiş
değil, çözümlemenin doğal parçası.

**`beam_size`:** çözücü her adımda en olası N adayı tutuyor (ışın araması).
Büyük değer daha iyi metin, daha yavaş. Varsayılan 5.

**`faster-whisper` farkı:** CTranslate2 çalışma zamanı — ağırlıkları
nicemlenmiş (int8) tutuyor, bellek düzenini yeniden düzenliyor ve toplu
matris çarpımlarını optimize ediyor. Aynı model, aynı çıktı, belirgin daha
hızlı.

**`int8_float16` ne demek:** ağırlıklar int8, ara hesaplar float16. Bellek
yarıya iniyor, hız ~%30 artıyor. **Kalite etkisi ölçülmedi** — kart geldiğinde
ilk ölçüm bu olmalı.

### 14.3 Opus-MT / Marian nasıl çalışıyor

**Kodlayıcı-çözücü Transformer**, Whisper'la aynı aile ama girdisi ses değil
metin ve **dil çiftine özel** eğitilmiş.

**Neden çift başına ayrı model:** her model yalnızca bir yönü biliyor
(`en→tr`). Bu, modeli küçük (~300 MB) ve hızlı tutuyor. Çok dilli tek model
(NLLB gibi) esnek ama büyük ve yavaş.

**`tc-big` varyantı:** Tatoeba Challenge veri kümesiyle eğitilmiş daha büyük
sürüm. Türkçe için yalnızca bu var; küçük varyant yayımlanmamış.

**512 token sınırı:** konumsal kodlamanın eğitildiği en uzun dizi. Aşan girdi
kırpılıyor — **sessizce**. Bu yüzden cümlelere bölme şart.

**`num_beams=1`:** ışın araması kapalı, açgözlü çözümleme. Kalite biraz
düşüyor ama hız belirgin artıyor; altyazıda cümleler kısa olduğu için fark az.

### 14.4 Zincirin toplam maliyeti

Bir bölüt için, ölçülen (CPU, `small`, int8):

```
ffmpeg ses çıkarma       ihmal edilebilir (sürekli, %0,8/kanal)
Silero-VAD               ihmal edilebilir (199× gerçek zaman)
Whisper                  ~4,5 sn / 25 sn ses    ← darboğaz
Opus-MT × 3              ~2 sn                   ← CPU'da, GPU'ya dokunmuyor
──────────────────────────────────────────────
toplam                   6,5 sn / 25 sn = 3,86×
```

İki eşzamanlı istekle bu **1,81×**'e düşüyor — CPU doyuyor.

**20 kanal için 20× gerekiyor.** `large-v3` CPU'da ~0,3-0,5× (literatür), yani
tek kanalı bile taşımaz. GPU zorunluluğu buradan geliyor.
