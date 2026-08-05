# Faz 3b Planı — Video Kütüphanesi

**Hedef:** Video yükleme, otomatik/elle thumbnail üretimi, video CRUD.

Faz 3a'daki (radyo) yaklaşımın aynısı: kritik varsayımlar önce ölçülüyor,
sonra kod yazılıyor. Aşağıda **doğrulandı** ve **doğrulanmadı** ayrı ayrı
işaretlendi.

---

## 1. Akış

```
tarayıcı                 backend                  MinIO            worker
   │  POST /api/videos      │                        │                │
   │───────────────────────>│  kayıt: YUKLENIYOR     │                │
   │<───────────────────────│  imzalı PUT adresi     │                │
   │                        │                        │                │
   │  PUT <imzalı adres>  ─────────────────────────> │  (dosya)       │
   │                        │                        │                │
   │  POST …/tamamlandi     │                        │                │
   │───────────────────────>│  statObject ile doğrula│                │
   │                        │  ISLENIYOR + kuyruğa   │                │
   │                        │                        │  <─── iş ──────│
   │                        │                        │  <─ range oku ─│
   │                        │  HAZIR + metadata      │ ─ thumbnail ─> │
```

Dosya **backend'den geçmiyor**. Bu, kliplerde zaten kurulmuş olan "video
backend'den geçmez" ilkesinin devamı: 5 GB'lık bir dosyayı backend üzerinden
akıtmak, canlı yayın API'siyle aynı süreci dakikalarca meşgul ederdi.

---

## 2. Doğrulama durumu

| Varsayım | Durum |
|---|---|
| MinIO tarayıcıdan doğrudan PUT'a izin veriyor (CORS) | **doğrulandı** |
| İmzalı adres üretimi dış adresle yapılmalı | **doğrulandı** (mevcut `PresignClient` deseni) |
| ffmpeg imzalı adresten range okuyup tüm dosyayı indirmeden kare alabiliyor | **doğrulanmadı** |
| MinIO tek PUT üst sınırı 5 GB | belgelenen S3 davranışı, ölçülmedi |

CORS ölçümü:

```
OPTIONS http://…:9000/<bucket>/<key>   Origin: http://localhost:3000
→ HTTP 204
  Access-Control-Allow-Methods: PUT
  Access-Control-Allow-Origin: http://localhost:3000
```

Yani MinIO tarafında ek yapılandırma gerekmiyor.

**Doğrulanmayan madde önemli:** ffmpeg tüm dosyayı indirmek zorunda kalırsa
thumbnail maliyeti dosya boyutuyla orantılı hale gelir (4 GB'lık videoda
dakikalar + iki katı ağ trafiği) ve worker tasarımı değişir. Uygulamaya
başlamadan önce MinIO'ya gerçek bir dosya konup ölçülmeli.

---

## 3. Tablo

### `videos`

Klip tablosuyla aynı iskelet: kayıt **aynı zamanda kuyruk**, durum makinesi
ve `FOR UPDATE SKIP LOCKED` ile işçi güvenliği.

| Sütun | Tip | Not |
|---|---|---|
| `id` | uuid pk | |
| `title` | varchar(200) not null | |
| `description` | text | |
| `object_key` | varchar(512) not null | **sunucu üretir**, istemciden alınmaz |
| `thumbnail_key` | varchar(512) | HAZIR olunca dolar |
| `original_filename` | varchar(255) | indirme adında kullanılır |
| `content_type` | varchar(100) | |
| `size_bytes` | bigint | MinIO'dan `statObject` ile doğrulanır |
| `duration_seconds` | int | ffprobe |
| `width` / `height` | int | ffprobe |
| `thumbnail_at_seconds` | int | elle seçilen kare anı; null = otomatik |
| `status` | varchar(16) | `YUKLENIYOR` \| `ISLENIYOR` \| `HAZIR` \| `HATA` |
| `error` | text | |
| `attempts` | int not null default 0 | |
| `uploaded_by` | uuid → users(id) | |
| `created_at` / `updated_at` / `completed_at` | timestamptz | |

İndeksler: `(uploaded_by, created_at desc)`, `(status)` kısmi indeks
`where status in ('YUKLENIYOR','ISLENIYOR')`, başlık aramasi için
`lower(title)`.

Yeni bucket: **`videolar`**. Thumbnail'lar aynı bucket'ta `kucukresim/`
öneki altında — ayrı bucket, ayrı yaşam döngüsü politikası gerektirmiyor.

---

## 4. Yükleme — zorluklar

### V1. "Yükleme bitti" bildirimi güvenilmez

Dosya backend'e uğramadığı için backend yüklemenin bittiğini ancak tarayıcı
haber verirse öğrenir. Kullanıcı sekmeyi kapatır, ağ kopar, tarayıcı çöker →
kayıt sonsuza kadar `YUKLENIYOR`'da kalır.

**Çözüm:** kliplerdeki süpürücü deseni. N dakikadan eski `YUKLENIYOR`
kayıtlar için MinIO'ya `statObject` sorulur:
- nesne varsa → `ISLENIYOR`'a alınıp kuyruğa atılır (bildirim kaybolmuş)
- nesne yoksa → `HATA`, "yükleme tamamlanmadı"

Yani tamamlanma bildirimi bir **hızlandırma**, doğruluk kaynağı değil.

### V2. 5 GB tek PUT sınırı

S3 tek PUT ile en fazla 5 GB kabul eder. Üstü multipart upload gerektiriyor:
parça başına ayrı imza, parça listesi takibi, `CompleteMultipartUpload`
çağrısı, yarım kalan yüklemelerin temizliği. Belirgin bir karmaşıklık sıçraması.

**Öneri:** ilk sürümde **5 GB üst sınır**, açık hata mesajıyla. Multipart
sonraya. Sınır sessizce kesmemeli — dosya seçilir seçilmez uyarmalı.

### V3. Yükleme ilerlemesi ve devam ettirilebilirlik

`fetch()` yükleme ilerlemesi vermiyor; ilerleme çubuğu için `XMLHttpRequest`
gerekiyor. 4 GB'lık bir dosyayı ilerleme göstergesi olmadan yüklemek
kullanıcıya "dondu" hissi verir.

Ayrıca tek PUT **devam ettirilemez**: %90'da kopan bir yükleme sıfırdan
başlar. Multipart'ın ikinci gerekçesi bu.

### V4. Content-Type imzaya dahil

İmzalı PUT adresi üretilirken content-type belirtilirse tarayıcı **birebir
aynı** başlığı göndermek zorunda; farklı gönderirse imza tutmaz ve MinIO 403
döner. Ya imzada content-type hiç belirtilmez ya da istemcinin göndereceği
değer sunucuya önceden bildirilir. İkincisi daha güvenli, ilki daha az kırılgan.

---

## 5. İşleme — zorluklar

### V5. moov atom / faststart

Kullanıcının yüklediği MP4'te `moov` atomu dosyanın **sonunda** olabilir.
O durumda tarayıcı oynatmaya başlamadan önce dosyanın tamamını indirmeye
çalışır — 4 GB'lık bir videoda bu, oynatmanın hiç başlamaması demek.

Worker `ffprobe` ile tespit edip gerekiyorsa `-c copy -movflags +faststart`
ile remux etmeli. Yeniden kodlama yok ama dosya bir kez daha baştan sona
okunup yazılıyor: 4 GB'da dakikalar ve geçici olarak iki katı depolama.

### V6. Format çeşitliliği

mkv, avi, mov tarayıcıda oynamaz. İki yol: kabul edilen formatları
mp4/webm ile kısıtlamak, ya da transcode etmek (GPU + ciddi süre).

**Öneri:** kısıtla, net hata ver, transcode'u Faz 4'e bırak. Kısıtlama
uzantıya değil **ffprobe çıktısına** bakmalı — uzantı yalan söyleyebilir.

### V7. Thumbnail için videoyu okumak

Worker tüm dosyayı indirmemeli. ffmpeg imzalı GET adresini girdi alıp `-ss`
ile yalnızca gereken byte aralığını çekebilmeli (HTTP range). Çalışırsa
4 GB'lık videodan kare almak birkaç yüz KB indirmek demek.

**Bu doğrulanmadı** (bkz. bölüm 2). Çalışmazsa seçenekler: worker'ın dosyayı
geçici diske indirmesi, ya da thumbnail'ı yükleme sırasında tarayıcıda
canvas ile üretmek.

### V8. Otomatik kare hangi andan alınmalı

Videonun ilk saniyeleri sıklıkla siyah ya da logo. **Öneri:** sürenin %10'u,
en az 3. saniye. Tamamen siyah kare tespiti (ortalama parlaklık eşiği) ile
bir sonraki adaya geçmek mümkün ama ilk sürüm için gereksiz.

---

## 6. Worker konteyneri

Karar: **aynı Quarkus jar'ı, ikinci bir imaj.** `Dockerfile.worker`,
`Dockerfile.jvm`'in üstüne `ffmpeg` + `ffprobe` ekler. Worker konteyneri bir
config bayrağıyla yalnızca video işçisini açar.

Neden ayrı bir servis/dil değil: DB, MinIO ve Redis istemcileri, entity'ler
ve durum makinesi zaten yazılı. İkinci bir dilde yeniden yazmak, iki ayrı
doğruluk kaynağı demek olurdu.

### Tuzak: scheduler iki yerde birden çalışır

`ClipConsumer` hem `@Scheduled` süpürücü hem `StartupEvent` ile bir Redis
tüketici döngüsü çalıştırıyor. Aynı jar worker'da da açıldığında bunların
**ikisi de** ikinci kez çalışır. `SKIP LOCKED` veri bozulmasını engeller ama
boşa iş ve iki kat log üretir.

Her zamanlanmış iş için "bu süreçte çalışsın mı" bayrağı gerekiyor:

| Bayrak | backend | worker |
|---|---|---|
| `clips.worker.enabled` | true | false |
| `videos.worker.enabled` | false | true |

Compose'a yeni servis; `devices: /dev/dri` **gerekmez** (thumbnail ve remux
GPU istemiyor), ama transcode Faz 4'te gelirse gerekecek.

---

## 7. Oynatma

`<video src>` içine imzalı GET adresi. Progressive MP4 — HLS paketleme
transcode gerektirdiği için kapsam dışı.

**Dikkat: imzalı adres süresi.** Klipler için TTL 15 dakika
(`clips.download-url-ttl-minutes`). 2 saatlik bir videoda bu yetmez: açık
bağlantı sürer ama kullanıcı 20. dakikada ileri sarmak isterse yeni bir range
isteği gider ve **403 alır** — video ortasında "bozuldu" gibi görünür.

Seçenekler: video için ayrı ve uzun bir TTL (örn. 6 saat), ya da oynatıcının
adresi süre dolmadan tazelemesi. İlki basit, ikincisi doğru. İlk sürüm için
uzun TTL yeterli; adres zaten tek nesneye ve salt okumaya kapsanmış durumda.

---

## 8. Güvenlik

**Nesne anahtarını istemci belirlememeli.** İmzalı PUT adresi, sahibine o
anahtara veri yazma yetkisi verir. Anahtar istemciden alınsaydı kullanıcı
başka bir videonun (ya da bir klibin) anahtarını gönderip üzerine yazabilirdi.
Anahtar sunucuda üretilir: `videolar/<uuid>/<uuid>.<uzanti>`.

**Yüklenen şeyin video olduğu varsayılmamalı.** İmzalı adrese herhangi bir
bayt dizisi yazılabilir. Worker `ffprobe` ile doğrulamalı; video akışı yoksa
`HATA` ve nesne silinir.

**TTL kısa tutulmalı** (yükleme için ~15 dk): adres sızarsa pencere dar olsun.

Bu, radyo modülündeki kabuk enjeksiyonu maddesiyle aynı aile: rol kontrolü
(Moderatör/Yönetici) girdinin doğrulanmasının yerini tutmaz.

---

## 9. İş kalemleri

### Backend

| # | Dosya | İş |
|---|---|---|
| 1 | `V9__videolar.sql` | tablo + indeksler |
| 2 | `video/entity/Video.java` | entity + kuyruk sorguları |
| 3 | `video/VideoStatus.java` | durum enum'u |
| 4 | `video/VideoStorage.java` | MinIO: presigned PUT/GET, stat, delete |
| 5 | `video/VideoService.java` | CRUD, yükleme başlat/tamamla, doğrulama |
| 6 | `video/VideoResource.java` | REST uçları |
| 7 | `video/dto/*` | `VideoDto`, `CreateVideoRequest`, `UpdateVideoRequest`, `UploadTicket` |
| 8 | `video/VideoWorker.java` | ffprobe + thumbnail + faststart |
| 9 | `video/VideoConsumer.java` | kuyruk + süpürücü (bayrakla gated) |
| 10 | `ClipConsumer` | `clips.worker.enabled` bayrağı |
| 11 | `Dockerfile.worker`, compose | ffmpeg'li ikinci imaj + servis |
| 12 | `application.properties` | bucket, TTL'ler, boyut sınırı, bayraklar |

### Frontend

| # | Dosya | İş |
|---|---|---|
| 13 | `api/types.ts`, `endpoints.ts` | `VideoDto`, `videosApi` |
| 14 | `pages/VideosPage.tsx` | kütüphane ızgarası, arama, durum |
| 15 | `pages/videos/VideoUploadDialog.tsx` | XHR ile ilerlemeli yükleme |
| 16 | `pages/videos/VideoEditDialog.tsx` | başlık/açıklama/thumbnail |
| 17 | `pages/videos/VideoPlayerDialog.tsx` | oynatma |
| 18 | `AppLayout`, `App.tsx` | nav + route |

---

## 10. Kapsam dışı (sonraya)

- Multipart upload (5 GB üstü, devam ettirilebilir yükleme)
- Transcode / kalite merdiveni (HLS paketleme)
- Altyazı, bölüm işaretleri, oynatma listeleri
- Kullanım/izlenme istatistikleri

---

## 11. Karara bağlanacaklar

1. **"Elle thumbnail" ne demek?** (a) kullanıcı zaman damgası seçer, worker o
   kareyi üretir; (b) kullanıcı kendi görselini yükler; (c) ikisi de. Farklı
   iş kalemleri.
2. **Format politikası:** yalnızca mp4/webm mi kabul edilsin, yoksa daha geniş
   bir küme alınıp reddetme worker'a mı bırakılsın?
3. **Üst sınır:** 5 GB kabul mü, yoksa multipart baştan mı yapılsın?
4. **ffmpeg range okuması ölçülsün mü** (bölüm 2), yoksa doğrudan
   "geçici diske indir" varsayımıyla mı başlanıyor?
