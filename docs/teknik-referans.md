# Yayın Merkezi — Teknik Referans

Bu belge, sistemin **her bileşenini ve her kararın gerekçesini** anlatıyor.
Amacı, teknik bir soruya tereddütsüz cevap verebilmek.

> **Derinleşmek için:** [teknik-referans-modul.md](teknik-referans-modul.md)
> — her paketin sınıf sınıf iç işleyişi, algoritmalar ve modellerin nasıl
> çalıştığı.

Sayıların çoğu **bu projede ölçüldü**. Ölçülmemiş olanlar açıkça işaretli —
bir sayının nereden geldiğini bilmek, sayının kendisi kadar önemli.

---

## İçindekiler

1. [Sistem bir bakışta](#1-sistem-bir-bakışta)
2. [Docker servisleri](#2-docker-servisleri)
3. [Teknoloji seçimleri ve gerekçeleri](#3-teknoloji-seçimleri-ve-gerekçeleri)
4. [Medya hattı](#4-medya-hattı)
5. [Java modülleri](#5-java-modülleri)
6. [Yapay zekâ modelleri](#6-yapay-zekâ-modelleri)
7. [Veri modeli](#7-veri-modeli)
8. [Asenkron iş hattı](#8-asenkron-iş-hattı)
9. [Güvenlik ve yetkilendirme](#9-güvenlik-ve-yetkilendirme)
10. [Kapasite ve ölçülen değerler](#10-kapasite-ve-ölçülen-değerler)
11. [Sık sorulacak sorular](#11-sık-sorulacak-sorular)

---

## 1. Sistem bir bakışta

Yayın Merkezi, **çok kaynaklı canlı yayınları tek bir arayüzde toplayan**,
geriye sarma, klip çıkarma, kayıt ve otomatik altyazı üreten bir platform.

```
Dış kaynaklar (HLS/RTSP/RTMP/SRT)
        │
        ▼
   ┌─────────┐   HLS    ┌──────────┐
   │ MediaMTX│─────────►│ Tarayıcı │
   │         │          └──────────┘
   │         │  RTSP          ▲
   │         │────┐           │ REST + imzalı adres
   └─────────┘    │           │
     │  │         ▼           │
     │  │   ┌──────────┐  ┌───────┐
     │  │   │video-work│  │backend│
 kayıt │   │  VAD     │  │Quarkus│
   ▼   │   └────┬─────┘  └───┬───┘
 diske │        │ HTTP       │
       │        ▼            ▼
       │   ┌──────────┐  ┌────────┐  ┌───────┐
       │   │stt-worker│  │Postgres│  │ MinIO │
       │   │ Whisper  │  └────────┘  └───────┘
       │   │ Opus-MT  │
       └──►└──────────┘
```

**Temel tasarım ilkesi:** medya işinin ağırlığı MediaMTX'te, uygulama katmanı
yalnızca **kontrol düzlemi**. Backend hiçbir zaman video baytı işlemiyor;
klip üretiminde bile MediaMTX'ten MinIO'ya **saf bayt aktarımı** yapıyor,
arada ffmpeg yok.

---

## 2. Docker servisleri

On servis. Her birinin **neden ayrı olduğu** aşağıda.

| Servis | İmaj | Port (host→kap) | Görev |
|---|---|---|---|
| `mediamtx` | `yayin/mediamtx:1.19.3` | 8554, 8888, 9997, 9996 | Medya sunucusu |
| `backend` | özel (Quarkus) | 8090→8081 | REST API, kontrol düzlemi |
| `frontend` | özel (nginx) | 3000→80 | Arayüz + `/api` vekili |
| `video-worker` | `yayin/video-worker` | — | ffmpeg işleri + VAD |
| `stt-worker` | `yayin/stt-worker` | 8100 | Konuşma tanıma + çeviri |
| `postgres` | `postgres:16` | 5433→5432 | Uygulama veritabanı |
| `keycloak` | `keycloak:25.0` | 8080 | Kimlik doğrulama |
| `keycloak-postgres` | `postgres:16` | — | Keycloak'un kendi veritabanı |
| `minio` | `minio/minio` | 9000, 9001 | Nesne depolama |
| `redis` | `redis:7-alpine` | 6379 | İş bildirimi |

### 2.1 Neden `video-worker` backend'den ayrı

**ffmpeg gerektiriyor.** Backend imajı `ubi9/openjdk-21-runtime` tabanlı ve
içinde ffmpeg yok — doğrulandı. Klip üretimi ffmpeg istemiyor (bayt
aktarımı), ama küçük resim, önizleme klibi ve VAD ses çıkarma istiyor.

Ayrıca **kaynak yalıtımı**: bir video kodlaması CPU'yu doyurduğunda REST
API'nin yanıt vermeyi sürdürmesi gerekiyor.

Aynı jar iki konteynerde çalışıyor; hangi işçinin nerede açılacağını bayraklar
belirliyor:

```
backend       CLIPS_WORKER_ENABLED=true   VIDEOS_WORKER_ENABLED=false
video-worker  CLIPS_WORKER_ENABLED=false  VIDEOS_WORKER_ENABLED=true
              VAD_ENABLED=true
```

Bayrak olmasaydı tüketici ve süpürücü **ikisinde birden** açılır, aynı iş iki
kez işlenirdi.

### 2.2 Neden `stt-worker` ayrı ve Python

Üç gerekçe:

**İmaj boyutu.** Ölçüldü: **9,84 GB** (`small` model + 3 çeviri modeli + torch
CPU). `large-v3` + CUDA ile daha da büyür. `video-worker`'ı bu boyuta
çıkarmanın anlamı yok.

**GPU yalnızca buraya gerekiyor.** `video-worker` GPU'suz makinelerde de
çalışmalı.

**Ekosistem.** `faster-whisper` (CTranslate2) ve Opus-MT'nin bakımlı JVM
bağlayıcısı yok. Python zorunlu.

**Arayüz HTTP.** Bölüt ortalama 440 KB; gRPC'nin karmaşıklığı bu boyutta
kendini ödemiyor. Ham PCM gövdede — base64 %33 şişirirdi.

### 2.3 Neden Keycloak'ın ayrı veritabanı var

Kimlik verisi (kullanıcılar, oturumlar, realm) ile iş verisi (kanallar,
klipler) **ayrı yaşam döngülerine** sahip. Uygulama veritabanını sıfırlamak
kimlikleri silmemeli; Keycloak'ı yükseltmek iş verisine dokunmamalı.

Aynı veritabanında olsalardı `--sifirla` her ikisini birden uçururdu.

### 2.4 Neden MediaMTX özel imaj

`yayin/mediamtx:1.19.3` — resmi imaj temel alınıp **ffmpeg ekleniyor**.
Rendition merdiveni MediaMTX'in `runOnReady` kancasıyla ffmpeg çağırıyor;
resmi imajda ffmpeg yok.

---

## 3. Teknoloji seçimleri ve gerekçeleri

### 3.1 Backend: Quarkus 3.37 / Java 21

| Neden | Ayrıntı |
|---|---|
| Açılış hızı | Ölçülen: **10-15 sn**. Konteyner yeniden başlatmaları sık |
| Bellek | `-XX:MaxRAMPercentage=80` ile sıkı sınır |
| Panache | Repository katmanı yazmadan aktif kayıt deseni |
| Yerleşik zamanlayıcı | `@Scheduled` — ayrı bir cron altyapısı gerekmiyor |
| OIDC | Keycloak entegrasyonu tek bağımlılık |

**Java 21:** sanal iş parçacıkları değil, `record` ve desen eşleme için.
Kodda `record` yoğun kullanılıyor (DTO'lar, iş nesneleri).

### 3.2 Kullanılan Quarkus eklentileri

```
quarkus-rest, quarkus-rest-jackson       REST uçları
quarkus-rest-client, -jackson            MediaMTX ve STT çağrıları
quarkus-hibernate-orm-panache            ORM
quarkus-jdbc-postgresql                  sürücü
quarkus-flyway                           şema göçü
quarkus-hibernate-validator              istek doğrulama
quarkus-keycloak-authorization           rol denetimi
quarkus-keycloak-admin-rest-client       kullanıcı yönetimi
quarkus-redis-client                     iş bildirimi
quarkus-scheduler                        süpürücüler, zamanlanmış kayıt
quarkus-smallrye-openapi                 /docs
quarkus-logging-json                     yapılandırılmış log
quarkus-websockets                       canlı altyazı akışı (/ws/altyazi/{id})
onnxruntime 1.20.0                       Silero-VAD
minio                                    S3 istemcisi
```

### 3.3 Frontend: React 19 + Vite + Tailwind v4

| Bağımlılık | Ne için |
|---|---|
| `hls.js` | HLS oynatma — tarayıcıların çoğu HLS'i yerel desteklemiyor |
| `react-router-dom` v7 | Yönlendirme |
| `@radix-ui/*` | Erişilebilir dialog, select, label |
| `tailwind-merge`, `cva` | Sınıf birleştirme |
| `sonner` | Bildirim |
| `lucide-react` | İkon |

**Neden hls.js:** Safari dışında hiçbir tarayıcı HLS'i yerel oynatmıyor.
hls.js MSE üzerinden besliyor ve bunun bir yan faydası var: **MSE ile beslenen
video canvas'ı "tainted" yapmıyor**, yani ekran görüntüsü tarayıcıda
alınabiliyor.

### 3.4 Neden `nginx` frontend'de

Statik dosya sunmanın yanında `/api` vekilliği yapıyor. Böylece tarayıcı tek
kaynağa konuşuyor ve CORS yalnızca geliştirme için gerekiyor.

---

## 4. Medya hattı

### 4.1 MediaMTX'in rolü

**Sistemdeki neredeyse tüm medya yükü burada.** Ölçülen (8 çekirdek, 16
eşzamanlı yayın):

| | Değer |
|---|---|
| CPU | **%13,3** (~0,13 çekirdek) |
| RAM | **352 MB** |
| HLS manifesti alınabilen | 16/16 |

Kanal başına maliyet çok düşük çünkü **yeniden kodlama yapılmıyor** — akış
yalnızca paketleniyor (stream copy).

### 4.2 Kanal nasıl yayına giriyor

```
1. Kullanıcı kanal ekler (kaynak adresi + mediamtx path)
2. SourceProbe kaynağı yoklar:
   - master playlist ise varyantları ayrıştırır
   - segment boyutunu tahmin eder (bandwidth/8 × targetDuration)
   - hls-max-segment-bytes altındaki EN YÜKSEK varyantı seçer
3. MediaMtxService path'i yazar (POST /v3/config/paths/add)
4. MediaMTX kaynağa bağlanır, HLS üretmeye başlar
5. Tarayıcı :8888/<path>/index.m3u8 adresinden çalar
```

**`SourceProbe` neden var:** gohlslib'in segment boyutu sınırı ~4 MB ve
**yapılandırılamıyor** (kodda sabit). Ölçüldü: sınır 3,01 MB ile 4,29 MB
arasında. `hlsSegmentMaxSize=500M` denendi, **işe yaramadı**. Çözüm, kaynağın
kendisinden daha küçük bir varyant seçmek.

### 4.3 Rendition merdiveni

İsteğe bağlı. Açıksa MediaMTX `runOnReady` ile ffmpeg başlatıp alt
çözünürlükler üretiyor ve her birini ayrı path'e yazıyor (`kanal1_720p` gibi).

**Maliyeti ölçüldü:** MediaMTX çekirdeği %25 iken 8 rendition **%276**'ya
çıkıyor. Bu yüzden yüzlerce kanal hedefi ancak merdivensiz gerçekçi.

Kodlayıcı seçilebilir: `NVENC` (NVIDIA), `VAAPI` (Intel/AMD), `YAZILIM`
(libx264). `VideoEncoder` enum'u üç durumu da tek yerde tutuyor.

### 4.4 DVR ve kayıt

MediaMTX path bazında diske yazıyor (`MTX_PATHDEFAULTS_RECORDDELETEAFTER`,
varsayılan 168 saat = 7 gün).

**Kayıt kaynak path'ine yazılıyor, rendition'a değil.** Önceden
`dvrRendition` ile bir rendition seçiliyordu; iki bedeli vardı:

- kaynak 1080p verse bile arşiv 720p/1500k kalıyordu,
- o rendition üretilmezse MediaMTX kaydı açıp klasörü oluşturuyor ama
  **içine hiçbir şey yazmıyordu** — kullanıcı dakikalarca kaydettiğini
  sanıp sonunda boş dönüyordu. Yaşandı, `V17` ile kaldırıldı.

### 4.5 DVR'ı kapalı kanallarda kayıt

`ChannelRecordingGate`: kayıt gerektiğinde MediaMTX'te kaydı **iş süresince**
açıyor, bitince geri kapatıyor.

Kapatma kararı tek soruya bakıyor: *bu kanalda kaydı kendisi açmış başka bir
iş kaldı mı?* Manuel ve planlı kayıt aynı kanalda çakışabildiği için, aksi
halde biri bitince diğerinin aralığı ortasından kesilirdi.

### 4.6 Klip aralığının kırpılması

Kullanıcının düğmeye bastığı an ile MediaMTX'in **gerçekten yazmaya başladığı**
an aynı değil: kaydı açmak path'i yeniden başlatıyor, kaynak yeniden
bağlanıyor. Ölçülen bölütler:

```
14:28:31 → 14:28:39   (9 sn)
14:28:45 → 14:29:21   (36 sn)
```

`clampToRecorded` istenen aralığı diskte gerçekten olanla kesiştiriyor.
Kırpmadan istenirse MediaMTX 404 döner ve kullanıcının elinde **hiçbir şey**
kalmaz.

---

## 5. Java modülleri

| Paket | Dosya | Sorumluluk |
|---|---|---|
| `channel` | 15 | Kanal CRUD, MediaMTX yönetimi, kaynak yoklama |
| `clip` | 27 | Klip, manuel kayıt, planlı kayıt, kuyruk, işçi |
| `video` | 15 | Kütüphane, imzalı yükleme, küçük resim, önizleme |
| `user` | 15 | Kullanıcı, rol, Keycloak senkronizasyonu |
| `radio` | 9 | Radyo yayınları, Icecast köprüsü |
| `VAD` | 8 | Ses etkinliği tespiti, bölütleme, STT'ye gönderim |
| `auth` | 7 | Oturum, token |
| `exception` | 6 | Hata haritalama |
| `dvr` | 5 | Zaman çizelgesi, geçmişten okuma |
| `screenshot` | 5 | Kare yakalama, galeri |
| `subtitle` | 4 | Altyazı yazma/okuma |
| `storage` | 3 | Kota, saklama süpürücüsü, yol düzeni |
| `media` | 1 | Kodlayıcı enum'u (NVENC/VAAPI/YAZILIM) |

### 5.1 `VAD` paketi ayrıntısı

| Sınıf | Görev |
|---|---|
| `VadConfig` | Sabitler — modelin dayattıkları ve ayarlanabilirler ayrı |
| `SileroVad` | ONNX oturumu, kanal başına ayrı durum |
| `AudioStream` | ffmpeg süreci, kare okuma, zaman çıpası |
| `SpeechSegmenter` | Histerezisli durum makinesi |
| `SpeechSegment` | Bölüt kaydı (mutlak damgalarla) |
| `ChannelVadWorker` | Kanal döngüsü, üstel geri çekilme |
| `VadService` | Yaşam döngüsü, STT kuyruğu, veritabanına yazma |
| `SttClient` | STT servisine HTTP |

---

## 6. Yapay zekâ modelleri

### 6.1 Silero-VAD v5 (ses etkinliği tespiti)

| | |
|---|---|
| Biçim | ONNX, **2,2 MB** |
| Çalıştırma | ONNX Runtime (Java) — PyTorch gerekmiyor |
| Hız | **199× gerçek zaman**, tek çekirdek (ölçüldü) |
| Girdi | 512 örnek + **64 örnek bağlam** = 576 |
| Durum | LSTM, `[2][1][128]` |

**Neden gerekli:** sessizlik ve müzikte STT çalıştırmak boşa yanan GPU.
Ölçülen konuşma oranı TRT Haber'de **%97** — yani o kanalda kazanç az; müzik
ağırlıklı kanallarda çok daha fazla olacak. Kanal türü başına ölçüm hâlâ
yapılmadı.

**v4 ile farkı** (ölçüldü):

| | v4 | v5 |
|---|---|---|
| Kare (16 kHz) | 512-2048 serbest | **yalnızca 512** |
| Bağlam | yok | **64 örnek zorunlu** |
| LSTM durumu | `h` + `c` ayrı | tek `state` |

**Sessiz tuzak:** v5'e bağlam verilmezse model hata vermez, her kareye
"sessizlik" der. Ölçüldü: bağlamsız **%0**, bağlamlı **%97** — ses RMS'i 0,11
ve tepe 0,95 iken. Bu davranış teste sabitlendi
(`baglamsizCagrimSessizceSifirVerir`).

### 6.2 Whisper (konuşma tanıma)

| | |
|---|---|
| Uygulama | `faster-whisper` 1.1.1 (CTranslate2) |
| Model | `small` (geliştirme) / `large-v3` (üretim hedefi) |
| Kip | **`task=translate`** — her dil → İngilizce |
| Dil desteği | 99 dil, kaynak sınırlanmıyor |
| VAD süzgeci | **kapalı** — Silero zaten yaptı |

**Neden `faster-whisper`:** CTranslate2 tabanlı, aynı kod CPU ve GPU'da
çalışıyor, nicemleme (int8) destekliyor. OpenAI'nin özgün uygulamasına göre
belirgin hızlı.

**Neden `task=translate`:** kaynak dil bilinmiyor ve Whisper'ın 99 dil desteği
pivotu tek geçişte veriyor. Alternatif (kaynak dilde transkripsiyon + metin
çevirisi) kaynak dil başına ayrı çeviri modeli isterdi ve çevrimdışı kısıt
altında kombinasyon patlardı.

**Bilinçli takas:** kaynak Türkçe ise Türkçe altyazı `TR → EN → TR` yolundan
geliyor ve özel isim/sayılarda kayıp veriyor. Ölçülen çıktıda görüldü.
Kabul edilemez bulunursa çözüm hazır: tespit sonucu saklandığı için o
kanallarda `task=transcribe` dalına geçmek yeterli.

### 6.3 Opus-MT (çeviri)

| Yön | Model |
|---|---|
| EN → TR | `Helsinki-NLP/opus-mt-tc-big-en-tr` |
| EN → DE | `Helsinki-NLP/opus-mt-en-de` |
| EN → RU | `Helsinki-NLP/opus-mt-en-ru` |

**Adlandırma tek biçimli değil.** Ölçüldü: `opus-mt-en-tr` → HTTP 401 (yok),
`opus-mt-tc-big-en-tr` → 200. Türkçe yalnızca Tatoeba Challenge varyantında
var. Bu yüzden kodda **eşleme** var, formül değil.

**Neden CPU'da:** metin çevirisi cümle başına birkaç on milisaniye. GPU'yu
Whisper'a bırakmak doğru takas — 20 kanal hedefinde GPU en kıt kaynak.

**Cümlelere bölme:** Marian modelleri cümle düzeyinde eğitildi; uzun paragraf
verilince sonu **sessizce kırpılıyor**. Cümlelere bölünüp tek yığında
çevriliyor.

### 6.4 Çevrimdışı çalışma

Ses ve türevi (metin) **dışarı çıkmıyor**. Bunun üç sonucu var:

1. Bulut STT ve çeviri tamamen elendi.
2. Modeller **imaja gömülü** — çalışma anında indirme kapalı ağda sessizce
   başarısız olur.
3. `HF_HUB_OFFLINE=1` ve `TRANSFORMERS_OFFLINE=1` — model eksikse indirmeye
   çalışmak yerine **açıkça patlasın**.

---

## 7. Veri modeli

19 migration. Tablolar:

| Tablo | İçerik |
|---|---|
| `users`, `roles` | Kullanıcı ve roller (Keycloak ile eşlenik) |
| `channels` | Kanal tanımları, MediaMTX path, rendition spec |
| `radios` | Radyo yayınları |
| `clips` | Klip işleri — durum, deneme, nesne anahtarı |
| `active_recordings` | Devam eden manuel kayıtlar |
| `planli_kayitlar` | Zamanlanmış kayıt emirleri |
| `videos` | Kütüphane videoları |
| `screenshots` | Yakalanan kareler |
| `altyazilar` | STT + çeviri çıktısı |

### 7.1 Neden `altyazilar` JSONB kullanıyor

```sql
metinler JSONB NOT NULL   -- {"en": "...", "tr": "...", "de": "...", "ru": "..."}
```

Dil başına ayrı satır yerine tek JSON: bir bölütün tüm dilleri **birlikte**
üretiliyor ve birlikte okunuyor. Ayrı satırlar her sorguda dört kat
birleştirme ve tutarsız kalma riski getirirdi.

**Quarkus tuzağı:** Hibernate'in JSON sütunları için REST'in `ObjectMapper`'ını
kullanması **reddediliyor** — REST için yapılan bir özelleştirme (alan gizleme,
null atlama) veritabanına yazılan veriyi sessizce bozabilir. Uygulama açılışta
patlıyor. Çözüm:

```properties
quarkus.hibernate-orm.mapping.format.global=ignore
```

### 7.2 Neden zaman damgaları mutlak

Canlı yayında izleyici **6-12 saniye geride**. Altyazı göreli süreyle
saklansaydı doğru kareye oturtmak imkânsız olurdu. Mutlak damga
(`PROGRAM-DATE-TIME` ile eşlenen) şunları bedava veriyor:

- geriye sarmada aynı sorgu çalışıyor,
- klip alındığında altyazı **aralık sorgusuyla** geliyor,
- video kütüphanesi için aynı tablo kullanılabilir.

### 7.3 Nesne depolama düzeni

```
<kullanıcı>/<kanal>/<id>.mp4        klip ve kayıt
<kullanıcı>/<kanal>/<id>.jpg        ekran görüntüsü
<kullanıcı>/<uuid>/kaynak.<uzantı>  kütüphane videosu
```

Kullanıcı en üstte çünkü içerik zaten kullanıcıya özel. Klasör adı okunabilir
olsun diye kullanıcı adından türetiliyor (`StoragePaths.slug`): `buğra →
bugra`, `Ayşe Öz → ayse-oz`.

---

## 8. Asenkron iş hattı

### 8.1 Doğruluk veritabanında, bildirim Redis'te

```
istek → clips satırı (BEKLIYOR) → Redis bildirimi
                                       ↓
                              işçi BLMOVE ile alır
                                       ↓
                    FOR UPDATE SKIP LOCKED ile talep eder
                                       ↓
                       MediaMTX → MinIO (bayt aktarımı)
                                       ↓
                              HAZIR + boyut
```

**Neden ikisi birden:** iş zaten tabloda kalıcı olmak zorunda. Kuyruğu tamamen
Redis'e taşımak iki doğruluk kaynağı demek olurdu; biri başarılı diğeri
başarısız olduğunda ya kaybolan ya iki kez işlenen işler çıkardı.

**Redis çökse hiçbir iş kaybolmaz** — gecikme süpürücünün aralığına düşer.

**`BLMOVE` neden:** iki liste (`bekleyen`, `isleniyor`) arasında **tek adımda**
taşıma. `BRPOP` kullanılsaydı işçi işi aldıktan hemen sonra çökerse iş hiçbir
listede olmaz ve iz bırakmadan kaybolurdu.

### 8.2 VAD → STT kuyruğu

Çözümleme uzun sürüyor: 25 saniyelik bölüt CPU'da `small` ile **6,5 saniye**
(ölçüldü). `onSegment` doğrudan STT'yi çağırsaydı kare döngüsü o süre boyunca
dururdu — ffmpeg borusu dolar, kareler kaybolur, akış bozulur.

Kuyruk **sınırlı (64) ve dolduğunda bölüt düşürülüyor**. Sınırsız olsaydı
bellek sessizce büyürdü; beklemek ise yakalamayı durdurmak demek — canlı
yayında geçmişi bekletemezsin. Düşen bölüt **uyarı olarak loglanıyor**.

### 8.3 Canlı altyazı akışı

```
video-worker ──PUBLISH──► Redis ──SUBSCRIBE──► backend ──WS──► tarayıcı
   (üretir)          altyazi:<channelId>      (sunar)
```

Üreten ve sunan **ayrı konteynerler** olduğu için doğrudan çağrı yapılamıyor;
Redis pub/sub araya giriyor. Sıra bilinçli: **önce veritabanı, sonra yayın** —
tersi olsaydı izleyici altyazıyı görür ama sayfayı yenileyince kaybolurdu.

Abonelik **kanal başına** ve ilk izleyiciyle açılıp son izleyiciyle kapanıyor:
tek kanala abone olup süzmek, 20 kanalın trafiğini tek izleyici için bu sürece
çekerdi.

Ayrıntı: [teknik-referans-modul.md §9.4-9.11](teknik-referans-modul.md).

### 8.4 Eşzamanlılık sınırları

| Ayar | Varsayılan | Gerekçe |
|---|---|---|
| `clips.concurrency` | 2 | Sınırsızsa disk ve ağ doyar, **canlı yayın etkilenir** |
| `videos.concurrency` | 2 | Aynı |
| `channels.max-active` | 16 | MediaMTX sınırı uygulamıyor; aşıldığında tüm kanallarda bozulma |
| `vad.max-channels` | 20 | Kaynak tükenince canlı yayın etkilenmemeli |
| `STT_MAX_CONCURRENCY` | 2 | Sınırsızsa VRAM doyar |

**Ortak gerekçe:** hiçbir arka plan işi canlı yayının önüne geçmemeli.

---

## 9. Güvenlik ve yetkilendirme

### 9.1 Keycloak

- OIDC + doğrudan hibe (direct grant)
- Client rolleri (realm rolü değil): `Yönetici`, `Moderatör`, `İzleyici`
- Realm `realm-export.json`'dan **yalnızca ilk açılışta** içe aktarılıyor

### 9.2 Rol matrisi

| İşlem | Yönetici | Moderatör | İzleyici |
|---|---|---|---|
| Kanal/radyo yönetimi | ✓ | ✓ | — |
| Kullanıcı yönetimi | ✓ | — | — |
| Video yükleme | ✓ | ✓ | — |
| Video görüntüleme | ✓ | ✓ | ✓ |
| Klip/kayıt/ekran görüntüsü alma | ✓ | ✓ | ✓ |
| Başkasının klibini görme | ✓ | — | — |

**İki farklı görünürlük modeli var, karıştırmak kolay:**

| İçerik | Kim görür |
|---|---|
| Klip, kayıt, ekran görüntüsü | **yalnızca sahibi** (+ yönetici) |
| Video kütüphanesi | **herkes** |

Klip ve ekran görüntüsü kişisel kayıt içeriği — varsayılan kapalı olmalı.
Kütüphane ise paylaşılan bir arşiv.

### 9.3 İmzalı adresler

MinIO nesnelerine doğrudan erişim yok; backend **presigned URL** üretiyor.

- Yükleme: `PUT`, 15 dakika
- İzleme/indirme: `GET`, 6 saat

**S3 v4 imzası Host başlığını kapsıyor** — bu yüzden `MINIO_PUBLIC_URL`
sonradan değiştirilemez, imzalar geçersiz olur.

### 9.4 Bilinen eksik

**İzleyici kimlik doğrulaması yok.** HLS adresleri (`:8888`) kimlik
istemiyor; adresi bilen izleyebilir. Uygulama katmanı korunuyor, medya katmanı
korunmuyor.

---

## 10. Kapasite ve ölçülen değerler

### 10.1 Medya

| | Değer | Nasıl |
|---|---|---|
| MediaMTX, 16 kanal | %13,3 CPU · 352 MB | ölçüldü |
| Rendition merdiveni, 8 çıktı | %276 CPU | ölçüldü |
| Segment boyutu sınırı | 3,01-4,29 MB arası | ikili arama |

### 10.2 VAD hattı

| | Değer |
|---|---|
| PCM çıkarma, kanal başına | **%0,8 CPU · 49 MB** |
| `-allowed_media_types audio` kazancı | %1,5 → %0,8 |
| 8 paralel çıkarma | %8,0 toplam — doğrusal |
| Silero-VAD | **199× gerçek zaman**, tek çekirdek |
| 20 kanal, VAD + çıkarma | ~%20 CPU · ~1 GB |
| Konuşma oranı (TRT Haber) | **%97** |

### 10.3 STT

| | Değer |
|---|---|
| `small`, CPU, int8, tek istek | **3,86× gerçek zaman** |
| `small`, CPU, 2 eşzamanlı + çeviri | **1,81-2,3×** |
| 25 sn bölüt | 6,5 sn işlem |
| İmaj boyutu | **9,84 GB** |

**20 kanal için 20× gerekiyor.** Ölçüm GPU zorunluluğunu doğruluyor:
`large-v3` CPU'da ~0,3-0,5× (literatür), yani tek kanalı bile taşımaz.

### 10.4 Depolama

| | Hesap |
|---|---|
| DVR, 16 kanal × 7 gün × 6 Mbps | ~7,3 TB |
| Altyazı, kanal başına | ~3,7 MB/gün |
| Altyazı, 20 kanal | ~73 MB/gün · ~27 GB/yıl |
| VAD bölütleri (geçici) | ~140 MB/saat/kanal |

---

## 11. Sık sorulacak sorular

**Neden MediaMTX, neden nginx-rtmp veya SRS değil?**
Tek ikilik, sıfır bağımlılık, HLS/RTSP/RTMP/SRT/WebRTC hepsi yerleşik, REST
API ile **çalışma anında path yönetimi**. Kanal ekleme yeniden başlatma
gerektirmiyor — asıl belirleyici bu.

**Neden klip üretiminde ffmpeg yok?**
MediaMTX'in playback sunucusu istenen aralığı zaten MP4 olarak veriyor.
Backend yalnızca baytları MinIO'ya aktarıyor — akış halinde, belleğe almadan.
2 saatlik klip 6 Mbps'te ~5,4 GB eder; tamponlamak sunucuyu düşürürdü.

**Neden altyazı veritabanında, doğrudan akıtılmıyor?**
Arşiv gereksinimi: geriye sarmada, kliplerde ve düzeltme arayüzünde altyazı
isteniyor. Ayrıca boyut önemsiz — 20 kanal için yılda ~27 GB, DVR'ın 7,3 TB'ı
yanında ihmal edilebilir.

**Neden VAD Java'da, STT Python'da?**
Silero ONNX'in resmî JVM bağlayıcısı var; `faster-whisper`'ın yok. VAD'ı
Java'da tutmak orkestrasyonu (kuyruk, veritabanı, hata yönetimi) mevcut
desenlerde bırakıyor. Python yalnızca GPU'ya dokunan kısımda.

**Neden 16 kHz mono?**
Silero yalnızca 16/8 kHz'de çalışıyor, Whisper girdiyi 16 kHz'e örnekliyor.
İnsan sesinin bilgi taşıyan bandı ~300 Hz–8 kHz; 16 kHz tam kapsıyor.
44,1 kHz stereo → 16 kHz mono dönüşümü veriyi **32 KB/sn**'ye indiriyor.

**Altyazı neden erken/geç görünmez?**
Eşleştirme `PROGRAM-DATE-TIME` üzerinden. Oynatıcının `playingDate()`
değeri karenin **yayındaki gerçek anını** veriyor; altyazı o ana göre
seçiliyor. "Şimdi geldi, şimdi göster" 6-12 saniye erken gösterirdi.

**Sistem kaç kanalı taşır?**
Yayın dağıtımı için 16+ (ölçüldü, %13 CPU). Rendition merdiveniyle ~2-3.
Altyazıyla CPU'da ~2, GPU'da kart sınıfına bağlı — `large-v3` için 20 kanal
~20× gerçek zaman kapasitesi istiyor.

**Veri kaybı riski nerede?**
`./baslat.sh --sifirla` geri alınamaz (`docker compose down -v`). Compose
proje adı `name: yayin-merkezi` ile **açıkça** yazılı — dosya taşındığında ad
değişip boş volume açılması bir kez yaşandı.

**Neden bazı ayarlar dört yerde birden?**
`application.properties` (varsayılan) → `docker-compose.yaml` (konteynere
geçiş) → `.env` (mevcut kurulum) → `yapilandir.sh` (yeni kurulum). Compose
geçişi atlanırsa ayar **ayarlanabilir görünüp hiçbir şey yapmaz** — yaşandı.
