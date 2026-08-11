# Yayın Merkezi

Çok kanallı canlı TV izleme, 7 günlük geriye sarma (DVR) ve kayıttan klip
çıkarma sistemi.

Kaynak yayınlar (HLS / RTSP / RTMP / SRT / UDP) MediaMTX'e alınır, HLS olarak
çoğaltılıp izleyicilere dağıtılır. Yönetim, kimlik doğrulama ve klip üretimi
Quarkus tabanlı backend'de; arayüz React + shadcn/ui.

---

## Hızlı başlangıç

**İki adım:**

```bash
./yapilandir.sh    # donanımı bulup .env üretir
./baslat.sh        # paketler, kurar, başlatır
```

Neden ayrı: donanım tespiti her zaman doğru olmayabilir — birden fazla GPU,
eksik sürücü, sunucuda farklı bir kart. Arada `.env`'i gözden geçirip
düzeltebilmeniz gerekiyor. `baslat.sh`, `.env` yoksa başlatmaz ve
`yapilandir.sh`'e yönlendirir.

`yapilandir.sh` bulduğu donanımı ekrana yazar ve `.env`'i buna göre doldurur:

| Bulunan | Sonuç |
|---|---|
| NVIDIA (`nvidia-smi` çalışıyor) | `CHANNELS_ENCODER=NVENC`, konteynerlere GPU açılır |
| Intel/AMD (`/dev/dri/renderD128`) | `CHANNELS_ENCODER=VAAPI`, `/dev/dri` geçirilir |
| Donanım yok | `CHANNELS_ENCODER=YAZILIM` (libx264) |

LAN adresi de otomatik: HLS ve MinIO adresleri `localhost` yerine makinenin
gerçek IP'siyle üretilir, böylece ağdaki başka cihazlardan da çalışır.

Tespit yanlışsa `.env`'deki kodlayıcı bloğunu elle düzeltin — dosyanın içinde
NVIDIA ve Intel/AMD için hazır örnekler var.

### Diğer komutlar

```bash
./yapilandir.sh --zorla  # .env'i yeniden üret (var olanın üzerine)

./baslat.sh --yeniden    # imajları sıfırdan kurarak başlat
./baslat.sh --durdur     # durdur (veri korunur)
./baslat.sh --sifirla    # durdur ve TÜM VERİYİ sil
```

> **`--sifirla` geri alınamaz.** `docker compose down -v` çalıştırır: kanallar,
> radyolar, kullanıcılar, klipler ve yüklenen videolar dahil her şey gider.
> Yalnızca temiz bir kurulum istediğinde kullan.

> **`--zorla` kurulu bir sistemde dikkat ister.** Veritabanı ve MinIO
> parolaları volume ilk oluşturulurken içine gömülüyor; yeni `.env` farklı
> parola yazarsa bağlantı kopar. Script bunu fark edip onay soruyor ve eskisini
> `.env.yedek` olarak saklıyor.

### Ön koşullar

`docker`, `docker compose` (v2) ve `java` (21+). Script yoksa söyler.

### Compose'u elle çalıştırmak

`docker-compose.yaml` **proje kökünde**, yani `-f` gerekmiyor:

```bash
docker compose up -d
docker compose logs -f backend
docker compose down
```

`.env` de kökte ve compose onu kendi dizininden okuyor — ek bir ayar yok.

> Compose dosyasında `name: yayin-merkezi` **açıkça yazılı**. Verilmezse
> Compose proje adını bulunduğu dizinden türetir ve volume adları da ona
> bağlanır; dosya taşındığında adlar değişir ve veritabanı bir anda "boş"
> görünür. Bu satır o tuzağı kapatıyor — **silmeyin**.

### Adresler

| | |
|---|---|
| Arayüz | http://localhost:3000 |
| API belgesi | http://localhost:8090/docs |
| Keycloak | http://localhost:8080 — `admin` / `admin` |
| MinIO konsolu | http://localhost:9001 |

Uygulama kullanıcılarının ilk şifresi **12345678**. Keycloak client secret'ı
da `12345678`; `realm-export.json` içine gömülü olduğu için `.env`'deki
`KEYCLOAK_CLIENT_SECRET` ile **aynı kalmak zorunda**.

> Realm yalnızca **ilk açılışta** kurulur. `realm-export.json`'ı sonradan
> değiştirmek mevcut bir Keycloak'ı güncellemez; `--sifirla` gerekir.

### Donanım kodlayıcı — elle değiştirmek

Otomatik tespit yanlışsa `.env`'de düzeltilir (ayrı compose dosyası yok):

```bash
# NVIDIA
CHANNELS_ENCODER=NVENC
VIDEOS_ENCODER=NVENC
CONTAINER_RUNTIME=nvidia
NVIDIA_VISIBLE_DEVICES=all
NVIDIA_DRIVER_CAPABILITIES=video,compute,utility
MEDIA_DEVICE=/dev/null:/dev/null

# Intel / AMD
CHANNELS_ENCODER=VAAPI
CONTAINER_RUNTIME=runc
MEDIA_DEVICE=/dev/dri:/dev/dri
```

`MEDIA_DEVICE` neden var: `/dev/dri` sabit yazılsaydı, o aygıtın bulunmadığı
bir NVIDIA sunucusunda konteyner hiç başlamazdı. `/dev/null` zararsız bir yer
tutucu.

> **NVENC oturum sınırı:** GeForce kartlarda eşzamanlı NVENC oturumu sürücü
> tarafından sınırlı (genellikle 3-8). Çok kanallı transcode için
> Quadro/RTX Ada/Tesla sınıfı kart gerekir.

---

## NVIDIA kurulumu

`.env`'de `CONTAINER_RUNTIME=nvidia` yazıyor ama şu hatayı alıyorsanız:

```
unknown or invalid runtime name: nvidia
```

…ya da video-worker NVENC tarafında hata veriyorsa, **host'ta eksik olan
bileşenler var.** Aşağıdaki adımlar tamamlandığında `.env`'i doldurup
çalıştırabilirsiniz.

Gereken iki şey: **çalışan bir NVIDIA sürücüsü** ve **NVIDIA Container
Toolkit**. CUDA Toolkit'in tamamını host'a kurmanıza gerek yok.

### 1. Sürücüyü kur

```bash
sudo apt-get update
sudo apt-get install -y ubuntu-drivers-common pciutils linux-headers-$(uname -r)

lspci -nnk | grep -A3 -Ei 'VGA|3D|Display'   # GPU görünüyor mu
ubuntu-drivers devices                        # uygun sürücüler
sudo ubuntu-drivers install                   # önerileni kur
sudo reboot
```

Secure Boot açıksa kurulum bir **MOK parolası** sorar; yeniden başlarken çıkan
mavi ekranda anahtarı kaydetmeniz gerekir. Atlanırsa sürücü yüklenmez.

### 2. Sürücüyü doğrula

```bash
nvidia-smi
nvidia-smi -L
```

İkisi de GPU bilgisi göstermeli. Hâlâ şu hatayı alıyorsanız **sonraki adıma
geçmeyin** — toolkit kurmak bu sorunu çözmez:

```
NVIDIA-SMI has failed because it couldn't communicate with the NVIDIA driver
```

Teşhis için:

```bash
uname -r
mokutil --sb-state
cat /proc/driver/nvidia/version
dkms status
ubuntu-drivers devices
journalctl -k -b | grep -Ei 'nvidia|nouveau|NVRM' | tail -100
```

### 3. Container Toolkit deposunu ekle

```bash
sudo apt-get install -y --no-install-recommends ca-certificates curl gnupg2
sudo install -m 0755 -d /usr/share/keyrings

curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
  | sudo gpg --dearmor --yes -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg

curl -sL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
  | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
  | sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list
```

### 4. Toolkit'i kur

```bash
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit nvidia-container-toolkit-base \
  libnvidia-container-tools libnvidia-container1

nvidia-ctk --version
nvidia-container-runtime --version
```

### 5. Runtime'ı Docker'a kaydet

```bash
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker

docker info --format '{{json .Runtimes}}'
```

Çıktıda hem `runc` hem `nvidia` bulunmalı:

```json
{"io.containerd.runc.v2":{"path":"runc"},"nvidia":{"path":"nvidia-container-runtime"},"runc":{"path":"runc"}}
```

`sudo cat /etc/docker/daemon.json` içinde de `nvidia-container-runtime` kaydı
görünmeli.

### 6. GPU'yu konteyner içinde dene

```bash
docker run --rm --gpus all nvidia/cuda:12.4.0-base-ubuntu22.04 nvidia-smi
```

GPU tablosu geliyorsa Docker–NVIDIA bağlantısı hazır.

### 7. `.env`'i doldur ve başlat

```bash
grep -E '^(CHANNELS_ENCODER|VIDEOS_ENCODER|CONTAINER_RUNTIME|NVIDIA_VISIBLE_DEVICES|NVIDIA_DRIVER_CAPABILITIES)=' .env
```

Şöyle olmalı:

```bash
CHANNELS_ENCODER=NVENC
VIDEOS_ENCODER=NVENC
CONTAINER_RUNTIME=nvidia
NVIDIA_VISIBLE_DEVICES=all
NVIDIA_DRIVER_CAPABILITIES=video,compute,utility
```

```bash
docker compose down
./baslat.sh
```

> **Sıra önemli.** `docker info` çıktısında `nvidia` görünmeden `./baslat.sh`
> çalıştırırsanız `unknown or invalid runtime name: nvidia` hatası devam eder.

---

## Servisler

Dokuz konteyner. `./baslat.sh` hepsini birlikte ayağa kaldırır.

| Servis | İmaj | Port | Görevi |
|---|---|---|---|
| `postgres` | postgres:16 | 5433→5432 | Uygulama verisi |
| `keycloak-postgres` | postgres:16 | — | Keycloak'ın kendi veritabanı |
| `keycloak` | keycloak:25 | 8080 | Kimlik ve roller |
| `minio` | minio:latest | 9000, 9001 | Klip, video, küçük resim, **DVR kaydı** |
| `redis` | redis:7 | 6379 | İş kuyruğu bildirimi |
| `mediamtx` | özel | 8554, 8888, 9997 | Yayın sunucusu |
| `backend` | özel | 8090→8081 | REST API, kontrol düzlemi |
| `video-worker` | özel | — | ffmpeg işleri |
| `frontend` | özel | 3000 | Arayüz (nginx) |

### postgres

Kanallar, radyolar, klipler, videolar, kullanıcılar. Şema **Flyway** ile
yönetiliyor; Hibernate şemayı değiştirmiyor, yalnızca doğruluyor.

Host'ta **5433**'e açılıyor çünkü 5432'yi makinede kurulu PostgreSQL tutabiliyor.
Compose ağı içinde adres yine `postgres:5432`.

### keycloak-postgres

Keycloak'ın verisi uygulama verisinden **ayrı** tutuluyor: Keycloak sürüm
yükseltmeleri kendi şemasını değiştiriyor ve bunun uygulama tablolarıyla aynı
veritabanında olması geri dönüşü zorlaştırırdı. Dışarı port açmıyor.

### keycloak

`start-dev --import-realm` ile açılıyor. Realm tanımı
`src/main/docker/keycloak/realm-export.json`.

> **Realm yalnızca ilk açılışta kurulur.** Dosya sonradan değiştirilirse
> mevcut Keycloak güncellenmez — `./baslat.sh --sifirla` gerekir.

Backend Keycloak'ı üç şekilde kullanıyor: token doğrulama (OIDC), kullanıcı
yönetimi (Admin REST API, service account ile) ve şifre doğrulama
(direct grant).

### minio

S3 uyumlu nesne depolama. `klipler` ve `videolar` kovaları açılışta
kendiliğinden oluşturuluyor.

**Dosyalar backend'den geçmiyor:** yükleme imzalı PUT adresiyle doğrudan
tarayıcıdan, indirme imzalı GET adresiyle doğrudan MinIO'dan. 5 GB'lık bir
dosyayı backend üzerinden akıtmak canlı yayın API'siyle aynı süreci
dakikalarca meşgul ederdi.

### redis

Klip ve video kuyruklarının **bildirim kanalı** — doğruluk kaynağı değil.
İşin kalıcı hali veritabanında; Redis çökse iş kaybolmaz, yalnızca gecikme
süpürücünün aralığına düşer.

### mediamtx

Yayın sunucusu. Kaynağı çeker, HLS üretir, DVR kaydı yazar, geçmişten oynatır.

Özel imaj çünkü resmi imaj `scratch` tabanlı — içinde kabuk bile yok. Rendition
üretimi ve radyo köprüsü `runOnAvailable`/`runOnInit` kancalarıyla **konteynerin
içinde** ffmpeg çalıştırıyor, dolayısıyla ffmpeg oraya girmek zorunda. İmaj
ayrıca kendi VAAPI sürücüsünü (iHD) taşıyor.

| Port | Ne |
|---|---|
| 8554 | RTSP |
| 8888 | HLS (tarayıcı buradan izliyor) |
| 9997 | REST API (backend path'leri buradan yönetiyor) |

> `:9996` (playback) **kapatıldı**. DVR kaydı MediaMTX'te değil artık;
> geriye sarma MinIO'daki segmentlerden üretiliyor.

### backend

Quarkus. Kanal/radyo/video/klip yönetimi, kimlik, MediaMTX'e path yazma.
Video **içeriği** buradan geçmiyor; tek istisna geriye sarma akışı, o da
yetkilendirme gerektirdiği için.

### video-worker

Backend ile **aynı jar**, üstüne ffmpeg eklenmiş ikinci imaj. Küçük resim,
önizleme klibi, faststart remux ve metadata çıkarımı burada.

Ayrı olmasının sebebi ffmpeg'in backend imajını ~300 MB büyütmesi ve REST
sürecinin onu hiç kullanmaması. Sorumluluk ayrımı ortam değişkenleriyle:
`VIDEOS_WORKER_ENABLED=true` burada, `CLIPS_WORKER_ENABLED=true` backend'de.

### frontend

React + Vite, nginx ile sunuluyor. nginx `/api/` yolunu backend'e proxy'liyor,
bu yüzden tarayıcı **tek origin** görüyor ve API çağrılarında CORS devreye
girmiyor.

---

## `.env` alanları

`.env` gitignore'da. `./baslat.sh` yoksa üretir; **varsa dokunmaz**.
Yeniden ürettirmek için silip scripti tekrar çalıştırın.

### Profil

| Alan | Değerler | Açıklama |
|---|---|---|
| `QUARKUS_PROFILE` | `prod` \| `dev` | `dev` SQL loglarını açar ve konsol çıktısını okunur yapar. Konteynerde `prod` önerilir. |

### Veritabanı

| Alan | Örnek | Açıklama |
|---|---|---|
| `POSTGRES_USER` | `app_user` | Uygulama veritabanı kullanıcısı |
| `POSTGRES_PASSWORD` | serbest | **Üretimde mutlaka değiştirin.** İlk açılışta oluşturulur; sonradan değiştirmek için `--sifirla` gerekir |
| `KEYCLOAK_DB_USER` | `keycloak` | Keycloak'ın veritabanı kullanıcısı |
| `KEYCLOAK_DB_PASSWORD` | serbest | Aynı uyarı |

### Keycloak

| Alan | Değer | Açıklama |
|---|---|---|
| `KEYCLOAK_CLIENT_SECRET` | `12345678` | **`realm-export.json` içindekiyle AYNI olmak zorunda.** Farklı olursa giriş sessizce başarısız olur |
| `KEYCLOAK_CLIENT_ID` | `Yayın_App` | Realm'deki client adı — değiştirilirse realm dosyası da değişmeli |
| `KEYCLOAK_REALM` | `YayinYonetimi` | Realm adı |
| `KEYCLOAK_ADMIN` / `_PASSWORD` | `admin` / `admin` | Keycloak yönetim konsolu girişi. **Üretimde değiştirin** |

### Nesne depolama

| Alan | Örnek | Açıklama |
|---|---|---|
| `MINIO_ROOT_USER` | `minio_admin` | MinIO erişim anahtarı |
| `MINIO_ROOT_PASSWORD` | serbest | **En az 8 karakter.** İlk açılışta belirlenir |

### Tarayıcıda açılan adresler ⚠️

Bu üçü **en sık hata kaynağı**. `localhost` yazılırsa ağdaki başka bir cihaz
onu kendi makinesi sanar; yayın da indirme de çalışmaz.

| Alan | Örnek | Açıklama |
|---|---|---|
| `MEDIAMTX_HLS_BASE_URL` | `http://192.168.1.20:8888` | Oynatıcının bağlanacağı adres. Makinenin **LAN IP'si** olmalı |
| `MINIO_PUBLIC_URL` | `http://192.168.1.20:9000` | İmzalı adresler bununla üretilir. S3 imzası Host başlığını kapsadığı için **sonradan değiştirilemez** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://192.168.1.20:3000` | Virgülle ayrılır. `/…/` ile sarılan girdi **regex** sayılır: `/https?://192\.168\.1\.[0-9]+(:[0-9]+)?/`. Regex içinde **virgül kullanmayın** — liste ayracı |

> IP değişirse bu üçü de değişmeli. En kolayı: `.env`'i silip `./baslat.sh`.

### Arayüze nasıl erişilir

Üç yol var; hangisinin çalıştığı `.env`'deki `PORT_FRONTEND` ve `PUBLIC_HOST`
alanlarına bağlı.

| Adres | Ne zaman çalışır |
|---|---|
| `http://localhost` | `PORT_FRONTEND=80` ise — **her zaman** |
| `http://<sunucu-ip>` | Aynı ağdaki diğer cihazlardan |
| `http://yayın.com` | `PUBLIC_HOST` dolu **ve** hosts satırı eklenmişse |

`PORT_FRONTEND=3000` ise adreslerin sonuna `:3000` eklenir.

```bash
grep -E '^PORT_FRONTEND=|^PUBLIC_HOST=' .env    # hangi düzendesin
./alan-adi-kur.sh                                # hosts satırını gösterir
./alan-adi-kur.sh --yaz                          # kendi makinene ekler
```

> **`yayın.com` açılmıyorsa** neredeyse her zaman sebep hosts satırının
> eksikliğidir. `./baslat.sh` bunu açılışta uyarıyor.

---

### HLS adresleri ve yollar

Her kanalın MediaMTX'te bir **path**'i var (`kanal1` gibi) ve tüm adresler
ondan türüyor.

| Adres | Ne verir |
|---|---|
| `/hls/<path>/index.m3u8` | **Kaynak kalitesinde** yayın — arayüzün varsayılanı |
| `/hls/<path>_720p/index.m3u8` | Rendition (tanımlıysa) |
| `/hls/<path>_480p/index.m3u8` | Daha düşük rendition |

Örnek — `trt` kanalı, path'i `kanal1`:

```
http://yayın.com/hls/kanal1/index.m3u8         kaynak
http://yayın.com/hls/kanal1_720p/index.m3u8    720p rendition
```

**Vekil olmadan** (alan adı kullanılmıyorsa) aynı adresler MediaMTX'in kendi
portundan:

```
http://<ip>:8888/kanal1/index.m3u8
```

Bu adres `MEDIAMTX_HLS_BASE_URL`'den geliyor ve backend `hlsUrl` alanını
buna göre üretiyor — arayüz adresi kendisi kurmuyor.

#### Diğer protokoller

| Adres | Kim kullanır |
|---|---|
| `rtsp://mediamtx:8554/<path>` | VAD ses çıkarma **ve DVR kaydı** (iç ağ) |
| `http://<ip>:9997/v3/paths/list` | Path durumu — teşhis |

`:8554` **dışarı açılmıyor**; yalnızca compose ağı içinden erişilebilir.

#### Kayıt yolu

Kayıt **diske değil MinIO'ya** yazılıyor:

```
dvr/<kanal>/<YYYY>/<AA>/<GG>/<SS>/<zaman-damgası>.ts
```

Kullanıcı öneki **yok** — klip, ekran görüntüsü ve videonun aksine DVR'ın
sahibi yok; kanalın sürekli sistem kaydı, kimse "üretmiyor".

Kayıt **kaynak kalitesinde**, rendition'a düşürülmeden alınıyor (`-c copy`).
Gerekçesi: rendition üretilmezse eski düzende kayıt klasörü açılıyor ama
içine hiçbir şey yazılmıyordu — kullanıcı dakikalarca kaydettiğini sanıp boş
dönüyordu (`V17`).

---

### Kanal ve radyo listesi

Liste **veritabanında**, bu belgede değil — kanallar arayüzden eklenip
kaldırılıyor ve buraya yazılan her liste ertesi gün eskir.

Anlık listeyi almanın üç yolu:

```bash
# 1) Veritabanından
docker exec postgres psql -U app_user -d yayin_merkezi -c \
  "select name, mediamtx_path, active, dvr_enabled from channels order by name"

docker exec postgres psql -U app_user -d yayin_merkezi -c \
  "select name, mediamtx_path, source_kind from radios order by name"

# 2) MediaMTX'ten — hangileri GERÇEKTEN yayında
curl -s 'http://localhost:9997/v3/paths/list?itemsPerPage=100' \
  | python3 -c "import json,sys;[print(i['name'], i['ready']) for i in json.load(sys.stdin)['items']]"

# 3) API'den (token gerekir)
GET /api/channels
GET /api/radios
```

İkisi arasındaki fark önemli: veritabanı **tanımlı** olanı, MediaMTX
**akan** olanı gösteriyor. Kanal `active=true` olduğu hâlde kaynağı düşmüşse
yalnızca ikincisinde görünür.

---

### Alan adıyla erişim (`yayın.com`)

Varsayılan erişim `http://<ip>:3000`. Alan adıyla açmak için:

```bash
PUBLIC_HOST=yayın.com ./yapilandir.sh --zorla
./alan-adi-kur.sh --yaz          # /etc/hosts satırını ekler (sudo ister)
./baslat.sh
```

`PUBLIC_HOST` verildiğinde üç şey birden değişiyor:

| | Alan adı yokken | Alan adıyla |
|---|---|---|
| `PORT_FRONTEND` | 3000 | **80** (alan adında port yazılmaz) |
| `MEDIAMTX_HLS_BASE_URL` | `http://<ip>:8888` | `http://xn--yayn-nza.com/hls` |
| `CORS_ALLOWED_ORIGINS` | `<ip>:3000` | `http://xn--yayn-nza.com` |

> **Punycode.** `yayın.com` bir IDN alan adı; tarayıcı ağa çıkarken
> `xn--yayn-nza.com`a çevirir. `nginx`'in `server_name`'i, `/etc/hosts`
> satırı ve `.env`'deki adresler **punycode** olmalı — Unicode yazılan
> hiçbir yerde eşleşmez. Script dönüşümü kendisi yapıyor.

**Yayın artık vekil üzerinden geliyor.** `nginx` şunları aynı origin'e
topluyor:

```
/          arayüz (statik)
/api/      backend
/ws/       WebSocket — canlı altyazı
/hls/      MediaMTX yayını
/docs      OpenAPI
```

Tek origin iki şey kazandırıyor: CORS devreye girmiyor ve izleyicinin ayrıca
`8888` portuna erişebilmesi gerekmiyor.

> **MinIO vekilden geçmiyor.** İmzalı adresler S3 v4 imzası kullanıyor ve imza
> **Host başlığını ve yolu** kapsıyor; alt yol altında vekillemek imzayı
> geçersiz kılar. MinIO kendi portundan (`9000`) erişilebilir kalmalı.

#### Her makinede hosts satırı gerekiyor

`hosts` dosyası yereldir — erişecek **her** makinede satır olmalı:

```
192.168.1.20    xn--yayn-nza.com
```

| İşletim sistemi | Dosya |
|---|---|
| Linux / macOS | `/etc/hosts` |
| Windows | `C:\Windows\System32\drivers\etc\hosts` |

`./alan-adi-kur.sh` (parametresiz) doğru satırı yazdırıyor; `--yaz` ile
kendi makinesine ekliyor.

**Kalıcı çözüm** ağdaki DNS sunucusuna bir `A` kaydı eklemek — o zaman hiçbir
makinede hosts düzenlemesi gerekmez.

### Mevcut bir kurulumu yükseltirken — DVR MinIO'ya taşındı

Bu sürümde DVR kaydı **MediaMTX'ten alınıp MinIO'ya taşındı**. Var olan bir
kurulumda üç şey yapılmalı:

**1. `.env`'i güncelleyin.** `DVR_PATH` artık okunmuyor; yerine:

```bash
DVR_BUCKET=dvr
DVR_RETENTION_DAYS=7
DVR_SEGMENT_SECONDS=30
```

**2. İmajları yeniden kurun.** Backend'in tabanı değişti (UBI9 →
`eclipse-temurin`) ve içine ffmpeg girdi:

```bash
./mvnw package
docker compose build backend video-worker
```

**3. Eski kayıtları silin.** Yeni düzen onları okuyamıyor; `V20` göçü boş bir
zaman çizelgesiyle başlıyor. Dosyalar konteyner içinden root olarak yazıldığı
için normal `rm` yetmez:

```bash
docker run --rm -v ./src/main/docker/mediamtx-data:/data alpine \
  rm -rf /data/recordings
docker volume rm yayin-merkezi_mediamtx_recordings 2>/dev/null
```

> **Eski kayıtlar taşınamıyor.** MediaMTX fMP4 yazıyordu, yeni düzen MPEG-TS
> segment bekliyor ve zaman çizelgesi veritabanından geliyor. Dönüştürmek
> mümkün ama tek seferlik bir iş için yazılmadı — arşiv gerekiyorsa
> yükseltmeden önce klip alın.

---

### Başka bir makineye taşırken

Üç şey `.env`'den **gelmiyor**; taşımada gözden kaçan yerler bunlar.

#### 1. `nginx.conf`'taki `server_name` imaja gömülü

```nginx
server_name xn--yayn-nza.com www.xn--yayn-nza.com localhost _;
```

Alan adı değişirse `.env` yetmez — dosyayı düzenleyip **frontend imajını
yeniden kurmak** gerekiyor:

```bash
docker compose build frontend
```

`./yapilandir.sh` uyuşmazlığı fark edip uyarıyor.

> Sondaki `_` hepsini yakaladığı için yanlış `server_name` ile de çalışır;
> yalnızca sanal sunucu ayrımı yapılamaz.

#### 2. `/hls/` vekilliği iki ayarı birlikte gerektiriyor

```nginx
location /hls/ {
    proxy_pass http://mediamtx:8888/;          # sondaki slash: /hls kırpılır
    proxy_redirect ~^https?://[^/]+/(.*)$ /hls/$1;
    proxy_redirect ~^/(.*)$ /hls/$1;           # Location'a önek geri eklenir
}
```

İkisi de şart, ikisi de ölçülerek bulundu:

| Eksik olan | Belirti |
|---|---|
| Sondaki slash | `path 'hls/kanal1' is not configured` |
| `proxy_redirect` | Oynatıcı playlist yerine **HTML** alıyor — "yayında ama bağlanmıyor" |

Sebebi: MediaMTX oturum çerezi için `?cookieCheck=1` ile kendine yönlendiriyor
ve `Location`'ı **kendi gördüğü yola** göre üretiyor. Önek kırpıldığı için
yönlendirme `/hls` olmadan çıkıyor, nginx SPA kuralına düşüp `index.html`
dönüyor.

#### 3. Modeller imaja gömülü

`stt-worker` imajında **1,9 GB model** var (`small` 464 MB + Opus-MT × 3).
Model ya da cihaz değişirse imaj yeniden kurulmalı:

```bash
STT_MODEL=large-v3 STT_DEVICE=cuda docker compose build stt-worker
```

`.env`'de değiştirip yeniden başlatmak **yetmez** — o model imajda yok.

---

### `stt-worker` sürekli yeniden başlıyorsa

Neredeyse her zaman **model belleğe sığmıyor** demek.

```bash
docker inspect -f '{{.State.Status}}' stt-worker     # "restarting" mi
docker logs stt-worker --tail 20
```

Süreç çekirdek tarafından öldürüldüğü için logda net bir hata görünmeyebilir.

| Ortam | Sebep | Çözüm |
|---|---|---|
| GPU | **VRAM yetmiyor** | `STT_MODEL=medium` ya da `STT_COMPUTE_TYPE=int8` |
| CPU | RAM yetmiyor | Aynı — `large-v3` int8 ile ~2 GB, float16 ile ~3 GB ister |

Her iki durumda da **imaj yeniden kurulmalı**:

```bash
docker compose build stt-worker
```

`./baslat.sh` bu döngüyü fark edip aynı öneriyi yazdırıyor.

> **Kapasite ayrı bir konu.** Konteyner ayakta ama altyazı geride kalıyorsa
> bellek değil **işlem gücü** yetmiyordur — bkz.
> [teknik-referans-modul.md §15](docs/teknik-referans-modul.md).

### Host portları

Hepsi `.env`'den ayarlanabilir. Yalnızca **host tarafı** değişir; konteyner içi
portlar sabit ve compose ağında adresler hep aynı kalır (`backend:8081`,
`mediamtx:8888` …). Başka bir uygulama portu tutuyorsa burayı değiştirin.

| Alan | Varsayılan | Servis |
|---|---|---|
| `PORT_FRONTEND` | `3000` | Arayüz (nginx) |
| `PORT_BACKEND` | `8090` | REST API — konteyner içi 8081 |
| `PORT_KEYCLOAK` | `8080` | Kimlik sunucusu |
| `PORT_MINIO_API` | `9000` | Nesne depolama API'si |
| `PORT_MINIO_CONSOLE` | `9001` | MinIO web konsolu |
| `PORT_HLS` | `8888` | HLS yayını — tarayıcı buradan çalar |
| `PORT_RTSP` | `8554` | RTSP |
| `PORT_MEDIAMTX_API` | `9997` | MediaMTX REST API'si |
| `PORT_POSTGRES` | `5433` | Makinede kurulu PostgreSQL 5432'yi tutabildiği için 5433 |
| `PORT_REDIS` | `6379` | Kuyruk bildirimi |

> **Üçü tarayıcıya da yazılı.** Bunları değiştirirseniz yukarıdaki adresleri de
> elden geçirin — uyuşmazlarsa yayın ve indirme *sessizce* kırılır:
>
> | Port | Ayrıca güncellenecek |
> |---|---|
> | `PORT_FRONTEND` | `CORS_ALLOWED_ORIGINS` |
> | `PORT_MINIO_API` | `MINIO_PUBLIC_URL` |
> | `PORT_HLS` | `MEDIAMTX_HLS_BASE_URL` |

### Donanım kodlayıcı

| Alan | Değerler | Açıklama |
|---|---|---|
| `CHANNELS_ENCODER` | `NVENC` \| `VAAPI` \| `YAZILIM` | Kanal rendition'ları — **mediamtx** konteynerinde çalışır |
| `VIDEOS_ENCODER` | aynı | Kütüphane işleri — **video-worker**'da çalışır. Ayrı, çünkü iki konteyner farklı aygıtlara erişebilir |
| `CONTAINER_RUNTIME` | `runc` \| `nvidia` | NVIDIA için `nvidia` |
| `NVIDIA_VISIBLE_DEVICES` | boş \| `all` \| `0,1` | Hangi GPU'lar açılacak |
| `NVIDIA_DRIVER_CAPABILITIES` | boş \| `video,compute,utility` | **`video` şart** — yoksa NVENC/NVDEC görünmez |
| `MEDIA_DEVICE` | `/dev/dri:/dev/dri` \| `/dev/null:/dev/null` | mediamtx'e geçirilen aygıt. NVIDIA'da `/dev/dri` olmayabilir, o yüzden `/dev/null` |
| `WORKER_MEDIA_DEVICE` | aynı | video-worker için |

### Depolama: kota ve temizlik

Süreler **gün ya da saat** olarak yazılabilir: `P30D` = 30 gün · `720h` = aynı
süre · `PT12H` = 12 saat · `0` = kapalı. Uygulama açılışta yürürlükteki
politikayı logluyor.

| Alan | Varsayılan | Açıklama |
|---|---|---|
| `STORAGE_USER_QUOTA_BYTES` | `21474836480` (20 GB) | Kullanıcı başına. Klip + kayıt + ekran görüntüsü + video toplamı. `0` = sınırsız |
| `STORAGE_CLIP_RETENTION` | `0` | Klip ve kayıt saklama süresi. **Varsayılan kapalı** — baskıyı kota kursun, saat değil |
| `STORAGE_SCREENSHOT_RETENTION` | `0` | Aynı |
| `STORAGE_FAILED_CLIP_RETENTION` | `P7D` | Başarısız klipler. Dosyaları zaten yok, yalnızca sebep gösterilsin diye bekletiliyor |
| `STORAGE_SWEEP_INTERVAL` | `1h` | Süpürücü aralığı |
| `SCREENSHOTS_BUCKET` | `ekran-goruntuleri` | Galeri kovası |
| `SCREENSHOTS_MAX_BYTES` | `10485760` (10 MB) | Tek kare üst sınırı |

> **Kota dolunca yeni iş reddedilir, var olan silinmez.** Sessizce silmek
> kullanıcının verisini habersiz yok etmek olurdu; ne silineceğine kullanıcı
> karar vermeli.

### DVR (geriye sarma kaydı)

Kayıt **host diskine ve docker volume'üne yazılmıyor**; doğrudan MinIO'ya
akıtılıyor. `DVR_PATH` kaldırıldı.

| Alan | Varsayılan | Açıklama |
|---|---|---|
| `DVR_BUCKET` | `dvr` | Ayrı kova. MinIO'nun silme kuralları kova bazlı; kliplerle aynı kovada olsaydı onlar da silinirdi |
| `DVR_RETENTION_DAYS` | `7` | Saklama süresi. Uygulama bunu kovaya kural olarak yazıyor, **silmeyi MinIO yapıyor** |
| `DVR_SEGMENT_SECONDS` | `30` | Parça süresi. Kısa → çok nesne; uzun → çökmede çok kayıp |
| `DVR_SYNC_INTERVAL` | `10s` | Yayın durumunun yoklanma sıklığı. Manuel kayıt en fazla bu kadar gecikmeyle başlar |

**Yer ihtiyacı MinIO'da:** 16 kanal × 7 gün × 3 Mbps ≈ **3,6 TB**.

### İsteğe bağlı ince ayar

`.env`'de bulunmuyorlar; gerekirse eklenir.

| Alan | Varsayılan | Açıklama |
|---|---|---|
| `CHANNELS_MAX_ACTIVE` | `16` | Aynı anda yayında olabilecek kanal. Donanım sınırını yansıtır |
| `RADIOS_MAX_ACTIVE` | `32` | Radyolar ayrı sayılır — maliyeti aynı ölçekte değil (~%2.6 CPU) |
| `RADIOS_DEFAULT_BITRATE` | `128k` | Köprü modunda üretilen AAC bit hızı |
| `CHANNELS_HLS_MAX_SEGMENT_BYTES` | `3670016` | Master playlist'ten seçilecek varyantın segment üst sınırı. MediaMTX ~4 MB'ta düşüyor |
| `CHANNELS_GPU_FULL_PIPELINE` | `true` | NVENC'te kareler GPU'da kalsın mı. Sürücü sorun çıkarırsa `false` |
| `CLIPS_MAX_DURATION_MINUTES` | `120` | Klip üst sınırı |
| `CLIPS_CONCURRENCY` | `2` | Eşzamanlı klip. Yüksek tutulursa canlı yayın etkilenir |
| `CLIPS_URL_TTL` | `15` | İmzalı indirme adresi ömrü (dakika) |
| `VIDEOS_MAX_UPLOAD_BYTES` | `5368709120` | 5 GB. S3 tek PUT sınırı |
| `VIDEOS_STREAM_TTL_HOURS` | `6` | İzleme adresi ömrü. Kısa olursa video **ortasında** ileri sarma 403 alır |
| `VIDEOS_ALLOWED_EXTENSIONS` | `mp4,webm,mov,m4v` | Erken süzgeç; gerçek doğrulama işçide ffprobe ile |
| `VIDEOS_CONCURRENCY` | `2` | Eşzamanlı video işi |
| `VIDEOS_PREVIEW_SECONDS` | `5` | Önizleme klibi süresi |
| `VIDEOS_FFMPEG_TIMEOUT` | `30` | ffmpeg üst sınırı (dakika) |

---

## İçindekiler

- [Ne yapar](#ne-yapar)
- [Mimari](#mimari)
- [Nasıl ayağa kaldırılır](#nasıl-ayağa-kaldırılır)
- [Akış: bir kanal nasıl yayına girer](#akış-bir-kanal-nasıl-yayına-girer)
- [MediaMTX'e binen yük](#mediamtxe-binen-yük)
- [Depolama hesabı](#depolama-hesabı)
- [Bilinçli tasarım kararları](#bilinçli-tasarım-kararları)
- [Geliştirme](#geliştirme)

---

## Ne yapar

| Yetenek | Durum |
|---|---|
| Manuel kayıt (kayda başla / durdur) — **DVR kapalı kanallarda da** | ✅ |
| Planlı kayıt (geçmiş veya gelecek saat aralığı için kayıt emri) | ✅ |
| Canlı yayından kare yakalama ve kronolojik galeri | ✅ |
| Kullanıcı başına depolama kotası ve temizlik politikası | ✅ |
| Radyo yayınları (Icecast köprüsü dahil) | ✅ |
| Video kütüphanesi (yükleme, küçük resim, önizleme klibi) | ✅ |
| Keycloak ile kimlik doğrulama ve rol bazlı yetki | ✅ |
| Kullanıcı yönetimi (ekleme, rol atama, şifre sıfırlama, silme) | ✅ |
| Kanal CRUD, en fazla 16 eşzamanlı yayın | ✅ |
| Yeniden başlatmada kanalların kendiliğinden ayağa kalkması | ✅ |
| Çoklu izleme (4x4'e kadar mozaik) + büyük ekran | ✅ |
| Sayfa değiştirince yayının kesilmemesi | ✅ |
| 7 günlük DVR ve zaman çizelgesi üzerinden geriye sarma | ✅ |
| Zaman çizelgesinden aralık seçip klip çıkarma | ✅ |
| İzleyici kimlik doğrulaması (HLS erişimi) | ❌ bkz. `notlar.md` |
| Uyarlanabilir bit hızı (transcode) | ❌ kaynak ne veriyorsa o dağıtılır |

### Roller

Keycloak'ta `Yayın_App` client'ının **client rolleri** olarak tanımlıdır
(realm rolü değil):

| Rol | Yetki |
|---|---|
| `Yönetici` | Her şey — kullanıcı yönetimi dahil; başkasının klibini, videosunu, kaydını görür ve yönetir |
| `Moderatör` | Kanal ve radyo yönetimi, video yükleme; kullanıcı yönetemez |
| `İzleyici` | Kendi adına kayıt, klip ve kare yakalar; kütüphaneyi görür ama **video yükleyemez** |

#### Görünürlük kuralı

İki farklı model var; karıştırmak kolay:

| İçerik | Kim görür | Kim üretir |
|---|---|---|
| Klip, kayıt, ekran görüntüsü | **yalnızca sahibi** (+ yönetici) | giriş yapmış herkes |
| Video kütüphanesi | **herkes** | Yönetici, Moderatör |

Klip ve ekran görüntüsü kişisel kayıt içeriği — varsayılan kapalı olmalı.
Kütüphane ise paylaşılan bir arşiv. Kütüphanede düzenleme ve silme yine
sahibine özel; yönetici tümüne dokunabilir.

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
        │                        │                            │
        │                        │  ③ RTSP                    │
        │                        ├──────────┐                 │
        │                        │          ▼                 │
        │                        │    video-worker            │
        │                        │    ffmpeg -c copy          │
        │                        │          │ 30 sn'lik TS    │
                          Backend :8081     │                 │  yönetim
                                 │  ◄───────┼─────────────────┤
                    ┌────────────┼──────────┼──┐              │
                    ▼            ▼          ▼  ▼              │
              PostgreSQL     Keycloak       MinIO ◄────────────┘
             kanal/klip      kimlik    klip + DVR    imzalı indirme
             + segment                  segmentleri
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
| Keycloak | 8080 | Kimlik |
| PostgreSQL | 5433 | Uygulama verisi |
| MinIO | 9000 / 9001 | Klip, video ve **DVR segmentleri** |
| Redis | 6379 | Klip/video iş bildirimi (`BLMOVE`) |

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

MINIO_ROOT_USER=minio_admin
MINIO_ROOT_PASSWORD=<güçlü-bir-şifre>
MINIO_PUBLIC_URL=http://localhost:9000

MEDIAMTX_HLS_BASE_URL=http://localhost:8888
CORS_ALLOWED_ORIGINS=http://localhost:3000

# 7 günlük DVR MinIO'da tutuluyor — bkz. Depolama hesabı
DVR_RETENTION_DAYS=7
```

> `.env` compose dosyasının bulunduğu dizinde aranır. Kökte tutmak
> istiyorsanız `src/main/docker/.env` bir symlink olmalı:
> `ln -s ../../../.env src/main/docker/.env`

`MEDIAMTX_HLS_BASE_URL` ve `MINIO_PUBLIC_URL` **tarayıcının çözebileceği**
adresler olmalı. Container içi isimler (`http://mediamtx:8888`) yazılırsa
oynatıcı yayını bulamaz, indirme çalışmaz.

### 2. Backend'i paketleyin

> **Sıra önemli.** `docker compose build backend` imajı `target/quarkus-app`'tan
> kopyalıyor, Maven'ı kendisi çalıştırmıyor. Paketlemeyi atlarsan imaj eski
> kodla kurulur ve yeni migration'lar *sessizce* uygulanmaz.

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
| Kullanıcı | `admin1` / `12345678` (realm-export.json'da gömülü, kalıcı) |
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

5. dvrEnabled ise video-worker RTSP'den kayıt almaya başlar
   → ffmpeg -c copy -f mpegts → 30 sn'lik segmentler → MinIO
   → dvr/<kanal>/<YYYY>/<AA>/<GG>/<SS>/<zaman>.ts
   (en fazla DVR_SYNC_INTERVAL kadar gecikmeyle)
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
   ├─ Aralığı kapsayan DVR segmentlerini MinIO'dan çeker
   ├─ ffmpeg ile birleştirip kırpar (-c copy, yeniden kodlama yok)
   ├─ MinIO'ya AKIŞ HALİNDE yazar (belleğe almadan)
   └─ HAZIR + object_key + size_bytes

4. Kullanıcı Klipler sayfasından izler veya indirir
   → GET /api/clips/{id}/links → imzalı MinIO adresleri
```

### Kayıt: manuel ve planlı

Üçünün de **ürettiği şey aynı**: bir klip. Ayrıldıkları yer, aralığın nasıl
belirlendiği.

| | Aralık nasıl belirlenir | Uç |
|---|---|---|
| Aralık seçimi | çizelgeden sürükleyerek | `POST /api/channels/{id}/clips` |
| Manuel kayıt | başlat → durdur, bitiş **o anda** | `POST …/clips/kayit` |
| Planlı kayıt | başlangıç ve bitiş **baştan** verilir | `POST …/planli-kayitlar` |

Planlı kayıtta aralık geçmişte, şu anda ya da **gelecekte** olabilir. Tamamen
geçmişteyse beklenmez, klip hemen açılır; aksi halde emir kuyruğa girer ve
30 saniyede bir dönen zamanlayıcı yürütür:

```
BEKLIYOR ──(başlangıç geldi)──► KAYITTA ──(bitiş geçti)──► TAMAMLANDI
    │                              │                          │
    └──────── IPTAL ───────────────┘                    veya BASARISIZ
```

Emirler veritabanında; sunucu kapalıyken aralığı geçen bir emir açılışta
toplanır. Bellekte zamanlanmış görev olsaydı yeniden başlatmada sessizce
düşerdi.

#### DVR kapalı kanallarda kayıt

Yeni bir kayıt mekanizması yok. Klip hattı, MediaMTX'in diske yazdığı bir
aralığı MinIO'ya kopyalıyor — arada ffmpeg bile yok, saf bayt aktarımı. Eksik
olan tek şey, **o aralığın diske yazılmış olması**.

Kanalın geriye sarması kapalıysa kayıt **iş süresince açılıyor**, bitince geri
kapatılıyor. Kullanıcıdan önce kanal ayarını değiştirmesini istemek, tam da
kaydedilmek istenen anın kaçırılması demekti.

Manuel ve planlı kayıt aynı kanalda çakışabildiği için kapatma kararı tek bir
soruya bakıyor: *bu kanalda kaydı kendisi açmış başka bir iş kaldı mı?* Aksi
halde biri bitince diğerinin aralığı ortasından kesilirdi.

### Nesne depolama düzeni

```
<kullanıcı>/<kanal>/<id>.mp4        klip ve kayıt
<kullanıcı>/<kanal>/<id>.jpg        ekran görüntüsü
<kullanıcı>/<uuid>/kaynak.<uzantı>  kütüphane videosu (kanalı yok)
```

Kullanıcı en üstte çünkü içerik zaten kullanıcıya özel. Kovaya konsoldan bakan
biri de aynı ayrımı görsün; kanala göre gruplamak tek bir kullanıcının
dosyalarını onlarca kanala dağıtıyordu.

Klasör adı okunabilirlik için kullanıcı adından türetiliyor — kimlik olarak
değil. Türkçe harfler sadeleştiriliyor (`buğra → bugra`, `Ayşe Öz → ayse-oz`).
**Bilinen sınır:** Keycloak'ta kullanıcı adı değişirse yeni dosyalar yeni
klasöre gider, eskiler yerinde kalır. Anahtar veritabanında saklandığı için
hiçbiri kaybolmaz, yalnızca iki klasöre dağılır.

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
6 Mbps'te **8,7 TB** gerekir. Bu alan artık **MinIO'nun deposunda** gerekiyor
— host diskine hiçbir şey yazılmıyor.

> **Depo dolarsa kayıt yazılamaz ve kötü senaryoda canlı yayın da etkilenir.**
> Yükleme yavaşlarsa ffmpeg borusu dolar ve RTSP tamponu taşabilir; bu durum
> loglanıyor:
> ```
> WARN  DVR yüklemesi geride kalıyor (<kanal>): segment 47000 ms sürdü,
>       hedef 30000 ms. MinIO yavaşsa canlı yayın etkilenebilir.
> ```
> `DVR_RETENTION_DAYS` her zaman deponun gerçek kapasitesiyle tutarlı olmalı.
> Silmeyi MinIO'nun yaşam döngüsü kuralı yapıyor.

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

### Doğruluk veritabanında, bildirim Redis'te

Klip işinin kalıcı hali `clips` tablosunda; Redis yalnızca **"yeni iş var"**
haberini taşır. İşçi işi `FOR UPDATE SKIP LOCKED` ile tablodan talep eder —
tekilliği garanti eden adım budur, Redis değil.

**Neden ikisi birden:** iş zaten tabloda kalıcı olmak zorunda, dolayısıyla
kuyruğu tamamen Redis'e taşımak iki doğruluk kaynağı demek olurdu; biri
başarılı diğeri başarısız olduğunda ya kaybolan ya iki kez işlenen işler
çıkardı. Redis'siz tasarımın bedeli ise yoklama gecikmesiydi — iş hazır
olmasına rağmen bir sonraki taramaya kadar bekliyordu.

Bu ayrım sayesinde **Redis tamamen çökse bile hiçbir iş kaybolmaz**; gecikme
süpürücünün aralığına (`clips.sweep-interval`, varsayılan 60 sn) düşer.

İki liste kullanılıyor:

| Liste | Ne zaman |
|---|---|
| `bekleyen` | yeni iş buraya itilir |
| `isleniyor` | işçi iş aldığında atomik olarak buraya taşınır |

Taşıma `BLMOVE` ile **tek adımda**. Basit bir `BRPOP` kullanılsaydı, işçi işi
aldıktan hemen sonra çökerse iş hiçbir listede olmaz ve Redis tarafında iz
bırakmadan kaybolurdu.

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

# Kayıt aralıkları (kimlik gerektirir)
curl -s -H "Authorization: Bearer <token>" \
  "localhost:8090/api/channels/<id>/dvr/timeline?from=<iso>&to=<iso>" \
  | python3 -m json.tool

# DVR segmentleri MinIO'da birikiyor mu
docker run --rm --network yayin-merkezi_yayin-merkezi --entrypoint sh minio/mc -c \
  'mc alias set t http://minio:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD >/dev/null &&
   mc ls --recursive t/dvr | tail -5'
```

### Ayrıntılı belgeler

| Belge | İçerik |
|---|---|
| [docs/yayin-mimarisi.md](docs/yayin-mimarisi.md) | Akış mimarisi, gecikme bütçesi, diske yazılan dosyalar |
| [docs/faz2-dvr-plani.md](docs/faz2-dvr-plani.md) | DVR ve klip çıkarma planı, depolama hesabı |
