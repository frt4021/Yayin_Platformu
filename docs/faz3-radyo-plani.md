# Faz 3a Planı — Radyo Modülü

**Hedef:** Radyo yayınları kanallarla aynı altyapıdan dağıtılsın; sayfa
geçişlerinde susmayan kalıcı bir mini oynatıcıdan dinlensin.

Aşağıdaki teknik kararlar, çalışan MediaMTX 1.19.3 üzerinde **canlı olarak
test edilerek** alındı. Ölçülen değerler açıkça işaretlendi.

---

## 1. Kaynak biçimleri — MediaMTX neyi kabul ediyor

MediaMTX'in `source` alanına yazılabilecek şemalar, path tanımı yazılıp
silinerek tek tek denendi:

| Kaynak biçimi | Sonuç | Radyoda karşılığı |
|---|---|---|
| `http(s)://.../*.m3u8` | kabul | HLS veren sağlayıcılar |
| `http(s)://...` (uzantısız) | kabul — **ama HLS sanılıyor** | Icecast/Shoutcast tuzağı |
| `rtsp://` | kabul | stüdyo çıkışı |
| `rtmp://` | kabul | stüdyo çıkışı |
| `srt://` | kabul | profesyonel aktarım |
| `udp://` | kabul | yerel multicast |
| `whep://` | kabul | WebRTC |
| `publisher` | kabul (sourceOnDemand ile birlikte değil) | ffmpeg köprüsünün hedefi |
| `redirect` | ayrı alan ister | radyoda kullanılmıyor |
| `icecast://` | **reddedildi** | böyle bir şema yok |

### Kritik bulgu: düz MP3 sessizce başarısız oluyor

Radyoların çoğu Icecast/Shoutcast üzerinden düz MP3 verir
(`http://yayin.ornek.com:8000/canli`). MediaMTX bu adresi **path yazılırken
kabul ediyor** — yani kullanıcı hata almıyor. Ama `http(s)` kaynaklarını
HLS sayıyor:

```
POST /v3/config/paths/add/test_mp3  {"source":"http://127.0.0.1:8099/radyo"}
→ HTTP 200

GET /v3/paths/get/test_mp3
→ {"ready":false, "bytesReceived":0, "source":{"type":"hlsSource"}}

mediamtx log: INF [path test_mp3] [HLS source] started
```

Sonuç: radyo kaydedilir, hata görünmez, **yayın hiçbir zaman başlamaz.**
Doğrulama açısından en kötü senaryo bu — bu yüzden kaynak türü kullanıcıdan
açıkça alınacak, adresten tahmin edilmeyecek.

### Çözüm: ffmpeg köprüsü (test edildi, çalışıyor)

```
POST /v3/config/paths/add/test_kopru
{"source":"publisher",
 "runOnInit":"ffmpeg -i http://127.0.0.1:8098/radyo -c:a aac -b:a 128k
              -f rtsp rtsp://127.0.0.1:$RTSP_PORT/$MTX_PATH",
 "runOnInitRestart":true}

GET /v3/paths/get/test_kopru
→ {"ready":true, "source":{"type":"rtspSession"},
   "bytesReceived":120365, "tracks":["MPEG-4 Audio"]}

GET :8888/test_kopru/index.m3u8
→ #EXT-X-STREAM-INF:BANDWIDTH=131524,CODECS="mp4a.40.2"
```

Kanallardaki transcode deseninin aynısı; fark, kancanın `runOnAvailable`
değil `runOnInit` olması (beklenecek bir kaynak yok, köprünün kendisi
kaynağı üretiyor).

**Ölçülen maliyet:** radyo başına **%2.6 CPU** (MP3 → AAC, 128k, tek çekirdek
yüzdesi). Video transcode'un (%14) beşte biri. 20 radyo ≈ 0,5 çekirdek.

---

## 2. Kaynak türü modeli

İki mod, kullanıcı tarafından açıkça seçilir:

| Tür | MediaMTX yapılandırması | Ne zaman |
|---|---|---|
| `DOGRUDAN` | `source: <adres>`, `sourceOnDemand: false` | HLS, RTSP, RTMP, SRT, UDP, WHEP |
| `KOPRU` | `source: publisher` + `runOnInit: ffmpeg …` | Icecast/Shoutcast düz MP3/AAC |

Adresten otomatik tahmin **bilinçli olarak yapılmıyor**: Icecast adreslerinin
çoğunda uzantı yok (`/canli`, `/stream/1`) ve HLS adreslerinin hepsi `.m3u8`
ile bitmiyor. Yanlış tahmin, yukarıdaki "sessizce çalışmama" durumunu
üretirdi.

---

## 3. Tablo

### `radios`

| Sütun | Tip | Not |
|---|---|---|
| `id` | uuid pk | `gen_random_uuid()` |
| `name` | varchar(128) not null unique | görünen ad |
| `source_url` | varchar(512) not null | kaynak adresi |
| `source_kind` | varchar(16) not null | `DOGRUDAN` \| `KOPRU` |
| `mediamtx_path` | varchar(128) not null unique | HLS adresi bundan türer |
| `bitrate` | varchar(16) not null default `128k` | yalnızca `KOPRU` için |
| `active` | boolean not null default true | pasifse MediaMTX'te path yok |
| `logo_url` | varchar(512) | listede gösterilecek görsel |
| `sort_order` | int not null default 0 | listede sıralama |
| `created_by` | uuid not null → users(id) | |
| `created_at` | timestamptz not null default now() | |

`channels`'a `type` sütunu eklemek yerine ayrı tablo: `renditions`,
`dvr_enabled`, `dvr_rendition` alanlarının radyoda hiçbir karşılığı yok ve
her sorgu "bu satır radyo mu" kontrolü taşımak zorunda kalırdı.
`MediaMtxService` paylaşılıyor, tablo paylaşılmıyor.

**Bit hızı neden kanal bazında:** kanallardaki rendition dersiyle aynı —
64k'lık bir Icecast yayınını 128k'ya kodlamak kaliteyi artırmaz, yalnızca
bant genişliği harcar.

---

## 4. Güvenlik: kabuk enjeksiyonu

`source_url` **kabukta çalışan bir komuta gömülüyor** (`runOnInit`).
Kanallarda böyle bir açık yok, çünkü orada adres MediaMTX'in `source`
alanına yazılıyor ve kabuğa hiç uğramıyor. Burada iki katmanlı savunma var:

1. Adres komutta **tek tırnak içine** alınıyor — `&`, `?`, `=` gibi kabuk
   anlamı olan karakterler zararsızlaşıyor.
2. Tek tırnak içeren veya izinli karakter kümesi dışına çıkan adresler
   reddediliyor; şema da beyaz listeden geçiyor.

Yalnızca yönetici ve moderatör radyo ekleyebiliyor olması bu doğrulamanın
yerini tutmaz: medya sunucusu konteynerinde komut çalıştırabilmek, rol
sahibine verilen yetkinin çok ötesinde.

---

## 5. Kapasite

Radyolar `channels.max-active` sayacına **dahil edilmiyor**. O sınır video
kodlama ve bant genişliği bütçesinden geliyor; radyonun maliyeti (%2.6 CPU,
128 kbps) aynı ölçekte değil. Ayrı ayar: `radios.max-active`, varsayılan 32.

---

## 6. Arayüz

### Kalıcı oynatıcı

`PersistentPlayers` deseninin birebir tekrarı: `AppLayout` içinde
`<Outlet/>`'in **dışında**, sayfa değişse de unmount olmayan bir bileşen.

### Tek ses kuralı

Canlı yayın karolarının sesi `PlayerContext.audioId` ile yönetiliyor
("onlarca yayın aynı anda seslenirse hiçbiri duyulmaz"). Radyo ayrı bir
provider'a konsaydı iki ses üst üste çalardı. Bu yüzden radyo durumu **aynı
context'e** ekleniyor ve tek bir ses sahibi kuralı işletiliyor: radyo
çalarken bir kanala ses verilirse radyo susar, tersi de geçerli.

### Otomatik oynatma

Tarayıcılar kullanıcı etkileşimi olmadan sesli oynatmaya izin vermiyor.
Sayfa yenilendiğinde radyo durumu geri gelse bile kendiliğinden çalamaz —
duraklatılmış gelir, kullanıcı başlatır.

### Gecikme

HLS segment tabanlı: radyoya basınca ses ~5-10 saniye sonra gelir. Mutlak
gecikme radyoda önemsiz ama **zap süresi** hissediliyor. Doğrudan MP3'ü
`<audio>`'ya vermek bunu sıfırlardı; MediaMTX üzerinden gitme kararının
bilinçli bedeli bu. Gerekirse LL-HLS ile azaltılabilir.

---

## 7. İş kalemleri

### Backend

| # | Dosya | İş |
|---|---|---|
| 1 | `V8__radyolar.sql` | tablo + indeksler |
| 2 | `radio/entity/Radio.java` | entity |
| 3 | `radio/RadioSourceKind.java` | `DOGRUDAN` \| `KOPRU` |
| 4 | `radio/AudioBridgeCommand.java` | ffmpeg komutu + adres doğrulama |
| 5 | `radio/RadioService.java` | CRUD, kapasite, MediaMTX yansıtma |
| 6 | `radio/RadioResource.java` | REST uçları |
| 7 | `radio/RadioRestorer.java` | açılışta geri yükleme |
| 8 | `radio/dto/*` | `RadioDto`, `CreateRadioRequest`, `UpdateRadioRequest` |
| 9 | `MediaMtxPathConfig` | `runOnInit` / `runOnInitRestart` alanları |
| 10 | `MediaMtxService` | `applyAudioPath`, `removePath(path)` |
| 11 | `application.properties` | `radios.max-active`, `radios.default-bitrate` |

### Frontend

| # | Dosya | İş |
|---|---|---|
| 12 | `api/types.ts`, `api/endpoints.ts` | `RadioDto`, `radiosApi` |
| 13 | `pages/RadiosPage.tsx` | liste + yönetim |
| 14 | `pages/radios/RadioFormDialog.tsx` | ekleme/düzenleme |
| 15 | `player/PlayerContext.tsx` | radyo durumu + tek ses kuralı |
| 16 | `player/PersistentRadio.tsx` | kalıcı mini oynatıcı |
| 17 | `components/AppLayout.tsx`, `App.tsx` | nav + route |

---

## 8. Kapsam dışı (sonraya)

- **AAC passthrough.** Kaynak zaten AAC ise `-c:a copy` ile kodlama
  atlanabilir; tespit `ffprobe` gerektiriyor. Şimdilik hep AAC'ye kodlanıyor
  (%2.6 CPU).
- **Radyo kaydı / geriye sarma.** MediaMTX üzerinden gittiğimiz için
  altyapısı hazır, ama Faz 3 hedefinde yok.
- **LL-HLS ile düşük gecikme.**
