# Triton mimarisine geçiş — VRAM sınırsız senaryosu, 100 kanal ölçeği

**Durum: taslak / tahmin.** Bu belgedeki hiçbir "100 kanalda X kazanç"
sayısı ölçülmedi — ölçülemez, çünkü elimizde 6 GB'lık tek bir RTX 4050 var
ve varsayım tam olarak bunun tersi ("VRAM sınırım olmasaydı"). Aşağıdaki
her hesap, bu projede **gerçekten ölçülmüş** birkaç sayıdan matematiksel
olarak türetildi; hangisinin ölçüldüğü, hangisinin varsayım olduğu her
adımda ayrı ayrı işaretlendi. Gerçek bir Triton dağıtımı olmadan bu
sayıların doğrulanamayacağı — `docs/4050-kapasite-testi-sonuclar.md`'deki
A100 ekstrapolasyonunda uygulanan aynı disiplinle — baştan kabul ediliyor.

---

## İçindekiler

1. [Bugünün mimarisi — asıl kısıt ne, VRAM mi değil mi](#1-bugünün-mimarisi--asıl-kısıt-ne-vram-mi-değil-mi)
2. [Triton ne değiştirir](#2-triton-ne-değiştirir)
3. [Matematiksel model](#3-matematiksel-model)
4. [100 kanal hesabı](#4-100-kanal-hesabı)
5. [Uygulama planı — adım adım](#5-uygulama-planı--adım-adım)
6. [Yapılandırma dosyaları](#6-yapılandırma-dosyaları)
7. [Bu kartta (RTX 4050) Triton'a geçmenin gerçek karşılığı](#7-bu-kartta-rtx-4050-tritona-geçmenin-gerçek-karşılığı)
8. [Doğrulanmamış varsayımlar — tam liste](#8-doğrulanmamış-varsayımlar--tam-liste)
9. [Deploy sonrası ölçülmesi gerekenler](#9-deploy-sonrası-ölçülmesi-gerekenler)

---

## 1. Bugünün mimarisi — asıl kısıt ne, VRAM mi değil mi

### 1.1 Ölçülen mevcut durum

| | Değer | Kaynak |
|---|---|---|
| Model | faster-whisper `small`, `int8_float16`, `beam_size=1` | `.env` |
| Çeviri | Marian (`transformers`), EN→tr/de/ru, GPU | `translate.py` |
| Eşzamanlılık tavanı | `STT_MAX_CONCURRENCY=6` (Python `Semaphore`) | `stt.py:33` |
| Tek bölüt işlem süresi (6sn ses, tam hat) | **0,323 sn** (RTF 18,59×) | ölçüldü, `4050-kapasite-testi-sonuclar.md` §C.3 |
| Boşta VRAM | **2242 MB** / 6141 MB | ölçüldü, §A.4 |
| Tepe VRAM (4→16 kanal arası) | **3106 MB** / 6141 MB (%50,6) | ölçüldü, §B/C.1 |
| Stabil kanal sayısı | **8** | ölçüldü |
| Kırılma başlangıcı | **12** kanal, GPU util %68,2 (doymamış) | ölçüldü |
| Tam çöküş | **16** kanal, GPU util %83,7 (hâlâ %100 değil) | ölçüldü |

### 1.2 Kanıt: VRAM hiçbir aşamada sınırlayıcı değildi

4→16 kanal arası VRAM **2643 MB → 3106 MB** — sadece 463 MB fark. Kanal
sayısı 4 katına çıkarken VRAM %18 büyüdü. Bunun sebebi: model ağırlıkları
**tek kopya** paylaşılıyor (`Transcriber`/`Translator` sınıfları
`ApplicationScoped` benzeri tekil), kanal sayısı büyüdükçe büyüyen şey VRAM
değil **kuyruk ve gecikme**.

Sonuç: **`STT_MAX_CONCURRENCY=6` tavanı VRAM'den gelmiyor.** Muhtemelen
"GPU'yu boğmayalım" diye ihtiyatlı seçilmiş keyfi bir sayı — kart bunun çok
üstünü ölçülen tepe kullanımla (%50,6) kolayca taşırdı. Bu, sorunun tam
merkezi: **"VRAM sınırım olmasaydı" sorusu, bu mimaride VRAM zaten sınır
olmadığı için "tavanı büyüt" ile aynı şey değil.** Gerçek kazanç başka
yerden geliyor — §2.

### 1.3 Kanıt: cross-request batching hiç yok

`stt.py`'nin kendi yorumu (bu proje zaten fark etmiş):

```python
# Asil kazanc KANALLAR ARASI yiginlamada: 20 kanalin bolutleri
# tek yiginda toplanirsa GPU bosta beklemez. O, istek duzeyinde
# bir kuyruk gerektiriyor ve HENUZ YOK -- kart geldiginde
# olculup eklenecek.
```

Bugün `STT_MAX_CONCURRENCY=6` demek, aynı anda **6 ayrı** forward-pass —
her biri kendi kernel-launch'ı, kendi bellek-bant-genişliği maliyetiyle.
`translate.py`'de de aynı desen: bir segmentin İÇİNDEKİ cümleler tek
yığında çevriliyor (`_translate_one` — doğru), ama **farklı segmentler/
kanallar arasında** hiçbir yığınlama yok. Bu, Triton'ın `dynamic_batching`
özelliğinin çözdüğü tam olarak bu boşluk.

---

## 2. Triton ne değiştirir

Üç ayrı, birbirini güçlendiren mekanizma:

| Mekanizma | Ne yapar | Bugün var mı |
|---|---|---|
| **`instance_group`** | Aynı modelin N kopyasını GPU'da paralel çalıştırır | Yok — model tek kopya, `Semaphore(6)` ile paylaşılıyor |
| **`dynamic_batching`** | Kısa bir pencerede (ms) biriken farklı isteklerdengeç bir batch kurar, TEK forward-pass'te işler | Yok — her istek kendi forward-pass'i |
| **Ensemble / BLS** | Whisper→3×Marian zincirini sunucu içinde, ağ turu olmadan bağlar | Yok — bugün `stt-worker` tek process içinde Python'da sırayla/paralel çağırıyor |

**Önemli ve dürüst bir nokta:** Triton'ın kendisi büyü yapmıyor. Asıl
kazanç "farklı isteklerin batch'lenmesi" fikri — bu fikrin küçük bir
kısmı Triton'suz da elde edilebilir: CTranslate2'nin kendi
`translate_batch`/`transcribe` API'leri zaten native batch alıyor; elle
yazılmış bir "50ms topla, tek çağır" katmanı da benzer kazancın bir
kısmını verir. Triton'ın asıl getirdiği, bunu **production-sınıfı** hâle
getirmesi: otomatik scheduler, çoklu-model orkestrasyon, versiyonlama,
metrik, health-check, GPU-başına-instance dengeleme. Küçük ölçekte
(bugünkü 8 kanal) bu altyapı bedelini karşılamaz; 100 kanal ölçeğinde
karşılar — aşağıdaki matematik bunu gösteriyor.

---

## 3. Matematiksel model

### 3.1 Batch gecikme modeli

Transformer/Whisper tipi modellerde batch büyüklüğü B'nin gecikmesi
yaklaşık olarak:

```
T(B) ≈ T_sabit + T_değişken × B
```

- `T_sabit`: kernel-launch, attention-setup gibi batch boyutundan
  BAĞIMSIZ maliyet.
- `T_değişken`: batch elemanı başına EK maliyet — bellek bant genişliğiyle
  ölçekleniyor, GPU'nun matris-çarpım donanımı küçük batch'lerde büyük
  ölçüde ATIL kaldığı için genelde küçük bir kesir.

B=1 için `T(1) = T_sabit + T_değişken = 0,323 sn` (ölçüldü).

**Bu ayrıştırma (T_sabit/T_değişken oranı) ÖLÇÜLMEDİ** — bu donanımda,
bu modelle hiçbir batch-sweep testi yapılmadı. Literatürden bilinen genel
eğilim: küçük modellerde (whisper `small` ≈ 244M parametre) sabit maliyet
payı yüksektir. İki senaryo ile alt/üst sınır veriyorum:

| Senaryo | T_sabit | T_değişken | T(8) | Throughput(8) | B=1'e göre verim |
|---|---|---|---|---|---|
| İyimser (sabit maliyet payı %70) | 0,226 sn | 0,097 sn | 1,002 sn | 7,98 bölüt/sn | **2,58×** |
| Muhafazakâr (sabit maliyet payı %50) | 0,162 sn | 0,162 sn | 1,458 sn | 5,49 bölüt/sn | **1,77×** |

(Karşılaştırma: B=1'de throughput = 1/0,323 = 3,10 bölüt/sn — tek bir
"slot"un batch'siz kapasitesi.)

### 3.2 Kanal başına talep — Little's Law ile türetme

Kuyruklama teorisinin temel ilişkisi: `L = λ × W` (sistemdeki ortalama iş
sayısı = geliş hızı × sistemde geçirilen süre).

Kırılmanın **başladığı** nokta (12 kanal), talebin kapasiteye eşitlendiği
an olarak okunabilir — sistem henüz tam doymamış (GPU %68,2) ama kuyruk
biriktirmeye başlamış:

```
λ_toplam(12 kanal) ≈ C / T(1) = 6 / 0,323 ≈ 18,58 bölüt/sn
λ_kanal ≈ 18,58 / 12 ≈ 1,55 bölüt/sn/kanal
```

**Bu bir VARSAYIM zinciri, ölçüm değil** — "kırılma = talep/kapasite
eşitliği" yaklaşık bir okuma; gerçek kırılma yumuşak (12'de GPU henüz
doymamış), yani λ_kanal hem biraz düşük hem biraz yüksek tahmin edilmiş
olabilir. Ama bu, elimizdeki 3 ölçülen noktayla (8 stabil / 12 kırılma /
16 çöküş) içsel tutarlı, rastgele atılmış bir sayı değil.

Doğrulama: 8×1,55=12,4 bölüt/sn (6'lık tavanın altında → stabil, ölçümle
uyumlu). 16×1,55=24,8 bölüt/sn (tavanın 4 katı → tam çöküş, ölçümle
uyumlu).

---

## 4. 100 kanal hesabı

### 4.1 Gereken toplam kapasite

```
λ_100 = 100 × 1,55 ≈ 155 bölüt/sn
```

**Varsayım:** 100 kanalın konuşma/kanal karışımı, 4050 testindeki 16
kanallık karışıma (haber + spor + müzik + belgesel) benzer olacak. Gerçek
karışım daha çok haber-ağırlıklı olursa λ_kanal daha yüksek çıkar (haber
kanalı ölçülen konuşma oranı %97); daha çok müzik-ağırlıklı olursa daha
düşük.

### 4.2 Gereken instance sayısı — batching'siz (sadece `instance_group`)

```
Kapasite(N) = N / T(1) = N / 0,323
N = 155 × 0,323 ≈ 50 instance
```

VRAM: `50 × 2242 MB ≈ 112 GB` — **iki 80 GB A100/H100 kartı gerektirir.**

### 4.3 Gereken instance sayısı — dynamic batching (B=8) ile

```
Kapasite(N, B=8) = N × Throughput(8)
```

| Senaryo | Throughput(8) | Gereken N | VRAM (N × 2242 MB) |
|---|---|---|---|
| İyimser | 7,98 bölüt/sn | **20 instance** | ~44,8 GB |
| Muhafazakâr | 5,49 bölüt/sn | **29 instance** | ~65,0 GB |

**Sonuç:** dynamic batching, aynı 100-kanal hedefi için gereken GPU
belleğini **~1,7×–2,5× azaltıyor** (112 GB → 45-65 GB). Her iki senaryo
da **tek bir 80 GB kartla** (A100 80GB / H100) karşılanabiliyor;
batching olmadan iki karta ihtiyaç var. Triton'ın matematiksel katma
değeri tam olarak burada — donanım maliyetini yarıya indiriyor.

### 4.4 Duyarlılık — pik trafik

Yukarıdaki hesap **ortalama** yükü baz alıyor. 100 kanalın hepsi aynı anda
konuşma-yoğun olursa (örn. 100 haber kanalı, hepsi %97 konuşma oranında)
λ_kanal literatürdeki en yüksek gözlenen değere (TRT Haber gibi) yaklaşır
— bu durumda gereken instance sayısı yukarıdaki tablonun **1,3-1,5×**'ine
çıkabilir (tahmini, ölçülmedi). Prodüksiyon planlaması bu payı
(`instance_group.count`'a %30-50 tampon) içermeli.

---

## 5. Uygulama planı — adım adım

### Aşama 0 — Ölçüm (Triton'dan ÖNCE, zorunlu)

Bu projenin ilkesi (`CLAUDE.md`: "ölçmeden sayı verme") burada da geçerli.
Triton'a geçmeden önce, mevcut mimaride şunlar ölçülmeli:

1. `STT_MAX_CONCURRENCY`'yi kademeli yükselt (8→12→16→24), her adımda
   VRAM/GPU-util/gecikme kaydet. Eğer 6'dan 16'ya çıkmak zaten belirgin
   fayda veriyorsa, Triton'ın karmaşıklığına gerek kalmayabilir — bu
   belgenin öngördüğü kazancın bir kısmı **basit bir `.env` değişikliğiyle**
   elde edilebilir.
2. CTranslate2'nin native `translate_batch`/batch `transcribe` API'siyle,
   Triton'suz, elle yazılmış bir "50ms topla → tek çağır" katmanının
   kazancını ölç. Bu, §3.1'deki T_sabit/T_değişken ayrıştırmasını
   **gerçek veriyle** doldurur — şu andaki iki senaryo (iyimser/
   muhafazakâr) yerine tek, ölçülmüş bir sayı.

### Aşama 1 — Model export

| Model | Yol | Gerekçe |
|---|---|---|
| Whisper `small` | Python backend, CTranslate2 sarmalayıcı | Triton'da native CTranslate2 backend yok; ONNX/TensorRT export mümkün ama int8 quantization'ı yeniden kurmak gerekir — yüksek mühendislik maliyeti |
| Marian (tr/de/ru) | ONNX export (`optimum` kütüphanesi) → `onnxruntime` backend | Marian küçük, export'u olgun/belgeli; native Triton `dynamic_batching` kod yazmadan çalışır |

### Aşama 2 — Model repository ve BLS ensemble

```
model_repository/
├── whisper_small/
│   ├── config.pbtxt
│   └── 1/model.py            # Python backend
├── marian_tr/{config.pbtxt, 1/model.onnx}
├── marian_de/{config.pbtxt, 1/model.onnx}
├── marian_ru/{config.pbtxt, 1/model.onnx}
└── altyazi_pipeline/          # BLS: whisper -> 3x marian (paralel)
    ├── config.pbtxt
    └── 1/model.py
```

BLS (Business Logic Scripting), whisper çıktısını 3 Marian modeline
**paralel** dağıtır — bugünkü `anyio.create_task_group` mantığının
Triton içi karşılığı, ama artık ağ turu (stt-worker→Triton) tek sefer.

### Aşama 3 — VadService/SttClient entegrasyonu

`SttClient.java`'daki ham-PCM-body + zorla-HTTP/1.1 deseni, Triton'ın
KServe v2 REST/gRPC API'sine çevrilecek. İki seçenek:

- REST (KServe v2 JSON+binary tensor) — mevcut `HttpClient` deseniyle
  devam edilebilir, gövde formatı değişir.
- gRPC — Triton'ın `.proto` dosyaları Java'ya derlenir (`protobuf-maven-plugin`),
  daha düşük gecikme ama build zinciri karmaşıklaşır.

`stt-worker` (FastAPI) katmanı **tamamen kalkabilir** — VadService
doğrudan Triton'a konuşur. Ya da geçiş riskini azaltmak için ince bir
adaptör olarak bırakılıp `STT_BACKEND=fastapi|triton` bayrağıyla
kademeli geçiş yapılabilir (§Aşama 4).

### Aşama 4 — Kademeli geçiş

CLAUDE.md kültürüyle uyumlu: büyük altyapı değişikliği ölçülmeden tek
seferde devreye alınmaz.

1. Triton'u paralel ayağa kaldır, trafiğin **%0'ı** ile canlı sağlık
   kontrolü (aynı ses, iki yoldan geçirip çıktı karşılaştırması).
2. `STT_BACKEND` bayrağıyla belirli kanalların bir kısmını Triton'a
   yönlendir, `SubtitleLagMetrics`'teki kapsama/gecikme metrikleriyle
   iki yolu **aynı anda, aynı Grafana panelinde** karşılaştır.
3. Fayda ölçülünce (§9'daki liste) tam geçiş, `stt-worker` kaldırılır.

### Aşama 5 — Docker Compose

```yaml
tritonserver:
  image: nvcr.io/nvidia/tritonserver:24.09-py3
  runtime: nvidia
  environment:
    NVIDIA_VISIBLE_DEVICES: all
  volumes:
    - ./model_repository:/models
  command: tritonserver --model-repository=/models
  ports:
    - "8000:8000"   # HTTP
    - "8001:8001"   # gRPC
    - "8002:8002"   # metrics (Prometheus formatında — mevcut Grafana'ya direkt eklenir)
```

Triton'ın `/metrics` uç noktası **zaten Prometheus formatında** —
bu oturumda kurulan `prometheus.yml`'e tek satır scrape-target eklemek
yeterli, ayrı bir entegrasyon gerekmiyor.

---

## 6. Yapılandırma dosyaları

### `whisper_small/config.pbtxt`

```protobuf
name: "whisper_small"
backend: "python"
max_batch_size: 16

dynamic_batching {
  preferred_batch_size: [4, 8, 16]
  max_queue_delay_microseconds: 50000   # 50ms — ALTYAZI_BUTCE_MS (8000ms) yanında ihmal edilebilir
}

instance_group [
  { count: 8, kind: KIND_GPU }
]

input [
  { name: "PCM_AUDIO", data_type: TYPE_FP32, dims: [ -1 ] }
]
output [
  { name: "TEXT", data_type: TYPE_STRING, dims: [ 1 ] },
  { name: "LANGUAGE", data_type: TYPE_STRING, dims: [ 1 ] }
]
```

### `marian_tr/config.pbtxt`

```protobuf
name: "marian_tr"
backend: "onnxruntime"
max_batch_size: 32

dynamic_batching {
  preferred_batch_size: [8, 16, 32]
  max_queue_delay_microseconds: 30000
}

instance_group [
  { count: 4, kind: KIND_GPU }
]

input [
  { name: "INPUT_IDS", data_type: TYPE_INT64, dims: [ -1 ] },
  { name: "ATTENTION_MASK", data_type: TYPE_INT64, dims: [ -1 ] }
]
output [
  { name: "TRANSLATION", data_type: TYPE_STRING, dims: [ 1 ] }
]
```

### `altyazi_pipeline/1/model.py` (BLS iskeleti)

```python
import triton_python_backend_utils as pb_utils

class TritonPythonModel:
    def execute(self, requests):
        responses = []
        for request in requests:
            pcm = pb_utils.get_input_tensor_by_name(request, "PCM_AUDIO")

            whisper_req = pb_utils.InferenceRequest(
                model_name="whisper_small",
                requested_output_names=["TEXT", "LANGUAGE"],
                inputs=[pcm])
            whisper_resp = whisper_req.exec()
            text = pb_utils.get_output_tensor_by_name(whisper_resp, "TEXT")

            # Uc dile PARALEL gonder — anyio.create_task_group'un
            # Triton-ici karsiligi.
            pending = [
                pb_utils.InferenceRequest(
                    model_name=f"marian_{lang}",
                    requested_output_names=["TRANSLATION"],
                    inputs=[text]).async_exec()
                for lang in ("tr", "de", "ru")
            ]
            translations = {
                lang: pb_utils.get_output_tensor_by_name(p.wait(), "TRANSLATION")
                for lang, p in zip(("tr", "de", "ru"), pending)
            }

            out_tensors = [text] + [translations[l] for l in ("tr", "de", "ru")]
            responses.append(pb_utils.InferenceResponse(output_tensors=out_tensors))
        return responses
```

---

## 7. Bu kartta (RTX 4050) Triton'a geçmenin gerçek karşılığı

Sorunun başlığı "VRAM sınırım olmasaydı" — yani bugünkü 6 GB kartla ne
olacağını da dürüstçe göstermek gerekiyor, çünkü asıl karar burada
verilecek:

```
Kart:            6141 MB
Sistem/CUDA overhead (tahmini): ~500 MB
Kullanılabilir:  ~5641 MB
1 instance seti (whisper+3 marian): 2242 MB

Sığan instance sayısı: 5641 / 2242 ≈ 2,5 → pratikte 2 instance
```

**2 instance + dynamic batching (B=8, iyimser) ile kapasite:**

```
2 × 7,98 ≈ 16 bölüt/sn
```

Bugünkü batchsiz tavan (6/0,323 ≈ 18,58 bölüt/sn) ile **karşılaştırılabilir,
hatta biraz daha düşük** — çünkü VRAM kısıtı instance sayısını 6
eşzamanlı istekten daha az bağımsız kopyaya (2) düşürüyor, batching'in
kazancı bunu ancak dengeliyor. **Sonuç: bu kartta Triton'a geçmenin
ölçülebilir bir kazanç getirmesi olası değil** — tam da
`docs/p0-p1-p2-degerlendirme.md`'nin P2 bölümünde önceden söylenen şey.
Triton'ın matematiksel avantajı **yalnızca VRAM instance-sayısını
kısıtlamadığı** ölçekte (80 GB'lık kartlar, §4) ortaya çıkıyor.

---

## 8. Doğrulanmamış varsayımlar — tam liste

| # | Varsayım | Nerede kritik | Nasıl doğrulanır |
|---|---|---|---|
| 1 | T_sabit/T_değişken oranı (%50-70 sabit) | §3.1, §4'ün tamamı | Bu donanımda gerçek batch-sweep (B=1,2,4,8,16) |
| 2 | λ_kanal ≈ 1,55 bölüt/sn ("kırılma=kapasite" okuması) | §3.2, §4.1 | Ara kademeler (2,4,6,10,14 kanal) test edilerek gerçek kapasite eğrisi çıkarılmalı — `p0-p1-p2-degerlendirme.md`'de de "ara değerler test edilmedi" notu var |
| 3 | 100 kanalın karışımı 16-kanal testindekine benzer | §4.1, §4.4 | Gerçek hedef kanal listesiyle konuşma-oranı ölçümü |
| 4 | CTranslate2 Python-backend'in Triton scheduler'ıyla uyumu sorunsuz | §5 Aşama 1 | Küçük ölçekli PoC (5-10 model instance) |
| 5 | Marian ONNX export kalite kaybı yok | §5 Aşama 1 | Export sonrası çıktı karşılaştırması (bkz. §C.3'teki üç dilli doğrulama yöntemi) |
| 6 | VRAM/instance = 2242 MB sabit kalır (batch büyüklüğü VRAM'i önemli artırmaz) | §4.2-4.3, §7 | Ölçülmedi — dynamic_batching aktivasyon buffer'ları VRAM'e ek yük bindirir, tahmini küçük ama doğrulanmadı |

---

## 9. Deploy sonrası ölçülmesi gerekenler

Gerçek bir Triton PoC kurulduğunda, bu belgedeki HER sayı şu ölçümlerle
değiştirilmeli (varsayım → gerçek):

1. `T(B)` gerçek eğrisi: B=1,2,4,8,16 için gecikme (§3.1'deki iki
   senaryonun hangisine daha yakın olduğunu gösterir)
2. Tek GPU'da instance-sayısı arttıkça throughput'un düzleştiği nokta
   (gerçek GPU doygunluğu — bu belgede hiç ölçülmedi, tamamen teorik)
3. λ_kanal'ın gerçek hedef kanal karışımıyla ölçümü
4. BLS ensemble'ın eklediği ek gecikme (ağ turu azaldı ama Triton'ın
   kendi scheduler overhead'i var — net kazanç ölçülmeli)
5. Marian ONNX export'unun çıktı kalitesi (üç dilde, gerçek konuşma
   örnekleriyle, `4050-kapasite-testi-sonuclar.md` §C.3'teki yöntemle)

Bu beşi ölçülmeden, bu belgedeki "20-29 instance / 45-65 GB" aralığı
**mühendislik tahmini** olarak kalır — proje kültüründeki A100
ekstrapolasyonuyla aynı statüde: yol haritası için yeterli, satın alma
kararı için yetersiz.
