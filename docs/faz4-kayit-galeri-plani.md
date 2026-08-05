# Faz 4 Planı — Manuel Kayıt, Depolama Kotası ve Ekran Görüntüsü Galerisi

**Hedef:** Kullanıcıya özel kayıt listesi ve kronolojik ekran görüntüsü galerisi.

Önceki planlarda olduğu gibi: kritik varsayımlar önce ölçüldü, kararlar
gerekçeleriyle yazıldı, doğrulanmamış olanlar açıkça işaretlendi.

---

## 1. Önce bir kısıt: DVR MinIO'ya yazılamaz

İstek "klipleri ve DVR kaydını da MinIO'da tutalım, kendi volümüne bir şey
kaydetmeyelim" idi. Durumu netleştirmek gerekiyor.

### Klipler zaten MinIO'da

`ClipWorker` üretilen MP4'ü `klipler` kovasına yazıyor, diske hiç düşmüyor.
Bu tarafta yapılacak bir şey yok.

### DVR yazılamaz — MediaMTX'te S3 desteği yok

Çalışan yapılandırma sorgulandı: `recordPath` bir **dosya sistemi yolu** ve
`s3`, `bucket`, `object` içeren tek bir ayar yok.

```
recordPath: /recordings/%path/%Y-%m-%d_%H-%M-%S-%f
s3/minio/bucket iceren ayar: YOK
```

Dahası, geriye sarma ve klip üretimi MediaMTX'in **playback sunucusuna**
dayanıyor ve o sunucu da aynı yerel dosyaları okuyor. Yani kayıt yerini
değiştirmek, Faz 2'de kurulan zaman çizelgesi ve klip hattını da götürüyor.

### Üç seçenek

| | Yaklaşım | Bedeli |
|---|---|---|
| **A** | Yerel disk **tampon** olarak kalır; kullanıcının sakladığı her şey MinIO'da | Yerel disk hâlâ gerekli ama yalnızca dönen pencere kadar |
| **B** | Kendi kayıt hattımız: kanal başına ffmpeg segment yazıp MinIO'ya yükler | MediaMTX playback devre dışı — zaman çizelgesi, geriye sarma ve klip üretimi **sıfırdan yazılır**; kanal başına bir ffmpeg süreci daha |
| **C** | MinIO'yu dosya sistemi gibi bağlamak (s3fs/rclone) | Sürekli yazan fMP4 için S3-as-filesystem kırılgan: gecikme, yarım segment, bozulma riski. Önerilmez. |

**Önerim A.** Gerekçe: DVR bir **tampon**, kullanıcı verisi değil. Kullanıcının
"benim" dediği her şey (klip, manuel kayıt, ekran görüntüsü) zaten MinIO'ya
gidiyor. Yerelde kalan, 7 günlük dönen pencere — yani bir önbellek.

A ile isteğin özü karşılanıyor: **kalıcı hiçbir kullanıcı verisi konteyner
volümünde durmuyor.** Tampon süresi kısaltılarak disk baskısı da düşürülebilir
(7 gün → 48 saat, `recordDeleteAfter`).

B gerçekten isteniyorsa ayrı bir faz olarak planlanmalı; Faz 2'nin yeniden
yazımı demek.

### Yan bulgu: HLS segmentleri de diske yazılıyor

`mediamtx.yml`'de `hlsDirectory: /hls` var. MediaMTX varsayılan olarak
segmentleri **yalnızca bellekte** tutuyor; bu ayar her segmenti diske
yazdırıyor. Şu an 48 MB ama asıl maliyet sürekli yazma.

Bu satır kaldırılırsa disk trafiği düşer ve "volüme yazma" isteğine bir adım
daha yaklaşılır. Konulma sebebi belgelenmemiş — hata ayıklama için konulduysa
kaldırılabilir. **Karar gerekiyor.**

---

## 2. Manuel kayıt — mevcut hattı yeniden kullanarak

**Kullanıcı kanalı kayda alır, sonra durdurur; sonuç indirilebilir bir dosya.**

Yeni bir kayıt mekanizması **gerekmiyor**. DVR zaten sürekli kaydediyor;
"manuel kayıt" aslında bir zaman aralığı seçimi:

```
[Kayda başla]  → başlangıç anı saklanır (istemcide değil, sunucuda)
[Durdur]       → o an bitiş; [başlangıç, bitiş] için klip işi açılır
                 → mevcut ClipWorker MediaMTX'ten çeker, MinIO'ya yazar
```

Bu sayede klip hattının tamamı (kuyruk, yeniden deneme, süpürücü, imzalı
indirme) bedava geliyor.

### Şema

`clips` tablosuna bir sütun yetiyor — yeni tablo gereksiz:

```sql
alter table clips add column origin varchar(16) not null default 'ARALIK';
-- ARALIK        : zaman çizelgesinden aralık seçilerek
-- MANUEL_KAYIT  : kayda başla / durdur ile
```

Devam eden kaydı tutmak için ayrıca:

```sql
create table active_recordings (
    channel_id uuid not null references channels(id),
    user_id    uuid not null references users(id),
    started_at timestamptz not null default now(),
    primary key (channel_id, user_id)
);
```

Neden tablo: sunucu yeniden başlarsa devam eden kayıt kaybolmamalı. Bellekte
tutulsaydı, kullanıcı "durdur"a bastığında başlangıç anı yok olurdu.

### Kısıtlar ve karar noktaları

- **DVR açık olmalı.** Kapalıysa geçmiş yok, kayıt üretilemez. Arayüzde kayıt
  düğmesi kapalı gösterilmeli, sebebiyle.
- **Üst sınır.** `clips.max-duration-minutes` (120 dk) manuel kayda da
  uygulanmalı, yoksa unutulan bir kayıt 7 günlük pencereyi tek dosya yapmaya
  çalışır. Sınıra gelince otomatik durdurulsun mu, yoksa reddedilsin mi —
  **karar gerekiyor.**
- **Unutulan kayıtlar.** Kullanıcı sekmeyi kapatırsa kayıt açık kalır.
  Süpürücü, üst sınırı aşan `active_recordings` satırlarını otomatik
  kapatmalı.
- **Aynı kanalda birden fazla kullanıcı** ayrı ayrı kayıt alabilir; anahtar
  (kanal, kullanıcı) çifti.

---

## 3. Ekran görüntüsü galerisi

**Kullanıcı canlı yayından kare yakalar; galeri kronolojik, silme ve indirme var.**

### Kare nerede yakalanır — iki seçenek

| | Tarayıcıda (canvas) | Sunucuda (ffmpeg) |
|---|---|---|
| Kalite | kullanıcının izlediği rendition — 240p izliyorsa 240p | her zaman kaynak çözünürlüğü |
| Gecikme | anında | ~1-2 sn |
| Sunucu maliyeti | yok | kare başına bir ffmpeg |
| Doğruluk | **tam olarak görülen kare** | yakalama anındaki canlı kare (birkaç saniye kayabilir) |
| Bağımlılık | yok | worker'a erişim |

**Önerim: sunucuda.** Gerekçe: galeri kalıcı bir arşiv ve 240p bir karenin
sonradan değeri düşük. Ama "gördüğüm kareyi istiyorum" da meşru bir
beklenti — HLS'te izlenen an ile canlı uç arasında 6-20 saniye fark var,
sunucudan yakalanan kare kullanıcının gördüğü kare **olmayabilir**.

Bu, **karara bağlanması gereken** bir denge. Üçüncü yol: tarayıcı yakalama
anındaki oynatma zamanını da göndersin, sunucu o anı DVR'dan çeksin — hem
kaynak çözünürlüğü hem doğru kare. DVR açık kanallarda çalışır, kapalıysa
canlı uca düşülür.

### Şema

```sql
create table screenshots (
    id           uuid primary key default gen_random_uuid(),
    channel_id   uuid not null references channels(id),
    captured_by  uuid not null references users(id),
    -- Yayin zamani: karenin hangi ana ait oldugu. created_at ise kaydin
    -- olusturuldugu an -- geriye sarmadan yakalananlarda ikisi farkli.
    captured_at  timestamptz not null,
    object_key   varchar(512) not null unique,
    width        int,
    height       int,
    size_bytes   bigint not null,
    note         varchar(200),
    created_at   timestamptz not null default now()
);
create index idx_screenshots_kullanici on screenshots (captured_by, captured_at desc);
create index idx_screenshots_kanal on screenshots (channel_id, captured_at desc);
```

Yeni kova: `ekran-goruntuleri`. Anahtar sunucu üretir (video kütüphanesindeki
kural).

**Erişim:** klipler gibi sahibine özel mi, kütüphane gibi herkese açık mı —
**karar gerekiyor.** Klipler kayıt içeriği barındırdığı için kapalıydı;
ekran görüntüleri de aynı içerikten geliyor, dolayısıyla varsayılan kapalı
olmalı gibi görünüyor.

---

## 4. Depolama kotası ve otomatik temizlik

### Neyin sayılacağı

| Tür | Kova | Kim üretir | Kalıcılık |
|---|---|---|---|
| Klip / manuel kayıt | `klipler` | kullanıcı | kotaya dahil |
| Ekran görüntüsü | `ekran-goruntuleri` | kullanıcı | kotaya dahil |
| Kütüphane videosu | `videolar` | moderatör | **kurumsal** — ayrı sayılmalı |
| DVR tamponu | yerel disk | sistem | kota değil, `recordDeleteAfter` |

Kullanıcı kotası yalnızca ilk ikisini kapsamalı: kütüphane videosunu yükleyen
moderatörün kişisel kotasından düşmek yanlış olurdu.

### Ölçüm

Ayrı bir sayaç tablosu **tutulmamalı**: `clips.size_bytes` ve
`screenshots.size_bytes` zaten var, toplamları sorguyla alınır. Ayrı sayaç,
her silme/ekleme sonrası tutarlı kalması gereken ikinci bir doğruluk kaynağı
demek olurdu.

Sorgu maliyeti düşük ama kota kontrolü her yükleme isteğinde çalışacağı için
`(captured_by)` ve `(requested_by)` indeksleri şart — ikisi de mevcut.

### Uygulama

- `storage.user-quota-bytes` (varsayılan: karara bağlı, örn. 20 GB)
- Kota aşılırsa **yeni iş reddedilir**, var olan silinmez. Sessizce silmek
  kullanıcının verisini habersiz yok etmek olurdu.
- Hata mesajı ne kadar kullanıldığını ve neyin silinebileceğini söylemeli.

### Otomatik temizlik

| Tür | Öneri | Gerekçe |
|---|---|---|
| `HATA` durumundaki klipler | 7 gün | Dosyası yok, yalnızca kayıt; kullanıcı sebebi görsün diye bekletiliyor |
| Yarım kalan yüklemeler | mevcut (2 saat) | Zaten var |
| Klip / manuel kayıt | süresiz | Kullanıcı verisi; kota baskı yapsın, saat değil |
| Ekran görüntüsü | süresiz | Aynı |
| DVR tamponu | `recordDeleteAfter` (şu an 168 saat) | Kısaltmak disk baskısını doğrudan düşürür |

**Önemli karar:** kullanıcı verisi zamanla mı silinsin, yoksa yalnızca kota mı
baskı yapsın? Otomatik silme "arşivim duruyor" beklentisini bozar; kota ise
kullanıcıyı temizliğe zorlar ama kontrolü onda bırakır. Öneri: **yalnızca
kota**, otomatik silme yok.

### Yetim nesneler

DB kaydı silinip nesne kalırsa depolama sessizce şişer. Mevcut kodda silme
sırası "önce nesne, sonra kayıt" ve nesne silinemezse kayıt yine siliniyor —
bilinçli bir tercih ama yetim üretebiliyor.

Süpürücüye ek bir iş: MinIO'daki nesneleri listeleyip DB'de karşılığı
olmayanları raporla (silme değil, **rapor** — yanlış pozitifte veri kaybı
olmasın).

---

## 5. VAAPI riski

Alınan geri dönüt haklı. Somut riskler:

| Risk | Etki |
|---|---|
| Donanıma bağımlı | Intel/AMD dışında yok; NVIDIA'da NVENC, ARM'de başka bir şey gerekir |
| Sürücü farkları | **Zaten yaşandı:** host sürücüsü yalnızca CQP destekliyordu, CBR için imaja iHD konuldu |
| Sanal makine / bulut | `/dev/dri` çoğu zaman yok |
| Sessiz başarısızlık | Aygıt geçirilmezse ffmpeg açılışta düşer, rendition'lar **hiç üretilmez** ve arayüzde bir belirti çıkmaz |
| Oturum sınırı | NVIDIA tüketici kartlarında eşzamanlı NVENC oturumu sınırlı (3-8); VAAPI'de böyle bir sınır yok |

### Bu tur yapılanlar

Kodlayıcı **seçilebilir** hale getirildi ve her iki imaj da tüm kodlayıcıları
taşıyor:

```
CHANNELS_ENCODER = VAAPI | NVENC | YAZILIM     (mediamtx konteynerinde çalışır)
VIDEOS_ENCODER   = VAAPI | NVENC | YAZILIM     (video-worker konteynerinde)

her iki imajda: h264_nvenc · h264_vaapi · h264_qsv · libx264
hwaccel: cuda vaapi qsv drm opencl vulkan
```

Worker imajının tabanı bu yüzden değişti: eski statik ffmpeg'de **ne nvenc ne
vaapi vardı**, yalnızca libx264. CUDA desteği kâğıt üzerinde kalırdı.

**NVENC yolu doğrulanmadı** — bu makinede NVIDIA GPU yok.

### Eksik kalan: sessiz başarısızlığı görünür kılmak

En büyük risk teknik değil, **teşhis edilemezlik**. Öneri:

- Kanal `active` ve kaynak `ready` iken rendition path'leri N saniyedir
  `ready` değilse → kodlayıcı çalışmıyor demektir. Arayüzde açık bir uyarı.
- Bu, `notlar.md` madde 5'teki gözcü ile **aynı mekanizma** — birlikte
  yapılmalı.
- İsteğe bağlı: kodlayıcı düşerse `YAZILIM`'a otomatik geçiş. Dikkat: 16
  kanalda yazılım kodlama 22,7 çekirdek ister, yani otomatik geçiş makineyi
  düşürebilir. Geçiş yapılacaksa **kanal sayısı sınırıyla birlikte**
  yapılmalı — yoksa çare hastalıktan kötü olur.

---

## 6. İş kalemleri

### Backend

| # | Dosya | İş |
|---|---|---|
| 1 | `V13__manuel_kayit.sql` | `clips.origin`, `active_recordings` |
| 2 | `V14__ekran_goruntuleri.sql` | `screenshots` tablosu |
| 3 | `clip/RecordingService` | başlat / durdur / devam edenler |
| 4 | `clip/ClipResource` | kayıt uçları, `origin` filtresi |
| 5 | `screenshot/*` | entity, servis, resource, depolama |
| 6 | `screenshot/ScreenshotWorker` | ffmpeg ile kare yakalama (worker'da) |
| 7 | `storage/QuotaService` | kullanım ölçümü ve kontrol |
| 8 | süpürücüler | unutulan kayıtlar, yetim nesne raporu |
| 9 | `application.properties` | kota, kova, saklama ayarları |

### Frontend

| # | Dosya | İş |
|---|---|---|
| 10 | oynatıcı | Kayda başla / durdur düğmesi + süre göstergesi |
| 11 | oynatıcı | Kare yakala düğmesi |
| 12 | `pages/GaleriPage.tsx` | kronolojik galeri, sil / indir |
| 13 | `pages/ClipsPage` | manuel kayıtlar ayrı sekme |
| 14 | profil | kota göstergesi |

---

## 7. Karara bağlanacaklar

1. **DVR/MinIO:** A seçeneği (yerel tampon + MinIO'da kalıcı ürünler) kabul mü?
   B isteniyorsa ayrı faz olarak planlanmalı.
2. **`hlsDirectory: /hls` kaldırılsın mı?** Segmentler belleğe alınırsa disk
   trafiği düşer.
3. **Kare yakalama:** tarayıcıda mı, sunucuda mı, yoksa "izlenen an + DVR"
   birleşimi mi?
4. **Ekran görüntüsü erişimi:** sahibine özel mi, herkese açık mı?
5. **Manuel kayıt üst sınırına gelince:** otomatik dur mu, reddet mi?
6. **Kota değeri** ve kullanıcı verisinde otomatik silme olacak mı?
7. **Kodlayıcı düşerse yazılıma otomatik geçiş** istenir mi (kanal sınırıyla)?
