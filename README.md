# Yayın Merkezi

Çok kanallı canlı TV izleme, 7 günlük geriye sarma (DVR) ve kayıttan klip
çıkarma sistemi.

Kaynak yayınlar (HLS / RTSP / RTMP / SRT / UDP) MediaMTX'e alınır, HLS olarak
çoğaltılıp izleyicilere dağıtılır. Yönetim, kimlik doğrulama ve klip üretimi
Quarkus tabanlı backend'de; arayüz React + shadcn/ui.

---

## İçindekiler

- [Ne yapar](#ne-yapar)
- [Mimari](#mimari)
- [Nasıl ayağa kaldırılır](#nasıl-ayağa-kaldırılır)
- [Akış: bir kanal nasıl yayına girer](#akış-bir-kanal-nasıl-yayına-girer)
- [MediaMTX'e binen yük](#mediamtxe-binen-yük)
- [Depolama hesabı](#depolama-hesabı)
- [Bilinçli tasarım kararları](#bilinçli-tasarım-kararları)
- [Bilinen eksikler](#bilinen-eksikler)
- [Geliştirme](#geliştirme)

---

## Ne yapar

| Yetenek | Durum |
|---|---|
| Keycloak ile kimlik doğrulama ve rol bazlı yetki | ✅ |
| Kullanıcı yönetimi (ekleme, rol atama, şifre sıfırlama, silme) | ✅ |
| Kanal CRUD, en fazla 16 eşzamanlı yayın | ✅ |
| Yeniden başlatmada kanalların kendiliğinden ayağa kalkması | ✅ |
| Çoklu izleme (4x4'e kadar mozaik) + büyük ekran | ✅ |
| Sayfa değiştirince yayının kesilmemesi | ✅ |
| 7 günlük DVR ve zaman çizelgesi üzerinden geriye sarma | ✅ |
| Zaman çizelgesinden aralık seçip klip çıkarma | ✅ |
| İzleyici kimlik doğrulaması (HLS erişimi) | ❌ bkz. [Bilinen eksikler](#bilinen-eksikler) |
| Uyarlanabilir bit hızı (transcode) | ❌ kaynak ne veriyorsa o dağıtılır |

### Roller

Keycloak'ta `Yayın_App` client'ının **client rolleri** olarak tanımlıdır
(realm rolü değil):

| Rol | Yetki |
|---|---|
| `Yönetici` | Her şey — kullanıcı yönetimi dahil |
| `Moderatör` | Kanal ve klip yönetimi; kullanıcı yönetemez |
| `İzleyici` | Salt okuma — izler, geriye sarar |

---

## Mimari

```
   Kaynak yayın                MediaMTX                    Tarayıcı
  (HLS/RTSP/SRT/UDP)      :8554 / :8888 / :9997
        │                        │                            │
        │  ① çekme (pull)        │                            │
        ├───────────────────────►│                            │
        │                        │  ② HLS paketleme           │
        │                        ├───────────────────────────►│  izleme
        │                        │                            │
        │                        │  ③ kayıt → disk            │
        │                        │                            │
        │                   :9996 geri sarma                  │
        │                        ▲                            │
        │                        │                            │
                          Backend :8081  ◄────────────────────┤  yönetim
                                 │                            │
                    ┌────────────┼────────────┐               │
                    ▼            ▼            ▼               │
              PostgreSQL     Keycloak       MinIO ◄────────────┘
             kanal/klip      kimlik        klipler      imzalı indirme
```

**Kritik nokta: video backend'den geçmez.** Backend yalnızca MediaMTX'e
"şu kaynağı şu path'te yayınla" der; video trafiği tarayıcı ile MediaMTX
arasında doğrudan akar. Klip indirmesi de imzalı adresle doğrudan MinIO'dan
yapılır. Bu yüzden backend izleyici sayısıyla ölçeklenmez ve yeniden
başlatılması yayını kesmez.

### Bileşenler

| Bileşen | Port | Görev |
|---|---|---|
| Frontend (nginx) | 3000 | Arayüz + `/api` proxy'si |
| Backend (Quarkus) | 8090 → 8081 | Kanal/kullanıcı/klip yönetimi |
| MediaMTX RTSP | 8554 | Yayın alma |
| MediaMTX HLS | 8888 | İzleyiciye dağıtım |
| MediaMTX API | 9997 | Path yönetimi (IP kısıtlı) |
| MediaMTX playback | 9996 | Geri sarma — **yalnızca 127.0.0.1** |
| Keycloak | 8080 | Kimlik |
| PostgreSQL | 5433 | Uygulama verisi |
| MinIO | 9000 / 9001 | Klip dosyaları |
| Redis | 6379 | Şu an kullanılmıyor |

---

## Nasıl ayağa kaldırılır

### Gereksinimler

Docker + Docker Compose, JDK 21, Node 20+.

### 1. Ortam değişkenleri

Proje kökünde `.env` oluşturun:

```bash
QUARKUS_PROFILE=prod

POSTGRES_USER=app_user
POSTGRES_PASSWORD=<güçlü-bir-şifre>
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/yayin_merkezi

KEYCLOAK_SERVER_URL=http://localhost:8080
KEYCLOAK_REALM=YayinYonetimi
KEYCLOAK_ISSUER_URI=http://localhost:8080/realms/YayinYonetimi
KEYCLOAK_CLIENT_ID=Yayın_App
KEYCLOAK_CLIENT_SECRET=<client-secret>
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=<güçlü-bir-şifre>
KEYCLOAK_BOOTSTRAP_PASSWORD=<ilk-yönetici-şifresi>

MINIO_ROOT_USER=minio_admin
MINIO_ROOT_PASSWORD=<güçlü-bir-şifre>
MINIO_PUBLIC_URL=http://localhost:9000

MEDIAMTX_HLS_BASE_URL=http://localhost:8888
CORS_ALLOWED_ORIGINS=http://localhost:3000

# 7 günlük DVR için büyük disk — bkz. Depolama hesabı
DVR_PATH=/mnt/dvr
```

> `.env` compose dosyasının bulunduğu dizinde aranır. Kökte tutmak
> istiyorsanız `src/main/docker/.env` bir symlink olmalı:
> `ln -s ../../../.env src/main/docker/.env`

`MEDIAMTX_HLS_BASE_URL` ve `MINIO_PUBLIC_URL` **tarayıcının çözebileceği**
adresler olmalı. Container içi isimler (`http://mediamtx:8888`) yazılırsa
oynatıcı yayını bulamaz, indirme çalışmaz.

### 2. Backend'i paketleyin

```bash
./mvnw clean package -DskipTests
```

Docker imajı `target/quarkus-app/` dizinini kopyalar; bu adım atlanırsa
imaj derlemesi başarısız olur.

### 3. Her şeyi başlatın

```bash
cd src/main/docker
docker compose up -d --build
```

Keycloak ilk açılışta `keycloak/realm-export.json`'u içe aktarır: realm,
client, roller, service account yetkileri ve `admin1` kullanıcısı hazır gelir.

> Realm **zaten varsa** dosya yok sayılır. Yani bu dosyayı değiştirmek mevcut
> bir Keycloak'ı güncellemez; sadece sıfır ortamlarda etkilidir.

### 4. Girin

| | |
|---|---|
| Arayüz | http://localhost:3000 |
| Kullanıcı | `admin1` / `KEYCLOAK_BOOTSTRAP_PASSWORD` |
| API dokümanı | http://localhost:8090/docs |
| MinIO konsolu | http://localhost:9001 |
| Keycloak | http://localhost:8080 |

---

## Akış: bir kanal nasıl yayına girer

```
1. Yönetici arayüzden kanal ekler
   → POST /api/channels { name, sourceUrl, mediamtxPath, active, dvrEnabled }

2. Backend sırayla:
   ├─ isim ve path çakışması var mı?
   ├─ 16 kanal sınırı aşılıyor mu?
   ├─ channels tablosuna INSERT            ← kalıcı tanım
   └─ MediaMTX'e path yaz (REST API)       ← çalışan yapılandırma

3. MediaMTX kaynağa bağlanır ve yayını çekmeye başlar
   (sourceOnDemand: false — izleyici olmasa da çeker)

4. İlk izleyici geldiğinde HLS segmentleri üretilir
   → http://localhost:8888/<path>/index.m3u8

5. dvrEnabled ise MediaMTX aynı anda diske kayıt yazar
   → /recordings/<path>/2026-07-31_12-00-00.mp4  (1 saatlik segmentler)
```

**Sıra bilinçli: önce veritabanı, sonra MediaMTX.** MediaMTX çağrısı
patlarsa transaction geri alınır ve iki taraf da değişmemiş olur. Ters
sırada, MediaMTX'te kaydı olmayan sahipsiz bir yayın kalabilirdi.

### Yeniden başlatma

MediaMTX path yapılandırmasını **yalnızca bellekte** tutar; konteyner
yeniden başlayınca tüm kanallar kaybolur. Kalıcı tanım `channels`
tablosundadır:

```
Uygulama açılışı → StartupEvent → ChannelRestorer
                                     └─ aktif kanalları oku → MediaMTX'e yeniden yaz
```

MediaMTX **backend'den bağımsız** yeniden başlatılırsa bu otomatik çalışmaz;
Kanallar sayfasındaki "MediaMTX'e yeniden yaz" düğmesiyle elle tetiklenir.

### Klip çıkarma

```
1. Kullanıcı zaman çizelgesinde aralık seçer
   → POST /api/channels/{id}/clips { start, end }

2. Backend: clips tablosuna BEKLIYOR yazar, 202 döner   ← istek burada biter

3. Arka plandaki işçi (5 sn'de bir yoklar):
   ├─ BEKLIYOR → ISLENIYOR   (FOR UPDATE SKIP LOCKED)
   ├─ MediaMTX :9996/get'ten akışı çeker
   ├─ MinIO'ya AKIŞ HALİNDE yazar (belleğe almadan)
   └─ HAZIR + object_key + size_bytes

4. Kullanıcı Klipler sayfasından izler veya indirir
   → GET /api/clips/{id}/links → imzalı MinIO adresleri
```

---

## MediaMTX'e binen yük

Sistemdeki neredeyse tüm medya yükü MediaMTX'te. Ölçülen değerler
(8 çekirdek / 16 GB, 16 eşzamanlı yayın):

| | Değer |
|---|---|
| CPU | **%13,3** (~0,13 çekirdek) |
| RAM | **352 MB** |
| HLS manifesti alınabilen | 16 / 16 |

Kanal başına maliyet çok düşük çünkü **yeniden kodlama yapılmıyor** — akış
yalnızca paketleniyor (stream copy). Bu ölçüm gerçekte olacağından ağır:
test düzeneğinde MediaMTX hem 16 yayını alıyor hem kendi içinden 16 kez
tekrar çekiyordu.

### Dağıtım (bir kaynak → çok izleyici)

MediaMTX kaynağa **tek** bağlantı açıp N izleyiciye dağıtır. Ölçülen:

| | |
|---|---|
| Kaynaktan alınan | +10,8 MB |
| İzleyicilere gönderilen | +126 MB |
| Çoğaltma | ~12× |

### Gecikme

| | Ölçülen |
|---|---|
| LL-HLS part | 240 ms |
| Segment | 1,96 sn |
| Paketleme gecikmesi | **~3,9 sn** |
| Toplam (yerel kaynak) | ~4,5–6 sn |

Segment süresi 1 sn olarak ayarlı olmasına rağmen 1,96 sn çıkıyor: segment
sınırları anahtar kareye hizalanmak zorunda ve test kaynağının GOP'u 2 saniye.
**Gecikmeyi düşürmek için kaynağın GOP'unu kısaltmak gerekir**, MediaMTX
ayarını değiştirmek tek başına işe yaramaz.

### Asıl sınır: bant genişliği

Upstream sabit, downstream izleyici sayısıyla çarpılır. 6 Mbps'lik bir yayında:

| İzleyici | Downstream |
|---|---|
| 50 | 300 Mbps |
| 100 | 600 Mbps |
| ~165 | **1 Gbps — hat dolar** |

Bu bir yazılım sınırı değil. Daha fazlası için MediaMTX önüne CDN veya nginx
önbelleği gerekir (`hlsCDNSecret` ayarı bunun için).

### Tarayıcı tarafı

16 karolu mozaikte asıl darboğaz sunucu değil **tarayıcıdır**: 16 eşzamanlı
H.264 çözücü çalışır. `capLevelToPlayerSize` ile her karo kendi boyutuna
uygun rendition çeker, ama kaynak tek renditionlı ise bu ayarın yapacağı bir
şey yoktur — 16 kez tam çözünürlük çözülür.

---

## Depolama hesabı

Kanalın **Mbps** değeri, saniyede diske yazılan veriyle aynıdır (yeniden
kodlama yapılmadığı için). Bit/byte karıştırılmamalı: **Mbps ÷ 8 = MB/sn**.

Ölçüm: 2 Mbps'lik bir kanal dakikada **15,7 MB** yazıyor (hesap 15,5 MB;
aradaki fark fMP4 kapsayıcı yükü).

7 günlük DVR için gereken alan:

| Bit hızı | Kanal başına | 4 kanal | 8 kanal | 16 kanal |
|---|---|---|---|---|
| 2 Mbps | 151 GB | 0,60 TB | 1,21 TB | **2,42 TB** |
| 4 Mbps | 302 GB | 1,21 TB | 2,42 TB | **4,84 TB** |
| 6 Mbps | 454 GB | 1,81 TB | 3,63 TB | **7,26 TB** |
| 8 Mbps | 605 GB | 2,42 TB | 4,84 TB | 9,68 TB |

%20 boş alan payıyla 16 kanal / 7 gün için: 2 Mbps'te **2,9 TB**,
6 Mbps'te **8,7 TB** disk gerekir. Diski `DVR_PATH` ile gösterin.

> **Disk dolarsa MediaMTX kayıt yazamaz ve kötü senaryoda canlı yayın da
> etkilenir.** `recordDeleteAfter` (varsayılan 168 saat) her zaman diskin
> gerçek kapasitesiyle tutarlı olmalı.

Kayıt kanal bazında açılır (`dvr_enabled`); tüm kanallarda açık değildir.

---

## Bilinçli tasarım kararları

### Kaynağın kalitesi olduğu gibi dağıtılıyor

**Transcode yok.** MediaMTX gelen akışı yeniden kodlamıyor, yalnızca
paketliyor. Sonuçları:

- CPU maliyeti çok düşük (16 kanal ~0,13 çekirdek)
- Ama **uyarlanabilir bit hızı yok**: kaynak 1080p tek renditionsa,
  mobil veya zayıf bağlantıdaki izleyici ya onu çeker ya hiç izleyemez
- Kaynak çok renditionlı bir HLS ise MediaMTX **birini** seçer; hangisini
  seçtiği depolama ve bant genişliğini doğrudan belirler

Kurum dışına dağıtım hedefleniyorsa araya bir transcode katmanı gerekir ve
CPU tablosu tamamen değişir (kanal başına 1+ çekirdek).

### Kuyruk veritabanında, Redis değil

Klip işleri `clips` tablosunda tutulur ve işçi `FOR UPDATE SKIP LOCKED` ile
alır. Redis stack'te mevcut ama **kullanılmıyor**.

Gerekçe: iş zaten `clips` tablosunda kalıcı olmak zorunda. İki yere birden
yazmak, biri başarılı diğeri başarısız olduğunda ya kaybolan ya iki kez
işlenen işler üretirdi. Tek kaynak = tutarlılık sorunu yok.

Bedeli: yoklama aralığı kadar gecikme (5 sn). Klip üretimi zaten dakikalar
sürdüğü için önemsiz.

**İleride Redis eklenecek.** Gerekçesi olduğunda: çok sayıda backend kopyası,
saniye altı iş dağıtımı, ya da kuyruk derinliğinin veritabanını yormaya
başlaması. O noktada `clips` tablosu doğruluk kaynağı olarak kalmalı ve Redis
yalnızca bildirim kanalı olmalı — kuyruğu tamamen Redis'e taşımak yukarıdaki
tutarlılık sorununu geri getirir.

### Klip üretimi asenkron

2 saatlik bir klip 6 Mbps'te ~5,4 GB eder. Senkron indirmede HTTP bağlantısı
dakikalarca açık kalır, kullanıcı sekmeyi kapatınca iş boşa gider, sunucu
yeniden başlarsa iş kaybolur.

İki koruma var:
- **Eşzamanlılık sınırı 2** (`clips.concurrency`). Sınırsız bırakılırsa disk
  ve ağ doyar, canlı yayın etkilenir. Klip üretimi hiçbir zaman yayının önüne
  geçmemeli.
- **Akış halinde yazma** — dosya belleğe veya geçici diske alınmaz.

### Kapasite sınırı uygulama katmanında

`channels.max-active=16`. Sınırı MediaMTX uygulamıyor; aşıldığında sessizce
kabul edip **tüm** kanallarda bozulmaya yol açıyor. Bu yüzden sınır backend'de,
açık bir hata olarak uygulanıyor.

> Doğrudan veritabanına yazılan kayıtlar bu denetimi atlar.

### Oynatıcılar router'ın dışında

Sayfa değiştirince yayının kesilmemesi için oynatıcılar `<Outlet/>`'in
**dışında**, `AppLayout` içinde yaşar. Görünüm yalnızca CSS ile değişir
(mozaik ↔ mini oynatıcı); bileşen ağacı hiç değişmez. Farklı durumlar için
farklı JSX dalları döndürülseydi React ağacı söker ve her geçişte yayın
baştan bağlanırdı.

---

## Bilinen eksikler

Bunlar bilinen ve kabul edilmiş boşluklar — sürpriz değil, sıradaki iş.

### İstisnaların tamamı düşünülmedi

Şu an yalnızca **öngörülen** hata yolları ele alınmış durumda: MediaMTX
erişilemezliği, Keycloak yetki hataları, kayıt bulunamaması, kapasite aşımı,
geçici/kalıcı klip hataları. Bunların dışındaki her şey
`GenericExceptionMapper`'a düşüp 500 dönüyor.

Detaylandırılacak alanlar:

- MinIO erişilemezliği ve yarım kalan yükleme
- Klip işçisi süreç ortasında ölürse `ISLENIYOR` durumunda kalan işler —
  şu an kimse toparlamıyor, elle `BEKLIYOR`'a çekmek gerekir
- MediaMTX ile veritabanı arasındaki kayma senaryolarının tamamı
- Eşzamanlı düzenleme çakışmaları (iyimser kilit yok)
- Disk dolduğunda davranış

### İzleyici kimlik doğrulaması yok

`:8888`'e erişebilen herkes, uygulamaya hiç giriş yapmadan tüm kanalları
izleyebilir. `mediamtx.yml`'da izleme izni `ips: []` ile herkese açık.

Bu, "video backend'den geçmez" tasarımının doğrudan sonucu: backend video
isteklerini görmediği için yetki de kontrol edemiyor. Çözüm backend'i araya
sokmak değil (ölçeklenebilirlik kaybedilir), MediaMTX'in kendi doğrulama
mekanizmasını kullanmak.

### Diğer

| Eksik | Not |
|---|---|
| Tek nokta arızası | MediaMTX kümelenmiyor; düşerse tüm kanallar gider |
| İzleyici sayısı geç düşer | `hlsMuxerCloseAfter: 1m` — gösterge "son bir dakikada izleyen" |
| Klip temizliği yok | Süresi dolan klipler silinmiyor, MinIO sınırsız büyür |
| WebRTC kapalı | MediaMTX'te açık ama `:8889` compose'da yayınlanmamış |
| Otomatik test yok | Doğrulama elle yapıldı |
| Paket boyutu | Frontend 965 kB (302 kB gzip); hls.js'in payı ~520 kB |

---

## Geliştirme

### Backend

```bash
./mvnw quarkus:dev          # :8081, canlı yeniden yükleme
```

Şema değişiklikleri Flyway ile (`src/main/resources/db/migration`).
Hibernate şemayı değiştirmez (`database.generation=validate`).

### Frontend

```bash
cd frontend/yayin-frontend
npm install
npm run dev                 # :3000, /api backend'e proxy'lenir
```

Proxy sayesinde tarayıcı tek origin görür; geliştirmede CORS devre dışı kalır.

### Yalnızca altyapı

```bash
cd src/main/docker
docker compose up -d postgres keycloak-postgres keycloak minio redis mediamtx
```

### Faydalı komutlar

```bash
# MediaMTX path durumu
curl -s localhost:9997/v3/paths/list | python3 -m json.tool

# Bir kanalın gömülü oynatıcısı
xdg-open http://localhost:8888/<path>/

# Kayıt aralıkları
curl -s "localhost:9996/list?path=<path>" | python3 -m json.tool
```

### Ayrıntılı belgeler

| Belge | İçerik |
|---|---|
| [docs/yayin-mimarisi.md](docs/yayin-mimarisi.md) | Akış mimarisi, gecikme bütçesi, diske yazılan dosyalar |
| [docs/faz2-dvr-plani.md](docs/faz2-dvr-plani.md) | DVR ve klip çıkarma planı, depolama hesabı |
