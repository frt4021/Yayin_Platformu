# Kaynaktan ekrana — uçtan uca mimari

Bu belge bir kaynak yayının MediaMTX'e girdiği andan, izleyicinin
ekranında görüntü + altyazı olarak belirdiği ana kadar geçen **her adımı**
anlatıyor. Amaç: kod üzerinde değişiklik yapmadan önce tam resmi görmek.

---

## 1. Tek bakışta

```
                                   ┌─► video-worker (NVENC/VAAPI) ─┐
                                   │        (renditions varsa)      │
kaynak ──► MediaMTX ───────────────┤                                ├──► nginx /hls/ ──► tarayıcı <video>
(RTSP/RTMP/                       │                                │
 SRT/UDP/HLS)                     └─► video-worker: ffmpeg (ses) ──┘
                                            │
                                            ▼
                                       Silero VAD
                                    (konuşma bölütleri)
                                            │
                                            ▼
                                    bellek-içi kuyruk (64)
                                            │
                                            ▼
                                       stt-worker
                              Whisper(large-v3, task=translate)
                                            │
                                      İngilizce pivot
                                            │
                                            ▼
                                  Marian (opus-mt) EN→tr/de/ru
                                            │
                                            ▼
                              Postgres (altyazilar) + Redis pub/sub
                                            │
                                            ▼
                                backend WS (ws/altyazi/{kanalId})
                                            │
                                            ▼
                              tarayıcı: SubtitleOverlay (video ile senkron)
```

**Kritik nokta:** video yolu ve altyazı yolu birbirinden tamamen bağımsız
iki bağlantı. Aralarında doğrudan hiçbir çağrı yok — senkron olup olmadığı
yalnızca tarayıcıda, iki farklı zaman damgası karşılaştırılarak anlaşılıyor
(bkz. §5).

---

## 2. Video yolu

Bugün sağlıklı çalışıyor, bu oturumun odağı değil ama tam resim için:

1. **MediaMTX**, kanalın `sourceUrl` alanını çeker. Protokol (RTSP / RTMP /
   SRT / UDP / HLS) ayrı bir alan değil — URL'nin şemasından (`rtsp://`,
   `https://...m3u8` vb.) çıkarılıyor.
2. Kanalda `renditions` tanımlıysa (örn. `480p|854x480|800k,...`)
   **video-worker** ffmpeg ile ek kaliteler üretir. Kodlayıcı
   `CHANNELS_ENCODER` env'ine göre seçilir: `NVENC` (NVIDIA donanım
   kodlayıcı), `VAAPI` (Intel/AMD), `YAZILIM` (libx264, CPU). Üretilen
   renditionlar MediaMTX'e geri yazılır.
3. MediaMTX, HLS playlist'i (`.m3u8`) **bellekte** üretiyor, segmentleri
   (`.mp4`) **diske** yazıyor (`hlsDirectory: /hls`).
4. **Tarayıcı**, frontend'in nginx'i üzerinden `/hls/<kanal>/index.m3u8`'i
   çekip `hls.js` ile oynatıyor. nginx bunu MediaMTX'e proxy'liyor.

---

## 3. Altyazı yolu — adım adım

Videodan tamamen ayrı bir bağlantı: **video-worker, MediaMTX'ten sesi de
ayrıca RTSP ile kendi çekiyor** (`MEDIAMTX_RTSP_URL=rtsp://mediamtx:8554`).

### 3.1 Ses çekme

`VadService` / `AudioStream` sınıfı, ffmpeg alt sürecini başlatıp
`rtsp://mediamtx:8554/<mediamtxPath>` akışından sesi **16 kHz, mono,
s16le** (ham PCM) olarak çekiyor. Bu format zorunlu: hem Silero VAD hem
Whisper bu örnekleme hızını bekliyor. Java tarafındaki `VadConfig` ile
Python tarafındaki `config.py`'deki `SAMPLE_RATE` **elle senkron** tutuluyor
— iki ayrı süreç olduğu için paylaşılamıyor; ayrılırlarsa bölüt süreleri ve
zaman damgaları kayar ve hiçbir uyarı çıkmaz.

### 3.2 Silero VAD — konuşma tespiti

Gelen ses akışı, Silero VAD modeline (ONNX formatında, imaja gömülü)
küçük parçalar (frame) halinde veriliyor. Model her frame için
"konuşma mı, sessizlik mi" kararı veriyor. `SpeechSegmenter` bu kararları
biriktirip bölütlere (segment) dönüştürüyor:

- Konuşma başlar, ses parçaları biriktirilir.
- **`VAD_MIN_SILENCE_MS` (400ms)** kadar sessizlik gelirse bölüt **kapanır**
  — doğal cümle sonu kabul edilir.
- Konuşma **`VAD_MAX_SEGMENT_MS` (4000ms)**'yi aşarsa bölüt **zorla
  kesilir** (`kesik=true` işaretlenir) — cümle ortasında olabilir.
- **`VAD_OVERLAP_MS` (800ms)**: zorla kesilen bir bölütten sonraki bölüt,
  öncekiyle bu kadar örtüşerek başlıyor — amaç, cümlenin yarısı kesildiğinde
  bağlamın tamamen kaybolmaması.
- **`VAD_MIN_EMIT_MS`**: kısa bölütleri hemen göndermek yerine bir sonraki
  konuşmayla birleştirip birleştirme penceresi. **Şu an `0`** — yani bu
  birleştirme mekanizması pratikte devre dışı, her bölüt oluştuğu gibi
  hemen gönderiliyor.
- **`VAD_MODEL_VERSION` (v5)**: modelin girdi biçimini belirliyor (v5 → 512
  örnek + 64 örnek bağlam, v4 → 1536 örnek, bağlamsız). **Kozmetik bir alan
  değil** — yanlış sürüm ONNX'i patlatır ya da (daha kötüsü) sessizce boş
  altyazı üretir.

### 3.3 Kuyruğa giriş

Her bölüt (ham PCM + kanal + başlangıç/bitiş zaman damgası),
`VadService`'in **kanal başına** açtığı bellek-içi kuyruğa giriyor:

```java
private final BlockingQueue<SpeechSegment> queue = new ArrayBlockingQueue<>(64);
```

- Sabit **64** — `.env`'den ayarlanamıyor, kodda gömülü.
- Ekleme: `queue.offer(segment)` — kuyruk doluysa **yeni** bölüt
  **reddedilir** (WARN: "Çözümleme kuyruğu dolu, bölüt düşürüldü").
- Alma: `queue.take()` — hep **en eski** bölüt alınır (FIFO).

**Bu kombinasyon bir tuzak:** kuyruk bir kez dolduğunda, içinde kalan
bölütler zaten bayatlamıştır (üretilmelerinden beri zaman geçmiştir) ama
onlar işlenmeye devam eder; taze gelen bölütler ise kapıda reddedilir.
Sistem kendini asla "temiz" bir duruma getiremez, sürekli eski veriyi
işleyip yeniyi atar. Ayrıca **hiçbir yerde bölütün yaşına bakılmıyor** —
90 saniye önce üretilmiş, artık kimsenin göremeyeceği bir bölüt de aynı
öncelikle işleniyor.

### 3.4 stt-worker'a gönderim

"stt-gonderici" adlı iş parçacıkları (havuzda genelde 2 tane) kuyruktan
alıp HTTP ile gönderiyor:

```
POST http://stt-worker:8100/transcribe?channel=<uuid>&start=<iso>&end=<iso>
Content-Type: application/octet-stream
<ham PCM>
```

`STT_URL=http://stt-worker:8100` bu adresi veriyor.
`VAD_STT_ENABLED=true` bu gönderimin açık olup olmadığını kontrol ediyor
(kapalıysa bölütler üretiliyor ama hiçbir yere gönderilmiyor — sadece VAD
çalışıyor demek).

### 3.5 Whisper — çözümleme + İngilizce'ye pivot

`stt-worker/app/stt.py` içindeki `Transcriber`, faster-whisper kütüphanesi
ile modeli yüklüyor:

- **`STT_MODEL` (large-v3)**: model boyutu. `tiny|base|small|medium|large-v3`.
  Küçük modeller Türkçe/Rusça özel isim ve sayılarda belirgin hata veriyor;
  "yayına basılabilir" kalite için `large-v3` gerekiyor.
- **`STT_DEVICE` (cuda)**: `cpu` veya `cuda`. CPU'da `large-v3` gerçek
  zamanın ~%30-50'si hızında çalışıyor — tek kanalı bile taşımıyor.
- **`STT_COMPUTE_TYPE` (int8_float16)**: sayısal hassasiyet.
  `float16|int8_float16|int8`. `int8_float16` belleği yarıya indirip ~%30
  hız veriyor ama **kalite etkisi ölçülmedi** — varsayılmaması gereken bir
  değer.
- **`STT_BEAM_SIZE` (5)**: Whisper'ın arama genişliği (kalite/hız dengesi).
- **`STT_BATCH_SIZE` (16)**: tek bir sesi parçalara bölüp birlikte işleme.
  Kanallar arası yığınlama **henüz yok** — asıl kazanç orada olurdu.
- **`STT_MAX_CONCURRENCY` (4)**: `threading.Semaphore` ile sınırlanan,
  **aynı anda GPU'da çözümlenebilecek bölüt sayısı**. Bu sayı aşılırsa
  yeni istekler bu semaforda **bekler** (düşürülmez, sıraya girer — asıl
  gecikme birikimi burada oluyor).

Model, Whisper'ın `task="translate"` moduyla çağrılıyor — bu, kaynak dil ne
olursa olsun (Türkçe, Arapça, ...) çıktıyı **doğrudan İngilizce'ye çeviriyor
ve o tek geçişte üretiyor**. Ayrıca kaynak dili de tahmin ediyor
(`kaynak_dil`, güven skoruyla).

### 3.6 Marian — İngilizce pivottan hedef dillere

`stt-worker/app/translate.py` içindeki `Translator`, Whisper'ın ürettiği
İngilizce metni hedef dillere çeviriyor:

- **`STT_TARGET_LANGS` (tr,de,ru)**: hangi dillere çevrilecek. Whisper
  zaten İngilizce pivotu sağladığı için burada yalnızca `EN → X` yönleri
  var; kaynak dil kümesi genişlese bile bu liste sabit kalıyor.
- Her dil için **ayrı bir Marian (Opus-MT) modeli** belleğe yükleniyor
  (`Helsinki-NLP/opus-mt-tc-big-en-tr`, `opus-mt-en-de`, `opus-mt-en-ru`).
- **Az önce bu oturumda değiştirildi** (bkz. §7): modeller artık
  `STT_DEVICE` ne ise oraya (`cuda`) taşınıyor ve her dilin **kendi kilidi**
  var — TR çevirisi artık DE/RU'yu bloklamıyor.
- Metin önce cümlelere bölünüyor (`SENTENCE_BOUNDARY` regex) — Marian
  modelleri cümle düzeyinde eğitildiği için uzun paragraf verilirse sonu
  sessizce kırpılıyor.

### 3.7 Sonuç kaydı — Postgres + Redis

`stt-worker`'ın döndürdüğü sonuç (İngilizce metin, kaynak dil, güven,
`{tr, de, ru}` çevirileri) video-worker'a dönüyor ve **iki yere** yazılıyor:

1. **Postgres, `altyazilar` tablosu** — doğruluk kaynağı burası:
   ```sql
   channel_id, baslangic, bitis,   -- MUTLAK zaman damgaları (§5)
   kaynak_dil, guven,
   metinler jsonb,                 -- {"tr": "...", "de": "...", "en": "...", "ru": "..."}
   kesik boolean,                  -- zorla kesildi mi (bkz §3.2)
   created_at
   ```
2. **Redis, `altyazi:<kanalId>` kanalına pub/sub mesajı** — yalnızca
   **bildirim**. Mesaj kaybolursa (Redis restart, ağ sorunu) hiçbir veri
   kaybı olmaz; backend periyodik yoklamayla (polling) Postgres'ten
   toparlar. **Doğruluk kaynağı her zaman veritabanı, Redis sadece
   gecikmeyi azaltan bir kısayol.**

### 3.8 Backend → WebSocket

Backend'deki `SubtitleBroadcaster`, Redis'teki `altyazi:*` desenine abone
(`psubscribe`). Bu abonelik **süreç boyunca tek** ve **ayrı bir Redis
istemcisiyle** (`@RedisClientName("pubsub")`) açık tutuluyor — klip
kuyruğunun kullandığı komut trafiğiyle aynı havuzu paylaşırsa (bir kez
başımıza geldi) abonelik saniyelerce bloke olup sessizce kesiliyor.

Her kanal için izleyen WebSocket oturumlarının bir listesi tutuluyor
(`sessions: Map<UUID, Set<Session>>`). Redis'ten bir mesaj geldiğinde, o
kanalı izleyen tüm oturumlara anında iletiliyor:

```
ws://<host>/ws/altyazi/{channelId}
```

Bu uç **kimlik doğrulaması istemiyor** (herkese açık) — REST tarafındaki
geçmiş sorgusu (`GET /api/channels/{id}/altyazilar`) ise `@Authenticated`.

### 3.9 Tarayıcı — SubtitleOverlay

İzleyici, oynatıcı karosunun sağ üstündeki **"Altyazı" menüsünden bir dil
seçmeden** bu bileşen hiç yüklenmiyor (varsayılan: `kapalı`, kalıcı değil,
her yenilemede sıfırlanıyor). Seçildiğinde `SubtitleOverlay`:

1. **Geçmiş için** bir kez `GET /api/channels/{id}/altyazilar?from=&to=`
   çağırıyor (sayfa açıldığında ekranda hemen bir şey olsun diye).
2. **Canlı için** WebSocket'e bağlanıyor, gelen her bölütü yerel bir
   önbellekte tutuyor.
3. Her **250ms**'de bir, video oynatıcının o anki mutlak zamanını
   (`playingDate()`) önbellekteki bölütlerin `[baslangic, bitis)`
   aralığıyla karşılaştırıyor. Eşleşen varsa metni ekrana basıyor, yoksa
   ekran boş kalıyor.
4. Uzun metinler ≤2 satır × ≤38 karakter parçalara bölünüp
   (`parcala`), bu parçalar bölütün **kendi (kısa) süresi içinde**
   sıralı gösteriliyor (`parcaSec`).
5. `kesik` bayrağı **hiç okunmuyor** — zorla kesilmiş bir cümle özel bir
   işlem görmüyor.

---

## 4. .env değişkenleri — tam referans

### Donanım / kodlayıcı

| Değişken | Ne işe yarar |
|---|---|
| `CHANNELS_ENCODER` | Kanal rendition kodlayıcısı: `NVENC`\|`VAAPI`\|`YAZILIM` |
| `VIDEOS_ENCODER` | Yüklenen video işleme kodlayıcısı (aynı seçenekler) |
| `CONTAINER_RUNTIME` | Docker runtime: `nvidia` (GPU geçirmek için) ya da `runc` |
| `NVIDIA_VISIBLE_DEVICES` / `NVIDIA_DRIVER_CAPABILITIES` | Konteynere hangi GPU özelliklerinin açılacağı |
| `MEDIA_DEVICE` / `WORKER_MEDIA_DEVICE` | `/dev/dri` (VAAPI) geçişi; NVIDIA'da zararsız yer tutucu `/dev/null:/dev/null` |
| `CHANNELS_MAX_ACTIVE` | Aynı anda aktif olabilecek kanal sayısı üst sınırı (bu oturumda `docker-compose.yaml`'a kablolandı, önceden yalnızca `.env`'de durup hiçbir etkisi yoktu) |

### VAD (ses etkinliği tespiti)

| Değişken | Ne işe yarar |
|---|---|
| `VAD_ENABLED` | VAD hattının açık/kapalı olması |
| `VAD_MODEL_PATH` | Silero VAD ONNX modelinin imaj içi yolu |
| `VAD_MODEL_VERSION` | `v4`\|`v5` — modelin beklediği örnek/bağlam biçimi (§3.2) |
| `VAD_MAX_CHANNELS` | Aynı anda VAD çalıştırılacak kanal üst sınırı |
| `VAD_SEGMENT_DIR` | Bölütlerin (debug/arşiv amaçlı) diske yazılacağı yol |
| `VAD_MAX_SEGMENT_MS` | Bir bölütün zorla kesilmeden önce alabileceği azami süre (§3.2) |
| `VAD_MIN_SILENCE_MS` | Bölütü kapatan sessizlik süresi |
| `VAD_MIN_EMIT_MS` | Kısa bölütleri birleştirme penceresi — şu an `0`, etkisiz |
| `VAD_OVERLAP_MS` | Zorla kesimde bir sonraki bölütle bırakılan örtüşme |

### STT (çözümleme + çeviri)

| Değişken | Ne işe yarar |
|---|---|
| `STT_MODEL` | Whisper model boyutu: `tiny`…`large-v3` |
| `STT_DEVICE` | `cpu`\|`cuda` — Whisper VE (bu oturumdan sonra) Marian için |
| `STT_COMPUTE_TYPE` | `float16`\|`int8_float16`\|`int8` — sayısal hassasiyet |
| `STT_BEAM_SIZE` | Whisper arama genişliği |
| `STT_BATCH_SIZE` | Tek sesin kendi içinde yığınlanması |
| `STT_MAX_CONCURRENCY` | Aynı anda GPU'da çözümlenebilecek bölüt sayısı (Semaphore) |
| `STT_TARGET_LANGS` | Hedef diller (virgülle ayrık): `tr,de,ru` |
| `STT_URL` | video-worker'ın stt-worker'a POST ettiği adres |
| `VAD_STT_ENABLED` | Bölütlerin STT'ye gönderilip gönderilmeyeceği |
| `PORT_STT` | stt-worker'ın dinlediği port |
| `STT_RUNTIME` | stt-worker konteynerinin docker runtime'ı (`nvidia`) |

### Altyazı gecikme/kapsama ölçümü

| Değişken | Ne işe yarar |
|---|---|
| `ALTYAZI_HLS_GERIDE` | İzleyiciyi canlı kenardan kaç segment geriye alır — bütçeyi **büyütüp gecikmeyi saklıyor**, çözmüyor |
| `ALTYAZI_BUTCE_MS` | **Yanıltıcı isim** — hiçbir şeyi düşürmüyor, yalnızca `ALTYAZI KAPSAMA` log satırındaki yüzdeyi hesaplamak için kullanılıyor |
| `ALTYAZI_RAPOR_ARALIGI` | `SubtitleLagMetrics`'in ne sıklıkla rapor bastığı (varsayılan 60s) |

### Ağ / adresler

| Değişken | Ne işe yarar |
|---|---|
| `MEDIAMTX_RTSP_URL` | video-worker'ın ses çekmek için bağlandığı adres (`rtsp://mediamtx:8554`) |
| `MEDIAMTX_API_URL` | Backend'in MediaMTX'i yönettiği adres |
| `MEDIAMTX_HLS_BASE_URL` | Tarayıcının HLS'i çektiği taban adres |
| `PORT_HLS` / `PORT_RTSP` / `PORT_MEDIAMTX_API` | MediaMTX'in host'a açılan portları |

---

## 5. Kritik davranış — mutlak zaman damgası

`SubtitleOverlay.tsx`'teki eşleştirme **mutlak** zaman üzerinden çalışıyor,
canlı yayının o anki noktasına göre değil:

```ts
baslangic <= playingDate() < bitis
```

Bir bölütün üretimi ne kadar sürerse sürsün, `bitis` zamanı **sabit** kalır
(bölütün kendi ses aralığından geliyor). Üretim 90 saniye sürdüyse, bölüt
Postgres'e/Redis'e yazıldığında oynatma zamanı çoktan o pencereyi geçmiş
olur. Sonuç:

- Hiçbir hata çıkmaz.
- Loglar tamamen temiz görünür (Whisper başarılı, çeviri başarılı, DB
  yazımı başarılı).
- Ekran **sessizce boş kalır** — o bölüt sonsuza dek "görünmedi" sayılır.

**Bu yüzden "altyazı akmıyor" teşhisinde önce her zaman gecikmeye
bakılmalı** — kodda bariz bir hata aranırsa bulunamaz, çünkü hata yok;
zamanlama var.

---

## 6. Bugün ölçülen darboğazlar

Aynı GPU (RTX 4050 Laptop, 6 GB), aynı model (`large-v3`,
`int8_float16`), tek fark aktif kanal sayısı:

| Durum | Ort. gecikme | p95 | Kapsama | Kuyruk |
|---|---|---|---|---|
| 16 kanal aktif | ~90 sn | ~92 sn | %0 | sürekli dolu, ~%78 düşme |
| 1 kanal aktif | 1,5–2 sn | 3,0 sn | %100 | hiç dolmuyor |

Bütçe (`ALTYAZI_HLS_GERIDE` ile) 8 saniye. Tek kanalda GPU bazen **%0**
kullanımda bile ölçüldü — asıl sıkışma GPU'nun hızında değil, **16 kanalın
4 eşzamanlı işlem hakkı (`STT_MAX_CONCURRENCY`) için kuyrukta bekleşmesinde.**

Çeviri tarafında (bu oturumdan önce) ölçülen ayrı bir tavan: **4,39×**
gerçek zaman (üç dil, tek kilit, CPU) — Whisper'ı bedavaya indirseniz bile
bu sayı değişmiyordu, çünkü çeviri Whisper'dan **sonra**, ayrı bir seri
adımdı.

Kök sebepler, öncelik sırasıyla:

1. **İzleyici filtresi yok** — `VadService.java:134`, aktif her kanal
   çözümleniyor, kimse izlemese de. (`SubtitleBroadcaster.sessions` zaten
   izleyici sayısını biliyor; kullanılmıyor.)
2. **Kuyruk yanlış ucundan düşürüyor** — `offer()` yeniyi atıyor,
   `take()` eskiyi alıyor (§3.3).
3. **Yaş/bütçe kontrolü yok** — hiçbir segment, artık gösterilemeyecek
   kadar bayatladığı için atlanmıyor.
4. **Çeviri CPU'da + tek kilit** — bu oturumda GPU'ya taşındı, dil başına
   kilide ayrıldı (§7). **Henüz test edilmedi.**

---

## 7. Bu oturumda değişen — `stt-worker/app/translate.py`

```diff
- self._lock = threading.Lock()                    # tek, tüm diller ortak
+ self._locks: dict[str, threading.Lock] = {}       # dil başına bir tane

  # load() içinde, her dil için:
+ model.to(SETTINGS.device)                         # artık cuda'ya taşınıyor
+ self._locks[language] = threading.Lock()

  # _translate_one içinde:
- with self._lock, torch.no_grad():
+ with self._locks[language], torch.no_grad():
+     batch = batch.to(SETTINGS.device)
```

- Artık TR çevirisi DE'yi, farklı kanalların çevirisi birbirini
  bloklamıyor.
- Aynı dilin **ardışık** çağrıları hâlâ seri — aynı model nesnesine
  eşzamanlı `generate()` çağrısı güvenli sayılmadığı için bilerek
  bırakıldı.
- Tek bir segmentin kendi TR/DE/RU çevirisi hâlâ `translate()` içindeki
  `for` döngüsüyle **sıralı** çalışıyor (aynı thread'de) — üç dili aynı
  segment için paralel yapmak istenirse bu döngünün de thread'lere
  ayrılması gerekir; **bu değişikliğe dahil edilmedi.**
- **Durum: test edilmedi.** stt-worker imajı henüz yeniden kurulmadı;
  sözdizimi (`py_compile`) geçti ama gerçek GPU üzerinde ölçüm yapılmadı.

---

## 8. İlgili diğer belgeler

| Belge | İçerik |
|---|---|
| `docs/altyazi-acik-isler.md` | §6'daki iki büyük kazancın (izleyici filtresi, çeviri kilidi) detaylı planı |
| `docs/altyazi-gpu-olcum.md` | Ölçüm reçetesi — belirti → hangi değeri ölç |
| `docs/altyazi-devir-notu.md` | Önceki oturumun tam dökümü |
| `docs/olcekleme-plani.md` | Çok düğümlü hedef mimari (yüzlerce kanal) — bugünkü tek-düğüm sınırlarının nereden geldiğini açıklıyor |
