# Yayın Merkezi

Çok kanallı canlı TV izleme, 7 günlük geriye sarma (DVR), kayıttan klip
çıkarma ve canlı çok dilli altyazı (Whisper + Marian, Triton üzerinde).

Kaynak yayınlar (HLS / RTSP / RTMP / SRT / UDP) MediaMTX'e alınır, HLS olarak
izleyicilere dağıtılır. Yönetim/kimlik/klip Quarkus backend'de; arayüz
React + shadcn/ui.

---

## Nasıl çalıştırılır

```bash
./gereksinimler.sh   # eksik olanı söyler, hiçbir şey kurmaz (--kur ile kurar)
./yapilandir.sh      # donanımı bulup .env üretir
./baslat.sh          # imajları kurar (Triton dahil, ~51 GB, ilk seferde yavaş) ve başlatır
```

`yapilandir.sh` GPU/VAAPI/yazılım kodlayıcıyı ve NVIDIA varlığını otomatik
tespit edip `.env`'i ona göre doldurur. Yanlış tespit ederse `.env`'i elle
düzeltip `./baslat.sh`'i tekrar çalıştırın — `.env` varsa üzerine yazılmaz.

```bash
./yapilandir.sh --zorla   # .env'i yeniden üret (var olanın üzerine)
./baslat.sh --yeniden     # imajları sıfırdan kurarak başlat
./baslat.sh --durdur      # durdur (veri korunur)
./baslat.sh --sifirla     # durdur ve TÜM VERİYİ sil — GERİ ALINAMAZ
```

Compose dosyası kökte, `-f` gerekmiyor: `docker compose up -d` / `down` /
`logs -f <servis>` doğrudan çalışır.

### Elle kurulum (`yapilandir.sh`/`baslat.sh` kullanmadan)

`.env`'i kendin yazmak istersen, proje kökünde şu dosyayı oluştur (`<IP>`
yerine makinenin LAN IP'sini yaz — `hostname -I` ile bulunur):

```bash
cat > .env <<'EOF'
QUARKUS_PROFILE=prod
IMAGE_TAG=latest

# --- Veritabanı / Keycloak / MinIO — üretimde MUTLAKA değiştir ---
POSTGRES_USER=app_user
POSTGRES_PASSWORD=degistir
KEYCLOAK_DB_USER=keycloak
KEYCLOAK_DB_PASSWORD=degistir
KEYCLOAK_CLIENT_SECRET=12345678
KEYCLOAK_CLIENT_ID=Yayın_App
KEYCLOAK_REALM=YayinYonetimi
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=degistir
MINIO_ROOT_USER=minio_admin
MINIO_ROOT_PASSWORD=degistir_en_az_8_karakter

# --- Tarayıcıda açılan adresler ---
MINIO_PUBLIC_URL=http://<IP>:9000
MEDIAMTX_HLS_BASE_URL=/hls
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://<IP>:3000
PUBLIC_HOST=

# --- Host portları — varsayılanlar, çakışma yoksa dokunma ---
PORT_FRONTEND=3000
PORT_BACKEND=8090
PORT_KEYCLOAK=8080
PORT_MINIO_API=9000
PORT_MINIO_CONSOLE=9001
PORT_HLS=8888
PORT_RTSP=8554
PORT_MEDIAMTX_API=9997
PORT_POSTGRES=5433
PORT_REDIS=6379
PORT_PROMETHEUS=9090
PORT_GRAFANA=3001
PORT_DCGM=9400
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=admin

# --- Donanım kodlayıcı — NVIDIA/NVENC önerilir, yoksa VAAPI ya da YAZILIM ---
CHANNELS_ENCODER=NVENC
VIDEOS_ENCODER=NVENC
CONTAINER_RUNTIME=nvidia
NVIDIA_VISIBLE_DEVICES=all
NVIDIA_DRIVER_CAPABILITIES=video,compute,utility
MEDIA_DEVICE=/dev/null:/dev/null
WORKER_MEDIA_DEVICE=/dev/null:/dev/null

# --- Depolama: kota ve temizlik ---
STORAGE_USER_QUOTA_BYTES=21474836480
STORAGE_CLIP_RETENTION=0
STORAGE_SCREENSHOT_RETENTION=0
STORAGE_FAILED_CLIP_RETENTION=P7D
STORAGE_SWEEP_INTERVAL=1h

# --- Canlı altyazı: VAD (Java, video-worker) ---
VAD_ENABLED=true
VAD_MODEL_PATH=/models/silero_vad.onnx
VAD_MODEL_VERSION=v5
VAD_MAX_CHANNELS=20
VAD_SEGMENT_DIR=/vad-bolutler
VAD_MAX_SEGMENT_MS=4000
VAD_MIN_SILENCE_MS=400
VAD_MIN_EMIT_MS=0
VAD_OVERLAP_MS=800
VAD_STT_ENABLED=true

# --- Canlı altyazı: Triton (Whisper + Marian, GPU) ---
# small ile başla, mimariyi doğrula; kalite yetmezse large-v3'e geç ama
# ÖNCE WHISPER_INSTANCES=1'e düşür (6 GB kartta 2 kopya sığmaz).
STT_MODEL=small
STT_DEVICE=cuda
STT_COMPUTE_TYPE=int8_float16
STT_BEAM_SIZE=1
STT_MAX_CONCURRENCY=6
# SINIRSIZ liste -- "tr,de,ru,fr,es,..." gibi istenen kadar dil eklenebilir,
# ama her kod icin asagida MARIAN_MODELS'ta bir karsiligi OLMAK ZORUNDA.
STT_TARGET_LANGS=tr,de,ru
# ZORUNLU: STT_TARGET_LANGS'taki HER dil icin "kod=repo" eslemesi -- eksik
# dil icin build hata verip durur, repo adi TAHMIN EDILMIYOR. Format:
# "kod=repo,kod2=repo2". ("tr" icin standart opus-mt-en-tr artik herkese
# acik degil (401), bu yuzden daha buyuk tc-big surumu kullaniliyor.)
MARIAN_MODELS=tr=Helsinki-NLP/opus-mt-tc-big-en-tr,de=Helsinki-NLP/opus-mt-en-de,ru=Helsinki-NLP/opus-mt-en-ru
# CTranslate2 kuantizasyonu (17 Agustos, Marian ONNX'ten CTranslate2'ye
# tasindi -- bkz. asagidaki "Canli altyazi" bolumu): fp16 -> float16
# (GPU-native, kucuk) | fp32 -> float32. .env'deki isim degismedi,
# export_models.py bunu CTranslate2'nin kendi adlandirmasina ceviriyor.
MARIAN_EXPORT_DTYPE=fp16
STT_RUNTIME=nvidia
TRITON_URL=http://triton:8000
PORT_TRITON_HTTP=8100
PORT_TRITON_METRICS=8002
WHISPER_INSTANCES=2
# Format: "kod=sayi,kod2=sayi2" -- STT_TARGET_LANGS'taki her dil icin ayri
# bir *_INSTANCES degiskeni GEREKMIYOR artik. Listede olmayan dil
# MARIAN_INSTANCES_DEFAULT'u kullanir. REBUILD gerekmez, bkz. asagidaki bolum.
MARIAN_INSTANCES=
MARIAN_INSTANCES_DEFAULT=1

# --- Altyazı bütçesi ---
ALTYAZI_BUTCE_MS=8000
ALTYAZI_RAPOR_ARALIGI=60s
ALTYAZI_HLS_GERIDE=5

MEDIAMTX_RTSP_URL=rtsp://mediamtx:8554
EOF
```

Alanların ne işe yaradığı aşağıdaki **`.env` alanları** bölümünde. GPU yoksa
`CHANNELS_ENCODER=YAZILIM`, `STT_DEVICE=cpu`, `STT_RUNTIME=runc`,
`CONTAINER_RUNTIME=runc` yapıp GPU'ya özgü satırları (`NVIDIA_*`,
`WHISPER_INSTANCES` dışındaki `*_INSTANCES` hâlâ geçerli) boş bırak.

Sonra build + başlat:

```bash
docker compose build              # hepsini kur — triton ~51 GB, en uzun süren
docker compose up -d              # hepsini başlat

# tek tek de olur, sıra önemli değil (depends_on zaten veritabanını bekletir):
docker compose build backend frontend video-worker mediamtx triton
docker compose up -d postgres keycloak-postgres keycloak minio redis mediamtx
docker compose up -d triton backend video-worker frontend
docker compose up -d prometheus grafana dcgm-exporter   # izleme, opsiyonel
```

`docker compose logs -f triton` ile Whisper/Marian yükleme durumunu,
`docker compose ps` ile hangi servisin `healthy`/`unhealthy` olduğunu izle.

### Adresler

| | |
|---|---|
| Arayüz | http://localhost:3000 |
| API belgesi | http://localhost:8090/docs |
| Keycloak | http://localhost:8080 — `admin` / `admin` |
| MinIO konsolu | http://localhost:9001 |
| Grafana | http://localhost:3001 — `admin` / `admin` |

Uygulama kullanıcılarının ilk şifresi **12345678**.

---

## `.env` alanları

`.env`, `yapilandir.sh` tarafından üretilir ve gitignore'da — kimse elle
yazmaz, gerekirse silinip yeniden üretilir.

### Kimlik ve veritabanı

| Alan | Açıklama |
|---|---|
| `POSTGRES_USER` / `_PASSWORD` | Uygulama veritabanı. **Üretimde değiştirin** — ilk açılışta oluşturulur, sonradan değiştirmek `--sifirla` ister |
| `KEYCLOAK_DB_USER` / `_PASSWORD` | Keycloak'ın kendi veritabanı |
| `KEYCLOAK_CLIENT_SECRET` | `realm-export.json` içindekiyle **AYNI olmak zorunda** — farklıysa giriş sessizce başarısız olur |
| `KEYCLOAK_ADMIN` / `_PASSWORD` | Keycloak yönetim konsolu |
| `MINIO_ROOT_USER` / `_PASSWORD` | MinIO erişim anahtarı, en az 8 karakter |

### Tarayıcıda açılan adresler — en sık hata kaynağı

`localhost` yazılırsa ağdaki başka bir cihaz onu kendi makinesi sanar, yayın
da indirme de çalışmaz.

| Alan | Açıklama |
|---|---|
| `MEDIAMTX_HLS_BASE_URL` | Oynatıcının bağlandığı adres — makinenin **LAN IP'si** olmalı |
| `MINIO_PUBLIC_URL` | İmzalı indirme adresleri bundan üretilir — S3 imzası Host başlığını kapsadığı için **sonradan değiştirilemez** |
| `CORS_ALLOWED_ORIGINS` | Virgülle ayrılır. `/…/` ile sarılan girdi regex sayılır, regex içinde virgül kullanmayın |
| `PUBLIC_HOST` | Doluysa (`yayın.com` gibi) frontend 80'e alınır, `./alan-adi-kur.sh --yaz` ile hosts satırı eklenir |

IP değişirse üçü de değişmeli — en kolayı `.env`'i silip `./baslat.sh`.

### Donanım kodlayıcı

`CHANNELS_ENCODER`/`VIDEOS_ENCODER` = `NVENC` \| `VAAPI` \| `YAZILIM`.
NVENC için `CONTAINER_RUNTIME=nvidia` + `NVIDIA_VISIBLE_DEVICES=all` +
`NVIDIA_DRIVER_CAPABILITIES=video,compute,utility`; VAAPI için
`CONTAINER_RUNTIME=runc` + `MEDIA_DEVICE=/dev/dri:/dev/dri`. Donanım kodlayıcı
yoksa yazılımda ölçülen fark kanal başına **%14 yerine %142 CPU**.

### Depolama: kota ve temizlik

| Alan | Açıklama |
|---|---|
| `STORAGE_USER_QUOTA_BYTES` | Kullanıcı başına depolama kotası |
| `STORAGE_CLIP_RETENTION` / `_SCREENSHOT_RETENTION` | `P30D`/`720h` gibi, `0` = kapalı — varsayılan silinmiyor |
| `STORAGE_FAILED_CLIP_RETENTION` | Başarısız klip artıklarının ömrü |
| `DVR_PATH` | Üretimde büyük diske gösterin — 16 kanal × 7 gün × 6 Mbps ≈ 7,3 TB |

### Canlı altyazı (VAD + Triton)

| Alan | Açıklama |
|---|---|
| `VAD_ENABLED` | Altyazı hattının anahtarı. Kapatmak: `false` |
| `VAD_MAX_SEGMENT_MS` | Bölüt penceresi — gecikmenin en büyük parçası, kısaltmak Whisper'a bırakılan bağlamı azaltır |
| `STT_MODEL` | `tiny`\|`base`\|`small`\|`medium`\|`large-v3` — model değişince **Triton yeniden kurulmalı** (`docker compose build triton`) |
| `STT_DEVICE` / `STT_COMPUTE_TYPE` | `cpu`\|`cuda`, `int8`\|`int8_float16`\|`float16` |
| `STT_TARGET_LANGS` | Hedef diller — **sınırsız sayıda**, virgülle ayrılmış (`tr,de,ru,fr,...`). Whisper İngilizce pivot ürettiği için her dil `EN → X` yönünde tek bir Marian modeli. Backend, video-worker ve Triton'da **aynı** liste olmalı |
| `MARIAN_MODELS` | **Zorunlu.** `STT_TARGET_LANGS`'taki her dil için model repo'su (`kod=repo,...`) — eksik dil için build hata verip durur, varsayılan kalıp tahmin edilmez |
| `TRITON_URL` | Java → Triton adresi, container-içi her zaman `http://triton:8000` |
| `WHISPER_INSTANCES`, `MARIAN_INSTANCES`, `MARIAN_INSTANCES_DEFAULT` | Model başına paralel GPU kopyası (`MARIAN_INSTANCES` formatı: `kod=sayı,...`) — **rebuild gerekmez**, bkz. aşağıdaki bölüm |
| `ALTYAZI_HLS_GERIDE` | Altyazının gerçek bütçesi: `bütçe = ALTYAZI_HLS_GERIDE × bölüt süresi`. Kural: **bütçe ≥ p95 gecikme**, p95 `docker compose logs backend \| grep "ALTYAZI KAPSAMA"` |
| `ALTYAZI_BUTCE_MS` | Yalnızca rapor satırı için — **hiçbir şeyi düşürmez** |

> **Sıfır yazmayın** (`ALTYAZI_HLS_GERIDE`): MediaMTX `PART-HOLD-BACK=0.5`
> ilan ediyor, hls.js sıfır yerine onu kullanır ve bütçe yarım saniyeye düşer.

#### Yeni bir altyazı dili ekleme — adım adım

Bu bölümü, projeyi hiç bilmeyen biri bile takip edip çalışır hale
getirebilsin diye baştan sona yazdık. Örnek: **Fransızca (`fr`)** ekleyelim.

**1. `.env`'de üç satırı düzenleyin:**

```bash
# Hedef dil listesine kodu ekleyin (virgülle ayrılmış, sıra önemli değil):
STT_TARGET_LANGS=de,ru,es,fr

# Aynı kod icin Hugging Face model repo'sunu ZORUNLU olarak ekleyin --
# eklenmezse build hata verip durur, hicbir repo adi tahmin edilmez.
# Cogu dil icin kalip: Helsinki-NLP/opus-mt-en-<kod>
MARIAN_MODELS=de=Helsinki-NLP/opus-mt-en-de,ru=Helsinki-NLP/opus-mt-en-ru,es=Helsinki-NLP/opus-mt-en-es,fr=Helsinki-NLP/opus-mt-en-fr

# (Opsiyonel) Bu dile ozel GPU kopya sayisi vermek isterseniz -- vermezseniz
# MARIAN_INSTANCES_DEFAULT (varsayilan 1) kullanilir, hicbir sey yazmaniza
# GEREK YOK:
# MARIAN_INSTANCES=de=1,fr=2
```

> **Model repo'sunu nereden bulacağım?** `https://huggingface.co/Helsinki-NLP`
> adresinde `opus-mt-en-<dil kodu>` arayın (ör. `opus-mt-en-fr`). Bazı diller
> (örn. `tr`) standart isimle artık herkese açık değil — o zaman aynı model
> ailesinin `tc-big` gibi başka bir varyantını kullanın
> (`tr=Helsinki-NLP/opus-mt-tc-big-en-tr` gibi, `.env`'deki mevcut örneğe bakın).

**2. Triton'ı yeniden kurun ve tüm ilgili servisleri yeniden başlatın:**

```bash
docker compose build triton                          # yeni dili export eder (dakikalar surer)
docker compose up -d                                 # .env degisikligi agi yeniden kurabilir,
                                                       # bu yuzden servis adi vermeden TUMUNU calistirin
docker compose restart video-worker backend           # Java tarafinin da yeni listeyi okumasi icin
```

> **Neden `backend`/`video-worker`'ı da yeniden başlatmak gerekiyor:**
> `STT_TARGET_LANGS` Java tarafında yalnızca konteyner **açılışında** bir kez
> okunuyor. Yalnızca Triton'ı güncelleyip Java'yı yeniden başlatmazsanız,
> `video-worker` hâlâ eski dil listesiyle çeviri isteği atmaya çalışır ve
> yeni Triton'da o dil hiç yokmuş gibi (ya da eski dil artık yokmuş gibi)
> hatalar alırsınız.

**3. Doğrulayın:**

```bash
# Triton'da model gerçekten yüklendi mi:
curl -s -X POST localhost:8100/v2/repository/index
# Beklenen: [..., {"name":"marian_en_fr","version":"1","state":"READY"}, ...]

# Backend yeni dili önyüze doğru bildiriyor mu:
curl -s http://localhost:8090/api/ayarlar/oynatici
# Beklenen: {"altyaziDilleri":["de","ru","es","fr"],"hlsGeride":8}
```

`http://localhost:3000` üzerinden bir kanal açıp altyazı dili seçicisinde
yeni dilin göründüğünü kontrol edin — **başka hiçbir şey yapmanıza gerek
yok**, frontend'i yeniden kurmanız (`docker compose build frontend`)
GEREKMİYOR.

#### Bu nasıl çalışıyor — arka plandaki eşleme zinciri

`STT_TARGET_LANGS`'a eklenen bir dil, önyüzdeki altyazı dili seçicisine
kadar şu zincirle ulaşır — hiçbir adımda kod değişikliği gerekmez:

```
.env: STT_TARGET_LANGS=de,ru,es,fr
   → application.properties: stt.target-langs
   → OynaticiAyarResource (GET /api/ayarlar/oynatici, kimlik istemez)
        → ham ISO kodu listesi döner: ["de","ru","es","fr"]  (backend İSİM ÜRETMİYOR)
   → frontend: ayarlariYukle() bu kodları saklar (oynaticiAyarlari.ts)
   → SubtitleOverlay.tsx: subtitleLangs() her kod için görünen adı bulur
```

**Backend tarafı (Java):** Hiçbir sınıf `tr`/`de`/`ru` gibi bir dili sabit
kodlamıyor.
- `VadService` (canlı kanal altyazısı) ve `VideoSubtitleWorker` (yüklenen
  video altyazısı) — ikisi de açılışta `stt.target-langs`'ı okuyup
  `dil → "marian_en_" + dil` eşlemesini **kendileri** kuruyor
  (`@PostConstruct`). Triton'a hangi modele isteği atacaklarını buradan
  biliyorlar.
- `OynaticiAyarResource` aynı listeyi olduğu gibi (isim üretmeden) JSON
  dizisi olarak frontend'e sunuyor.
- `SubtitleLagMetrics` çeviri gecikmesi metriklerini (`altyazi_ceviri_gecikme_ms`)
  hangi `dil` parametresi gelirse onunla etiketliyor — Grafana
  dashboard'larında da sabit bir dil listesi/dropdown yok, hangi dil
  etiketi Prometheus'ta varsa o gösteriliyor.

**Frontend tarafı (React):** Görünen isim (`"Русский"` gibi) yalnızca
`SubtitleOverlay.tsx`'teki `DIL_ADLARI` sabit haritasından geliyor (~26
dil: tr, de, ru, fr, es, it, pt, nl, pl, ar, zh, ja, ko, uk, ro, bg, cs,
sv, fi, da, el, hu, he, hi, id, vi). Eklediğiniz kod bu haritada varsa
gerçek adıyla görünür; yoksa **kodu büyük harfle** ("FA" gibi) gösterir —
altyazı üretimi/çevirisi bundan etkilenmez, yalnızca seçicideki etiket
kozmetik kalır. Haritada olmayan bir dile güzel isim vermek isterseniz
`DIL_ADLARI`'na tek satır ekleyip `docker compose build frontend` yeterli
(zorunlu değil).

**Triton tarafı:** `export_models.py`, `STT_TARGET_LANGS`'taki her dil
için `triton/templates/marian_model.py` + `marian_config.pbtxt.tmpl`
şablonlarını kopyalayıp `MARIAN_MODELS`'taki repo'yu CTranslate2 formatına
dönüştürüyor (bkz. aşağıdaki "Canlı altyazı: darboğaz neydi" bölümü) —
hiçbir dile özel kod yok, `triton/templates/` dosyalarına asla dokunmanız
gerekmez.

---

## Canlı altyazı: darboğaz neydi, Triton nasıl çözdü

**Eski mimari (`stt-worker`, elle yazılmış Python/FastAPI):** Whisper ve
Marian **tek** model kopyasıydı; `STT_MAX_CONCURRENCY` bu tek kopyaya kaç
eşzamanlı isteğin gireceğini sınırlıyordu (`threading.Semaphore`). Daha
fazla eşzamanlı istek kabul etmek GPU'nun sabit hesap gücünü büyütmüyordu,
yalnızca çekişmeyi artırıyordu — ölçüldü: `STT_MAX_CONCURRENCY`'yi 6'dan
20'ye çıkarınca GPU kullanımı %100'e vurdu ama altyazı kapsaması **%0'a
düştü**. Elle yazılmış bir `BatchCoalescer` katmanı kanallar arası isteği
tek GPU çağrısında birleştirerek bunu kısmen iyileştirdi (ölçülen: Whisper
1,97×, çeviri 5,93× hızlanma) ama temel sınır aynı kaldı: **tek kopya**.

**Triton'da gerçek paralellik var:** `instance_group.count`, her artışında
modelin **VRAM'de tam bir kopyasını daha** açıyor — bu, eski mimaride hiç
olmayan bir kapasite artışı. `dynamic_batching` da aynı BatchCoalescer
fikrini (pencere + max boyut ile kanallar arası birleştirme) Triton'ın kendi
C++ zamanlayıcısında, Python GIL'ine bağlı kalmadan yapıyor.

VAD (konuşma tespiti) Triton'a taşınmadı, Java'da (`video-worker`) kaldı:
konuşma/sessizlik kararı zaten ağsız, in-process çalışıyor; Triton'a
taşımak bunu küçük ses çerçeveleri için sürekli ağ isteğine çevirir
(saniyede onlarca kat daha fazla istek) ve gecikmeyi **düşürmez, artırır**.

**Marian: ONNX Runtime → CTranslate2 (17 Ağustos, ikinci darboğaz).**
Triton'daki gerçek paralellik VRAM'i tavana yaklaştırınca yeni bir sorun
ortaya çıktı: ONNX Runtime'ın CUDA "caching allocator"ı, Marian'a gelen
değişken cümle sayısı/uzunluğu yüzünden her yeni tensor şekli için ayrı
bellek bloğu açıp bunu **hiç geri vermiyordu** — ölçüldü, 1 saatlik gerçek
15-kanal yükünde 590 MB'tan 5,1 GB'a çıkıp kartı tıkadı, çeviri başarı
oranı %5-30'a düştü. Whisper (`faster-whisper`/CTranslate2) aynı sorunu
yaşamıyordu çünkü her girdiyi sabit 30 saniyelik pencereye dolduruyor —
tensor şekli hiç değişmiyor, tek blok sürekli yeniden kullanılıyor
(ölçüldü: 164 MB, saatlerce sabit). Marian da CTranslate2'ye taşınınca
aynı sabit davranışı kazandı — ölçüldü: 452 MB, 3 dakikalık gerçek yükte
**hiç büyümedi**, çeviri başarı oranı %100'e çıktı. Aynı model ağırlıkları
kullanılıyor (`MARIAN_MODELS`), değişen yalnızca GPU'da nasıl çalıştığı.

### Instance sayısı ve VRAM hesabı

Eski mimaride VRAM sabitti (tek kopya, yalnızca hesap gücü paylaşılıyordu).
Triton'da her `*_INSTANCES` artışı gerçek bir VRAM maliyeti:

```
B_maks ≤ (V_kart − V_taban) / (N_es × v)
```

`docs`'ta (artık depoda tutulmuyor, yalnızca bu README kalıcı referans)
eski mimaride ölçülen `v = 80 MB/kesit` ile 6 GB'lık bir kartta
(`V_taban ≈ 2242 MB`, `N_es = 6`) tavan `≈ 8,1` çıkmıştı — ama bu, tek-kopya
eşzamanlılık modeline aitti, Triton'ın `instance_group` paralelliğine
**doğrudan uygulanamaz**. Triton'da her kopya kendi başına tam model ağırlığı
+ ara hesaplama belleği demek:

| Model | `.env` değişkeni | Varsayılan | Not |
|---|---|---|---|
| Whisper | `WHISPER_INSTANCES` | `2` | 6 GB kartta asıl darboğaz, gerçek kazanç buradan |
| `marian_en_<kod>` (her biri) | `MARIAN_INSTANCES` (`kod=sayı,...`) yoksa `MARIAN_INSTANCES_DEFAULT` | `1` | `STT_TARGET_LANGS`'taki HER dil için ayrı bir `marian_en_<kod>` dizini `entrypoint.sh` tarafından otomatik keşfediliyor — yeni dil eklemek bu script'e dokunmayı gerektirmez |

**Değiştirmek rebuild gerektirmiyor:** `triton/entrypoint.sh`, container her
başladığında `config.pbtxt`'lerdeki yer tutucuları (`${WHISPER_INSTANCES}`
gibi) `.env`'deki gerçek sayıyla dolduruyor (`envsubst`). Yani:

```bash
# .env'de WHISPER_INSTANCES=3 yapıp:
docker compose up -d triton
```

yeterli. **VRAM tepe değeri her değişiklikte ölçülmeli** — yukarıdaki MB
tahminleri disk boyutundan türetildi, GPU'da izole ölçülmedi:

```bash
nvidia-smi dmon                      # canlı, kaba
curl -s localhost:8002/metrics | grep nv_gpu_memory_used_bytes   # Triton'ın kendi ölçümü
```

`triton` sürekli yeniden başlıyorsa (`docker inspect -f
'{{.State.Status}}' triton`) neredeyse her zaman **model(ler) VRAM'e
sığmıyor** demektir — önce `*_INSTANCES` değerlerini düşürün (rebuild
gerekmez), sonra `STT_MODEL=medium` ya da `STT_COMPUTE_TYPE=int8`
(bunlar **imaj yeniden kurulmasını** ister: `docker compose build triton`).
