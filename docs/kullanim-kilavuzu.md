# Yayın Merkezi — Kullanım ve İşletim Kılavuzu

**Doküman Versiyonu:** 1.1.0 (Marian'ın CTranslate2'ye taşınması ve admin
panel otomatik tazeleme güncellemeleriyle revize edildi)
**Yayın Tarihi:** 17 Ağustos 2026
**Hedef Kitle:** Sistem Yöneticileri, Son Kullanıcılar, Operasyon Ekibi

Teknik/mimari referans için bkz. `docs/teknik-dokuman.md` — bu kılavuz
onun yerine geçmiyor, **kurulum ve günlük kullanım** odaklı, farklı bir
okuyucu kitlesi için yazıldı.

---

## 1. Giriş

### 1.1. Dokümanın Amacı

Bu kılavuz, **Yayın Merkezi** platformunun kurulumu, yapılandırılması,
günlük kullanımı ve karşılaşılabilecek sorunların giderilmesi süreçlerinde
rehberlik etmek amacıyla hazırlanmıştır.

### 1.2. Kapsam

Kılavuz şu bileşenlerin operasyonel adımlarını kapsar:

- **Canlı Yayın İzleme** — kanal listesi, HLS oynatıcı, kalite (rendition)
  seçimi
- **Radyo** — ses-yalnız yayın izleme
- **DVR & Klip** — 7 günlük geriye sarma, klip çıkarma, planlı kayıt
- **Video Kütüphanesi** — video yükleme, izleme, önizleme klibi
- **Canlı Altyazı** — otomatik konuşma tanıma + çeviri (Whisper + Marian)
- **Admin Paneli** — kullanıcı/rol yönetimi, sistem sağlığı, sistem
  logları, analitik

### 1.3. Tanımlar ve Kısaltmalar

| Kısaltma | Açıklama |
|---|---|
| API | Application Programming Interface |
| ENV | Environment Variables (`.env` dosyasındaki ortam değişkenleri) |
| DVR | Digital Video Recorder — canlı yayının geriye sarılabilir kaydı |
| HLS | HTTP Live Streaming — tarayıcıda oynatılan video akış biçimi |
| OIDC | OpenID Connect — Keycloak üzerinden kimlik doğrulama protokolü |
| VAD | Voice Activity Detection — konuşma bölütlerini tespit eden bileşen |
| STT | Speech-to-Text — Whisper ile konuşma tanıma |
| Rendition | Aynı yayının farklı çözünürlük/bit hızındaki kopyası |
| Pivot (dil) | Whisper'ın önce ürettiği İngilizce ara metin — tüm çeviriler buradan yapılır |

---

## 2. Sistem Gereksinimleri ve Ön Hazırlık

| Bileşen | Minimum Gereksinim | Önerilen |
|---|---|---|
| İşletim Sistemi | Linux (Docker + `nvidia-container-toolkit` destekleyen herhangi bir dağıtım) | Ubuntu 24.04 LTS |
| Bellek (RAM) | 8 GB | 16 GB veya üzeri |
| Depolama | 60 GB SSD (imajlar ~51 GB'a kadar çıkabilir) | 100 GB NVMe SSD |
| Bağımlılıklar | Docker Engine + Docker Compose v2 | Güncel sürümler |
| GPU (altyazı için) | — (GPU'suz da çalışır, CPU'da) | NVIDIA GPU (≥6 GB VRAM) + `nvidia-container-toolkit` |

> **Not — GPU'suz kurulum:** Canlı altyazı CPU'da da çalışır ama üretim
> gecikmesi izleyicinin HLS gecikmesini (~6-12 sn) aştığı için altyazı
> **hiç görünmez** (geç kalan altyazı geç değil, hiç gösterilmez). GPU
> yoksa `.env`'de `VAD_ENABLED=false` yaparak bu hattı tamamen kapatmak,
> yarım çalışan bir özellik bırakmaktan iyidir.

Kurulum öncesi bağımlılıkları denetlemek için:

```bash
./gereksinimler.sh          # eksik olanı raporlar, hiçbir şey kurmaz
./gereksinimler.sh --kur    # eksikleri kurmayı dener
```

Host makinede **Maven/Node.js kurulu olmasına gerek yok** — build tüm
adımlarıyla Docker imajları içinde yapılır.

---

## 3. Kurulum ve Başlatma

### 3.1. Ortam Değişkenlerinin Hazırlanması

`.env` dosyası elle yazılmaz — donanımı otomatik tespit edip üreten script
kullanılır:

```bash
./yapilandir.sh
```

Bu script GPU/VAAPI/yazılım kodlayıcıyı, mevcut VRAM'i tespit edip
`STT_MODEL`, `WHISPER_INSTANCES`, kodlayıcı ayarlarını buna göre seçer ve
`.env` dosyasını üretir. **`.env` zaten varsa üzerine yazmaz** — yeniden
üretmek isterseniz:

```bash
./yapilandir.sh --zorla
```

`.env` içindeki kritik parametreler:

| Değişken | Açıklama |
|---|---|
| `POSTGRES_PASSWORD`, `MINIO_ROOT_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD` | **Üretimde mutlaka değiştirin** |
| `PUBLIC_HOST` / `CORS_ALLOWED_ORIGINS` | Sunucuya dışarıdan erişilecek adres |
| `CHANNELS_ENCODER` / `VIDEOS_ENCODER` | `NVENC`\|`VAAPI`\|`YAZILIM` — donanım kodlayıcı seçimi |
| `STT_TARGET_LANGS` / `MARIAN_MODELS` | Canlı altyazı hedef dilleri (bkz. §4.6) |

Tam alan listesi: `README.md` → `.env` alanları.

### 3.2. Servislerin Başlatılması

```bash
./baslat.sh
```

Bu komut tüm Docker imajlarını kurar (Triton dahil, ilk seferde ~51 GB
indirme/derleme olduğu için **yavaştır**) ve servisleri ayağa kaldırır.

Diğer bayraklar:

```bash
./baslat.sh --yeniden     # imajları SIFIRDAN kurarak başlat
./baslat.sh --durdur      # durdur (veri korunur)
./baslat.sh --sifirla     # durdur ve TÜM VERİYİ sil — GERİ ALINAMAZ
```

### 3.3. Servislerin Durumunu Doğrulama

```bash
docker compose ps
```

> **Dikkat:** yalnızca `postgres`, `minio`, `triton` servislerinde compose
> seviyesinde `healthcheck` tanımlı — diğerleri (`backend`, `video-worker`,
> `keycloak`, `redis`, `mediamtx`) için `docker compose ps` yalnızca
> `running` gösterir, `healthy`/`unhealthy` göstermez. Bu servislerin
> gerçekten çalıştığını doğrulamak için admin panelin "Sistem Sağlığı"
> ekranına bakın (bkz. §4.5).

### 3.4. Erişim Adresleri

| Servis | Adres | Varsayılan Giriş |
|---|---|---|
| Arayüz | `http://localhost:3000` | — |
| API belgesi (Swagger UI) | `http://localhost:8090/docs` | — |
| Keycloak | `http://localhost:8080` | `admin` / `admin` |
| MinIO konsolu | `http://localhost:9001` | `.env`'deki `MINIO_ROOT_USER`/`PASSWORD` |
| Grafana | `http://localhost:3001` | `admin` / `admin` |

Uygulama kullanıcılarının ilk şifresi **`12345678`** (ilk admin kullanıcı
`admin1`), admin panelden değiştirilebilir.

### 3.5. Üretime Geçiş Kontrol Listesi

`yapilandir.sh`'nin ürettiği `.env` **geliştirme/deneme** için güvenli
varsayılanlar taşır — gerçek/dış erişime açık bir kuruluma geçmeden önce
aşağıdakileri tek tek gözden geçirin:

| Alan | Neden değiştirilmeli |
|---|---|
| `POSTGRES_PASSWORD`, `KEYCLOAK_DB_PASSWORD` | Varsayılan, `.env` dosyasında düz metin duruyor |
| `MINIO_ROOT_PASSWORD` | Aynı gerekçe — nesne deposuna tam erişim şifresi |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak yönetim konsoluna (`:8080`) tam erişim |
| `KEYCLOAK_CLIENT_SECRET` | **Dikkat:** `realm-export.json`'a da gömülü — ikisini **birlikte** değiştirin, tek taraflı değişiklik girişi kırar |
| `admin1` kullanıcısının şifresi (`12345678`) | `realm-export.json`'a gömülü, ilk girişte değiştirme zorunlu DEĞİL — elle değiştirin |
| `PUBLIC_HOST` / `CORS_ALLOWED_ORIGINS` / `MINIO_PUBLIC_URL` | Geliştirmede `localhost`/LAN IP'si — dışarıdan erişilecek gerçek alan adına/']IP'ye çevrilmeli, aksi halde imzalı URL'ler ve CORS sessizce kırılır |
| `MediaMTX` yönetim API'si | Varsayılan yalnızca `127.0.0.1` + Docker bridge ağından erişilebilir; dışarı açık bir ağdaysanız `mediamtx.yml`'deki `authInternalUsers`'a gerçek kullanıcı/şifre tanımlayın |
| Grafana `admin`/`admin` | İlk girişte değiştirin — dashboard'lar iç ağ metriklerini (GPU, DB bağlantı sayısı vb.) gösteriyor |

Bu depoda **TLS/HTTPS sonlandırma yok** — `nginx.conf` yalnızca düz HTTP
sunuyor. Dışarıya açacaksanız önüne ayrı bir reverse proxy (Caddy, Traefik,
ya da bir bulut yük dengeleyici) koyup sertifikayı orada yönetmeniz gerekir;
bu depo bunu içermiyor.

---

## 4. Kullanım Adımları

### 4.1. Sisteme Giriş ve Yetkilendirme

1. Tarayıcınızdan `http://localhost:3000` adresine gidin.
2. **Giriş Yap** ekranında kullanıcı adı ve şifrenizi girin (kimlik
   doğrulama Keycloak üzerinden yapılır).
3. Üç rol vardır: **İzleyici** (varsayılan), **Moderatör** (içerik
   yönetimi), **Yönetici** (tam admin paneli erişimi).

> **Dikkat:** Keycloak'tan alınan oturum token'ı sunucunun kendi adresini
> (`iss=keycloak:8080`) taşımalı. Geliştirme ortamında token'ı elle test
> ederken **host tarayıcısından değil, konteyner içinden** doğrulayın —
> aksi halde `401 Unauthorized` alırsınız (bkz. §5).

### 4.2. Canlı Yayın İzleme

1. Sol menüden **Kanallar**'a tıklayın, listeden bir kanal seçin.
2. Oynatıcı otomatik olarak canlı kenardan birkaç saniye geride başlar —
   bu, altyazının yetişebilmesi için kasıtlı bir tampon (bkz. §4.6).
3. Oynatıcı kontrol çubuğından **kalite** (rendition) seçebilirsiniz;
   yalnızca gerçekten izlenen rendition'lar sunucu tarafında üretilir,
   birkaç saniyelik başlangıç gecikmesi normaldir.
4. Sağ alt köşedeki **?** simgesi o sayfanın rehberli turunu açar.

### 4.3. Radyo Dinleme

Sol menüden **Radyolar**'a tıklayın — akış ve oynatıcı davranışı
kanallarla aynıdır, yalnızca görüntü yoktur.

### 4.4. DVR (Geriye Sarma) ve Klip Alma

1. İzlerken oynatıcı zaman çizelgesinden **geriye sarabilirsiniz**
   (kanalın DVR desteği açıksa) — varsayılan saklama süresi 7 gündür.
2. Beğendiğiniz bir anı **Klip Al** düğmesiyle kırpabilirsiniz; klip
   arka planda işlenir, hazır olunca **Klipler** sayfasında görünür.
   - **Canlı izlerken geriye sarıp** kayıt düğmesine bastığınızda, klip
     **canlıdan değil, o an izlediğiniz geçmiş andan** başlar — düğmeye
     tekrar bastığınızda (canlıya dönmüş de olsanız) o andan o ana kadarki
     bölüm klip olarak kuyruğa alınır.
3. Önizleme oynatıcısının sağ üst köşesindeki **kamera** düğmesiyle o an
   oynayan kareyi de **Galeri**'ye ekleyebilirsiniz — geçmişten
   yakaladığınız kare, sunucunun bildiği gerçek yayın anıyla
   damgalanır (tarayıcının "şimdi"si değil).
4. **Planlı Kayıt** ile ileri bir tarih/saat için otomatik kayıt
   ayarlayabilirsiniz.

### 4.5. Video Yükleme ve İzleme

1. **Videolar** sayfasından yeni video yükleyin.
2. Yükleme tamamlanınca sistem otomatik olarak bir **önizleme klibi**
   üretir; isterseniz altyazı üretimini de tetikleyebilirsiniz (dakikalar
   sürebilir, GPU'ya bağlı işlem).
3. Video kartındaki durum rozeti işlemin aşamasını gösterir
   (Bekliyor / İşleniyor / Hazır / Hata).

### 4.6. Canlı Altyazı ve Dil Seçimi

- Oynatıcının altyazı simgesinden bir dil seçin. Listede yalnızca
  sunucunun **o an gerçekten ürettiği diller** görünür — bu liste
  `.env`'deki `STT_TARGET_LANGS`'a göre otomatik değişir, hiçbir yerde
  sabit kodlanmamıştır.
- **Neden bazen altyazı hiç görünmüyor:** altyazı, izleyicinin o ana
  gelmesinden **önce** üretilmiş olmalı; geç kalan altyazı geç değil
  **hiç** gösterilmez. Bu normal bir gecikme göstergesi değildir —
  üretim boru hattı yavaşladığında (GPU yükü, çok kanal eşzamanlı
  çözümleniyor vb.) bazı cümleler sessizce atlanır. Teşhis için §5.

### 4.7. Ekran Görüntüsü Alma

Oynatıcıdaki kamera simgesiyle o anki kareyi yakalayabilirsiniz —
görüntüler **Galeri** sayfasında birikir.

### 4.8. Admin Paneli (yalnızca Yönetici rolü)

Üst menüdeki **Yönetim Paneline Git** düğmesiyle açılır, dört sekme:

| Sekme | Ne için kullanılır |
|---|---|
| **Genel Bakış** | Anlık izleyici sayısı, aktif DVR kaydı, sistem sağlığı (7 bileşen), son etkinlikler — sayfa **15 saniyede bir otomatik tazelenir**, elle yenilemeye gerek yok |
| **Kullanıcılar** | Kullanıcı listesi, rol değiştirme, şifre sıfırlama, kullanıcı aktivite detayı |
| **Etkinlikler** | Tüm sistem olaylarının (giriş/çıkış, kayıt, klip vb.) ham, filtrelenebilir kaydı |
| **Analitik** | İçerik/kanal performansı, depolama, hata takibi, kullanıcı aktivite özetleri |

**Sistem Logları** ekranı (`/yonetim/sistem-loglari`) tüm konteynerlerin
loglarını teknik olmayan birinin anlayacağı Türkçe mesajlara çevirip
gösterir; rutin gürültü (health-check tekrarları, istek logları)
otomatik süzülür — yalnızca gerçekten anlamlı olaylar görünür.

**Genel Bakış**'taki "Servis Metrikleri" bölümünde Triton'ın model başına
gecikme kartları (`Whisper`, `Almanca çeviri`, `Rusça çeviri` vb.)
**dinamiktir** — `.env`'deki `STT_TARGET_LANGS`'a göre otomatik değişir,
hiçbir dil sabit kodlanmamıştır. Yeni bir dil eklediğinizde (§8 SSS) bu
sayfada ek bir işlem yapmanıza gerek yok, kart kendiliğinden belirir.

### 4.9. Kanal/Radyo Ekleme (Yönetici/Moderatör)

1. **Kanallar** ya da **Radyolar** sayfasında **Yeni Ekle** düğmesine
   basın.
2. Zorunlu alanlar: **isim**, **kaynak adresi** (RTSP/RTMP/SRT/UDP/HLS —
   kanal için; radyo için ayrıca aşağıdaki kaynak türü), **MediaMTX
   path'i** (URL'de görünecek kısa isim, benzersiz olmalı).
3. **Radyoya özel:** kaynak türünü doğru seçin —
   - **Doğrudan**: kaynak zaten HLS/RTSP/RTMP/SRT/UDP/WHEP yayınlıyorsa.
   - **Köprü**: kaynak Icecast/Shoutcast gibi düz bir MP3 akışıysa (bu
     durumda **bit hızı** alanını da doldurun, örn. `128k`) — sistem
     arka planda bunu otomatik olarak uygun formata çevirir.
   Yanlış tür seçilirse yayın hiç akmaz; hangi türü seçeceğinizden emin
   değilseniz kaynağın adresini tarayıcıda doğrudan açıp M3U8/RTSP mi
   yoksa düz bir ses dosyası akışı mı olduğuna bakın.
4. **Kanala özel:** DVR'ı (geriye sarma) açık bırakmak isteyip
   istemediğinizi ve rendition (kalite) listesini burada tanımlayın —
   rendition'lar yalnızca **gerçekten izlenirken** sunucu kaynağı
   tüketir, tanımlı olmaları başlı başına yük getirmez.
5. Kaydettikten birkaç saniye sonra kanal/radyo listede **yayında**
   görünmelidir. Görünmüyorsa §5, Hata 8'e bakın.
6. Silme işlemi **iki adımlıdır**: önce "silme özeti" (kaç klip/ekran
   görüntüsü/DVR saati etkileneceği) gösterilir, sonra **kendi
   şifrenizle** onaylarsınız. Klip/ekran görüntüsü/DVR verisini ayrı ayrı
   "sil" ya da "kanaldan kopar, veriyi tut" olarak seçebilirsiniz —
   varsayılan, kullanıcı içeriğini korumaktır.

### 4.10. Kullanıcı ve Rol Yönetimi (Yönetici)

1. **Yeni kullanıcı:** Admin panel → Kullanıcılar → **Yeni Ekle** —
   kullanıcı adı, e-posta, ad-soyad, geçici şifre ve başlangıç rolünü
   girin. Kullanıcı ilk girişte bu geçici şifreyi kullanır.
2. **Rol değiştirme:** kullanıcı satırındaki rol açılır menüsünden
   (İzleyici / Moderatör / Yönetici) seçin — değişiklik anında etkin
   olur, kullanıcının yeniden giriş yapması gerekmez.
3. **Şifre sıfırlama:** kullanıcı satırındaki şifre simgesi — yeni bir
   geçici şifre üretir, kullanıcıya siz iletmelisiniz (e-posta ile
   otomatik gönderim yoktur).
4. **Kullanıcı aktivite detayı:** kullanıcının adına tıklayınca açılan
   pencerede yüklediği video/klip sayısı, toplam izleme süresi, en çok
   izlediği kanal/radyo, son giriş zamanı gibi ayrıntılar görünür.
5. **Keycloak↔yerel eşitleme** (**Eşitle** düğmesi): Keycloak'ta
   doğrudan (bu panel dışında) yapılan kullanıcı değişikliklerini yerel
   listeye yansıtır. "Sahipsiz kalan" (Keycloak'ta artık olmayan) yerel
   kayıtlar **otomatik silinmez**, yalnızca raporlanır — gerekirse elle
   temizleyin.

---

## 5. Hata Yönetimi ve Sorun Giderme

### Hata 1: Uygulamaya hiç erişilemiyor / "Connection Refused"

**Olası Nedeni:** Servisler henüz ayağa kalkmamış veya bir bağımlılık
çökmüş.

**Çözüm Adımları:**
1. `docker compose ps` ile hangi servisin `Exit`/`Restarting` durumunda
   olduğuna bakın.
2. `docker compose logs <servis> --tail 100` ile son logları inceleyin.
3. Veritabanı servisiyse: `docker compose logs postgres | grep FATAL`.
4. Genel bir belirsizlik varsa tüm ağı yeniden kurun:
   `docker compose up -d` (servis adı vermeden, TÜMÜNÜ).

### Hata 2: Girişte "401 Unauthorized"

**Olası Nedeni:** Oturum süresi dolmuş veya token'ın `iss` (issuer)
bilgisi backend'in beklediğiyle uyuşmuyor (host'tan `localhost:8080`,
backend'in beklediği `keycloak:8080`).

**Çözüm Adımları:**
1. Sayfayı yenileyip tekrar giriş yapın.
2. Kalıcıysa: Keycloak testini **konteyner içinden** yapın, host
   tarayıcısından alınan token'ı doğrudan API'ye yapıştırmayın.
3. Kullanıcının rol/izinlerini Admin panelinden kontrol edin.

### Hata 3: Canlı altyazı hiç gelmiyor / bazı diller eksik

**Olası Nedeni:** Triton (yapay zekâ servisi) modeli yüklenememiş,
GPU belleği dolmuş, ya da dil `.env`'de tanımlı değil.

**Çözüm Adımları:**
1. Admin panel → **Sistem Logları** → `servis=triton` filtresiyle bakın.
2. `docker compose logs triton --tail 200 | grep "successfully loaded\|failed to load"`.
3. Seçtiğiniz dilin gerçekten aktif olup olmadığını doğrulayın:
   `curl http://localhost:8090/api/ayarlar/oynatici` — `altyaziDilleri`
   listesinde olmalı.
4. GPU belleği doluysa (`nvidia-smi`, **host'tan** çalıştırın, konteyner
   içinden yanıltıcı sonuç verir) — bkz. Hata 5.

### Hata 4: Altyazı "gecikiyor" gibi görünüyor

**Olası Nedeni:** Bu genellikle gecikme değil **düşmedir** — mutlak
zaman damgası kuralı yüzünden yetişemeyen bölüt ekranda hiç görünmez,
hata da vermez.

**Çözüm Adımları:**
1. Grafana → "Altyazı Kuyruğu" dashboard'unda **kapsama yüzdesi** ve
   **p95 gecikme** panellerine bakın.
2. `docker compose logs backend | grep "ALTYAZI KAPSAMA"` — bu satır
   dakikada bir kanal başına özet gecikme/kapsama raporu verir.

### Hata 5: Triton sürekli GPU bellek hatası veriyor / çöküyor

**Olası Nedeni:** Eşzamanlı işlenen kanal sayısı GPU'nun kapasitesini
aşıyor, ya da `MARIAN_INSTANCES`/`WHISPER_INSTANCES` VRAM'e göre çok
yüksek ayarlanmış.

**Çözüm Adımları:**
1. `nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv`
   (host'tan) ile hangi sürecin ne kadar VRAM kullandığını görün.
2. `MARIAN_INSTANCES`'ı **kademeli** artırın/azaltın, her değişiklikten
   sonra VRAM'i ölçün — **her yeni kopya tam bir model ağırlığı** demek
   (ölçüldü: bir dilde 2 kopya kartı doldurup çökmeye yol açmıştı, 1
   kopyaya dönünce VRAM %92'den %19'a düştü).
3. Değişiklik yalnızca `docker compose up -d triton` gerektirir
   (rebuild gerekmez).

> **Not:** Marian 17 Ağustos'ta CTranslate2'ye taşındı (eskiden ONNX
> Runtime kullanıyordu) — bu, **saatler içinde VRAM'in kendiliğinden
> büyüyüp kartı doldurması** sorununu kalıcı olarak çözdü (ölçüldü: 452 MB,
> saatlerce sabit). Yukarıdaki "her kopya tam bir model ağırlığı" kuralı
> hâlâ geçerli, ama artık zamanla kendiliğinden kötüleşmiyor.

### Hata 6: Kök nedeni düzelttim ama altyazı hâlâ timeout veriyor

**Olası Nedeni:** Triton'ın kendi iç isteği kuyruğu, önceki çöküş
sırasında biriken (istemcilerin çoktan vazgeçtiği) işlerle **hâlâ dolu**.
Triton bunu bilmiyor, kuyruğu sırayla işlemeye devam ediyor.

**Çözüm Adımları:**
1. Belirti: izole bir test isteği bile (`curl .../infer`) uzun sürüyor,
   halbuki `/v2/health/ready` hızlı cevap veriyor.
2. **Triton'ı tamamen yeniden başlatın:**
   `docker compose stop triton && docker compose up -d triton`
   (yalnızca `restart` değil — kuyruğu temizden başlatmak için).
3. Ardından `video-worker`'ı da yeniden başlatın — Triton'ın Docker içi
   IP'si değiştiği için eski bağlantı havuzu geçersiz kalabilir:
   `docker compose restart video-worker`.

### Hata 7: `.env`'de dil/model değiştirdim ama etkisi görünmüyor

**Olası Nedeni:** `STT_TARGET_LANGS`/`MARIAN_MODELS` Triton'un **build
zamanında** karar verdiği değerler — yalnızca `.env`'i değiştirip
`docker compose up -d` yapmak yetmez.

**Çözüm Adımları:**
1. `docker compose build triton` çalıştırın (model export'u yeniden
   yapılır).
2. `docker compose up -d triton` ile yeniden başlatın.
3. Java kodu da değiştiyse (backend/video-worker), önce
   `./mvnw package -DskipTests`, sonra `docker compose build backend
   video-worker`, sonra `docker compose up -d backend video-worker`.

### Hata 8: Kanal/radyo yayına girmiyor

**Çözüm Adımları:**
1. `curl localhost:9997/v3/paths/list` — path'in `ready:true` olup
   olmadığına bakın.
2. Radyo ise kaynak türünün (`sourceKind`) doğru seçildiğini kontrol
   edin.

---

## 6. Bakım Görevleri

Aşağıdakiler tek seferlik kurulumun ötesinde, **periyodik olarak** yapılması
gereken işler.

### 6.1. Docker disk temizliği

Sık `docker compose build` çalıştırılan bir makinede build önbelleği
onlarca GB'a çıkabilir:

```bash
docker system df                # ne kadar yer kullanıldığını gösterir
docker builder prune -f         # yalnızca build önbelleğini temizler (imajlara dokunmaz)
```

### 6.2. Saklama (retention) ayarlarını gözden geçirin

Varsayılan olarak **kullanıcı içeriği silinmez** — yalnızca kota
(`STORAGE_USER_QUOTA_BYTES`) baskı kurar. Disk doluyorsa `.env`'deki
`STORAGE_CLIP_RETENTION`/`STORAGE_SCREENSHOT_RETENTION` gibi süreleri
(`P30D`, `720h` gibi) ayarlayıp `docker compose up -d backend`
yeterlidir — rebuild gerekmez.

### 6.3. GPU/Triton sağlığını periyodik izleyin

```bash
nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu --format=csv
docker compose ps triton
docker compose logs video-worker --since 5m | grep -icE "timed out|ulaşılamadı|out of memory"
```

Sağlıklı bir sistemde VRAM zamanla **büyümemeli** (sabit bir bantta
kalmalı) ve hata sayısı 0'a yakın olmalı. Sürekli büyüyorsa ya da hata
sayısı artıyorsa §5, Hata 5-6'ya bakın.

### 6.4. Log/disk büyümesini izleyin

Loki logları 7 gün saklıyor (otomatik siliniyor, elle müdahale
gerekmiyor). DVR segmentleri `DVR_RETENTION_DAYS` (varsayılan 7 gün)
sonunda otomatik siliniyor — bu süreyi uzatmak disk kullanımını
doğrudan artırır, MinIO konsolundan (`http://localhost:9001`) kova
boyutlarını periyodik kontrol edin.

---

## 7. Yedekleme ve Geri Yükleme

**Bu depoda hazır bir yedekleme scripti yoktur** — aşağıdaki komutlar
standart Docker/Postgres/MinIO araçlarıyla elle yapılan yedeklemedir.

### 7.1. Neyi yedeklemeniz gerekiyor

| Bileşen | İçerik | Nerede |
|---|---|---|
| PostgreSQL | Tüm ilişkisel veri (kullanıcılar, kanallar, klip/video kayıtları, altyazılar, denetim izi) | `postgres` konteyneri, `postgres_data` volume |
| MinIO | Video/klip/ekran görüntüsü/DVR **dosyaları** | `minio` konteyneri, kendi veri dizini |
| `.env` | Tüm yapılandırma ve parolalar | Depo kök dizini |
| `realm-export.json` | Keycloak realm/kullanıcı tanımı | Depo kök dizini (genelde değişmez, ama elle düzenlediyseniz yedekleyin) |

### 7.2. PostgreSQL yedekleme

```bash
docker compose exec -T postgres pg_dump -U app_user -d yayin_merkezi \
  | gzip > yayin_merkezi_$(date +%Y-%m-%d).sql.gz
```

Geri yükleme (**dikkat: hedef veritabanının BOŞ olması gerekir**):
```bash
gunzip -c yayin_merkezi_2026-08-17.sql.gz | \
  docker compose exec -T postgres psql -U app_user -d yayin_merkezi
```

### 7.3. MinIO yedekleme

En basit yol, konteynerin veri dizinini doğrudan kopyalamak (servisi
**durdurup** tutarlı bir kopya almak en güvenlisi):

```bash
docker compose stop minio
docker run --rm -v yayin-merkezi_minio_data:/kaynak -v "$(pwd)":/hedef \
  alpine tar czf /hedef/minio-yedek-$(date +%Y-%m-%d).tar.gz -C /kaynak .
docker compose up -d minio
```

Servisi durdurmadan yedek almak isterseniz `mc mirror` (MinIO Client)
kullanılabilir — bu depoya dahil değildir, ayrıca kurulmalıdır.

### 7.4. `.env` yedekleme

```bash
cp .env .env.yedek-$(date +%Y-%m-%d)
```

`yapilandir.sh --zorla` zaten her çalıştırıldığında eski `.env`'i
`.env.yedek` olarak saklar — ama bu yalnızca **bir önceki** sürümü tutar,
uzun süreli arşiv için yukarıdaki gibi tarihli bir kopya alın.

---

## 8. Sıkça Sorulan Sorular (SSS)

**S: Sistem loglarına nasıl erişebilirim?**
C: İki yol var — (1) Admin panel → Sistem Logları ekranı, teknik olmayan
Türkçe özet için. (2) Terminalden ham log:
`docker compose logs -f <servis_adı>`.

**S: Yapılandırma değişikliği sonrası servisi nasıl yeniden başlatırım?**
C: Değişikliğin türüne göre değişir:
- Yalnızca `.env`'deki bir **sayı/bayrak** (örn. `MARIAN_INSTANCES`,
  `WHISPER_INSTANCES`, saklama süreleri) → `docker compose up -d <servis>`
  yeterli, **rebuild gerekmez**.
- **Dil listesi** (`STT_TARGET_LANGS`, `MARIAN_MODELS`) → önce
  `docker compose build triton`, sonra `docker compose up -d triton`
  **ve** `video-worker`.
- **Java/frontend kodu** → önce `./mvnw package -DskipTests` (backend
  için), sonra ilgili servisi `docker compose build`, sonra
  `docker compose up -d`.

**S: Yeni bir altyazı dili nasıl eklerim?**
C: `.env`'de `STT_TARGET_LANGS`'a kodu ekleyin (örn. `de,fr`), aynı satırda
`MARIAN_MODELS`'a o dilin Hugging Face model repo'sunu **zorunlu olarak**
belirtin (örn. `fr=Helsinki-NLP/opus-mt-en-fr`) — eksik bırakılırsa build
hata verip durur. Ardından `docker compose build triton && docker compose
up -d triton video-worker backend`. Önyüzdeki dil seçici otomatik güncellenir,
ek bir kod değişikliği gerekmez.

**S: Bir kullanıcının şifresini nasıl sıfırlarım?**
C: Admin panel → Kullanıcılar → ilgili kullanıcının yanındaki şifre
sıfırlama düğmesi.

**S: Altyazı çalışmıyorsa ilk kontrol edeceğim yer neresi?**
C: Admin panel → Genel Bakış → Sistem Sağlığı kartında "Yapay Zekâ"
bileşeninin durumu. Kırmızıysa §5, Hata 3-6 arasındaki adımları izleyin.

**S: Kaç kanal aynı anda GPU'yu doldurur?**
C: Ölçülmüş, sabit bir sayı yok — donanıma (VRAM) ve instance sayısına
göre değişir. Bu makinede (RTX 4050, 6 GB) Marian CTranslate2'ye
taşınmadan önce 15 kanal tek instance'la kartı doldurabiliyordu; kendi
donanımınızda `nvidia-smi`'yi izleyerek ölçmeniz gerekir (bkz.
`docs/teknik-dokuman.md` §8).

**S: `MARIAN_EXPORT_DTYPE`/model motoru değişti mi, eski `.env`'im hâlâ
çalışır mı?**
C: Evet. 17 Ağustos'ta Marian'ın çalışma zamanı ONNX Runtime'dan
CTranslate2'ye taşındı ama `.env`'deki değişken isimleri
(`MARIAN_MODELS`, `MARIAN_EXPORT_DTYPE`, `MARIAN_INSTANCES`) hiç
değişmedi — `docker compose build triton` çalıştırdığınızda otomatik
olarak yeni motora göre dönüştürülür, `.env`'i elle güncellemenize gerek
yok.
