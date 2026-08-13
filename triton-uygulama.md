# Tek Instance Triton Kurulum Planı

**Kapsam:** Bu doküman, **bir tane** Triton Inference Server kurulumunu baştan sona tanımlar — Whisper + VAD + 3 Marian modeli, tüm config'ler, orchestrator entegrasyonu, test checklist. Bu, 3-makineli dağıtık mimarinin (önceki diyagram) **tek bir yapı taşı**dır — önce burada doğru çalıştığını kanıtla, sonra bu birimi 3 makineye çoğalt.

**Bu planın dayandığı önceki kararlar (özet):**
- Whisper görevi `task=translate`, pivot İngilizce, doğruluk esnek → küçük model (`small`/`base`) kullanılabilir
- 3 hedef dil (tr/de/ru) SABİT, her zaman üretilir, kesilemez
- Marian çağrıları **ayrı ayrı** yapılır, ensemble'da BİRLEŞTİRİLMEZ (anında-yayınla deseni için)
- CORRID = kanal_id, sequence batching ile kanal karışması engellenir
- 4050'de küçük ölçek doğrulama, A100'de gerçek kapasite

---

## 1. Model Repository Yapısı

```
model_repository/
├── silero_vad/
│   ├── config.pbtxt
│   └── 1/
│       └── model.py          (python backend, VAD mantığı)
├── whisper_small/
│   ├── config.pbtxt
│   └── 1/
│       └── model.py          (python backend, faster-whisper sarmalı)
├── marian_en_tr/
│   ├── config.pbtxt
│   └── 1/
│       └── model.onnx        (veya pytorch, backend'e göre)
├── marian_en_de/
│   ├── config.pbtxt
│   └── 1/
│       └── model.onnx
├── marian_en_ru/
│   ├── config.pbtxt
│   └── 1/
│       └── model.onnx
└── vad_whisper_pipeline/       (ENSEMBLE — sadece VAD+Whisper, Marian YOK)
    └── config.pbtxt
```

**Marian'ın ensemble klasöründe olmaması bilinçli** — Bölüm 4'te gerekçesi var.

---

## 2. Whisper Config — Dynamic + Sequence Batching

```protobuf
# whisper_small/config.pbtxt
name: "whisper_small"
backend: "python"
max_batch_size: 16

input [
  { name: "audio_segment", data_type: TYPE_FP32, dims: [-1] }
]
output [
  { name: "pivot_text", data_type: TYPE_STRING, dims: [1] }
]

dynamic_batching {
  preferred_batch_size: [4, 8, 16]
  max_queue_delay_microseconds: 100000    # 100ms — 4050 icin baslangic degeri
}

sequence_batching {
  max_sequence_idle_microseconds: 5000000  # 5sn sessizlik sonrasi slot serbest
  control_input [
    { name: "START", control: [{ kind: CONTROL_SEQUENCE_START, fp32_false_true: [0,1] }] },
    { name: "END",   control: [{ kind: CONTROL_SEQUENCE_END,   fp32_false_true: [0,1] }] },
    { name: "CORRID", control: [{ kind: CONTROL_SEQUENCE_CORRID, data_type: TYPE_UINT64 }] }
  ]
  oldest {
    max_candidate_sequences: 24     # 4050 icin: hedef kanal sayisi + guvenlik payi
    preferred_batch_size: [4, 8, 16]
  }
}

instance_group [
  { count: 2, kind: KIND_GPU, gpus: [0] }   # 4050: 2 ile basla, sweep ile optimize
]

model_warmup [
  {
    name: "warmup"
    batch_size: 4
    inputs: {
      key: "audio_segment"
      value: { data_type: TYPE_FP32, dims: [64000], zero_data: true }  # 4sn @ 16kHz
    }
  }
]
```

**`max_candidate_sequences: 24` neden 24, 100 değil:** Bu tek instance, 4050 için tasarlanıyor — 4050'nin gerçekçi tavanı (önceki sweep testlerinde bulunacak) muhtemelen 20-30 kanal civarı. A100'e geçişte bu değer değişecek (100+ 'a çıkacak) — bu, dosyanın **tek** donanıma özel satırı, diğer her şey aynı kalır.

---

## 3. Marian Config'leri — 3 Ayrı Model, Response Cache Açık

```protobuf
# marian_en_tr/config.pbtxt  (en_de, en_ru icin ayni yapi, isim degisir)
name: "marian_en_tr"
backend: "onnxruntime"       # veya pytorch, hangi format kullaniliyorsa
max_batch_size: 32

input [
  { name: "source_text", data_type: TYPE_STRING, dims: [1] }
]
output [
  { name: "translated_text", data_type: TYPE_STRING, dims: [1] }
]

dynamic_batching {
  preferred_batch_size: [8, 16, 32]
  max_queue_delay_microseconds: 50000    # 50ms — ceviri hafif, kisa bekleme yeter
}

instance_group [
  { count: 2, kind: KIND_GPU, gpus: [0] }
]

response_cache {
  enable: true    # tekrarlayan ifadeler icin sifir-kod cache (Marian'a ozel fayda)
}
```

**`response_cache: true` neden burada, Whisper'da değil:** Ses segmentleri (Whisper girdisi) neredeyse hiçbir zaman byte-byte aynı olmaz (gürültü, mikrofon farkı) — cache hit oranı sıfıra yakın olur, boşuna bellek harcar. Metin (Marian girdisi) ise tekrarlayan kalıp ifadelerde (anonslar, sabit cümleler) gerçek hit potansiyeli taşır. Bu yüzden cache sadece Marian'da açık.

---

## 4. Ensemble Kararı — Sadece VAD+Whisper, Marian Hariç

```protobuf
# vad_whisper_pipeline/config.pbtxt
name: "vad_whisper_pipeline"
platform: "ensemble"

input [
  { name: "raw_audio", data_type: TYPE_FP32, dims: [-1] }
]
output [
  { name: "pivot_text", data_type: TYPE_STRING, dims: [1] }
]

ensemble_scheduling {
  step [
    {
      model_name: "silero_vad"
      input_map: { key: "audio_chunk", value: "raw_audio" }
      output_map: { key: "is_speech", value: "vad_flag" }
    },
    {
      model_name: "whisper_small"
      input_map: { key: "audio_segment", value: "raw_audio" }
      output_map: { key: "pivot_text", value: "pivot_text" }
    }
  ]
}
```

**Neden Marian burada YOK (kritik karar, tekrar hatırlatma):** Ensemble, DAG'in tamamı bitmeden yanıt döndürmez. VAD→Whisper sıralı ve tek çıkışlı olduğu için sorun değil. Ama Marian'ı buraya eklersen (3 paralel dal), en yavaş dil bitene kadar hiçbir dil yayınlanamaz — "anında yayınla" avantajını kaybedersin. Bu yüzden orchestrator, `pivot_text` çıktığı anda **3 ayrı** Triton çağrısı yapar (Bölüm 5), ensemble'a dahil etmez.

---

## 5. Orchestrator Entegrasyonu — Anında Yayınla Deseni

### 5.1 Ensemble Çağrısı (VAD+Whisper, tek istek)

```java
// Java/Quarkus tarafi
CompletableFuture<String> pivotFuture = CompletableFuture.supplyAsync(
    () -> tritonClient.infer("vad_whisper_pipeline", rawAudioSegment, kanalId),  // CORRID=kanalId
    sttExecutor
);
```

### 5.2 Marian — 3 Ayrı Çağrı, Her Biri Kendi Bitince Yayınlanır

```java
pivotFuture.thenAccept(pivotText -> {
    if (pivotText == null || pivotText.isBlank()) return;

    Map<String, String> modeller = Map.of(
        "tr", "marian_en_tr",
        "de", "marian_en_de",
        "ru", "marian_en_ru"
    );

    modeller.forEach((lang, model) ->
        CompletableFuture.supplyAsync(() -> tritonClient.infer(model, pivotText), sttExecutor)
            .thenAccept(sonuc -> yayinlaCue(kanalId, lang, sonuc))   // aninda yayinla
            .exceptionally(e -> {
                log.warn("Ceviri hata: kanal={} dil={} hata={}", kanalId, lang, e.getMessage());
                return null;
            })
    );
});
```

**Toplam ağ çağrısı sayısı:** 1 (VAD+Whisper ensemble) + 3 (Marian, paralel) = 4 Triton çağrısı/segment. Sıralı yapılsaydı 4x gecikme birikirdi; bu şekilde her dilin gecikmesi bağımsız.

---

## 6. Docker Deployment (Tek Instance)

```yaml
# docker-compose.yml — triton servisi
triton:
  image: nvcr.io/nvidia/tritonserver:24.XX-py3
  command: >
    tritonserver
    --model-repository=/models
    --http-port=8000
    --grpc-port=8001
    --metrics-port=8002
    --cache-config=local,size=134217728    # 128MB local response cache
  deploy:
    resources:
      reservations:
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]
  volumes:
    - ./model_repository:/models
    - model-cache:/root/.cache            # model artifact cache (indirme tekrarini onler)
  ports:
    - "8000:8000"   # HTTP
    - "8001:8001"   # gRPC
    - "8002:8002"   # metrics
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8000/v2/health/ready"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 60s   # warmup suresine gore ayarlanmali

volumes:
  model-cache:
```

**`start_period: 60s` neden önemli:** Model yükleme + warmup tamamlanmadan health check "ready" dememeli — aksi halde orchestrator, henüz hazır olmayan Triton'a istek göndermeye başlar, ilk isteklerde hata/gecikme olur.

---

## 7. Metrics Entegrasyonu (Grafana'ya Bağlama)

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'triton'
    static_configs:
      - targets: ['triton:8002']
    scrape_interval: 5s
```

Bakılacak temel Triton metrikleri (önceki Grafana planına ek):
```
nv_inference_queue_duration_us{model="whisper_small"}       → kuyruk suresi, p95
nv_inference_pending_request_count{model="whisper_small"}   → biriken istek, ARTIYORSA ALARM
nv_inference_compute_infer_duration_us{model="marian_en_tr"} → ceviri suresi
nv_cache_num_hits_per_model / nv_cache_num_misses_per_model  → response cache hit orani
```

---

## 8. Instance Sizing Formülü — 4050 vs A100

```
instance_group.count formulu:

  VRAM_kullanilabilir = Toplam_VRAM - Driver_overhead(~1GB) - Diger_modeller
  Instance_basi_VRAM   = Model_agirlik + Batch_buffer (int8: kucuk model icin ~300-500MB)

  count = floor(VRAM_kullanilabilir / Instance_basi_VRAM), guvenlik payi ile %20 dusur
```

| Parametre | 4050 (6GB) | A100 (40GB) |
|---|---|---|
| whisper_small instance_group.count | 2 | 8 |
| marian_x3 instance_group.count (her biri) | 2 | 6 |
| max_candidate_sequences | 24 | 128 |
| preferred_batch_size (whisper) | [4,8,16] | [16,32,64] |
| max_queue_delay (whisper) | 100ms | 30-50ms |

**Bu tablo dışındaki her şey (config yapısı, ensemble kararı, anında-yayınla deseni) İKİ donanımda da AYNI kalır** — sadece sayılar değişir.

---

## 9. Test/Doğrulama Checklist (Bu Tek Instance İçin)

```
[ ] Triton container ayaga kalkiyor, /v2/health/ready 200 donuyor
[ ] Her model ayri ayri yuklu gorunuyor (curl /v2/models/{model}/config)
[ ] Ensemble (vad_whisper_pipeline) tek istekle pivot_text donduruyor
[ ] 3 Marian modeline ayri ayri istek atilinca, HER BIRI dogru dilde ceviri donduruyor
[ ] Ayni CORRID ile 2 ardisik istek gonderilince, sequence context korunuyor
[ ] 2 FARKLI CORRID ile ayni anda istek gonderilince, sonuclar KARISMIYOR (kritik test)
[ ] Marian response_cache: ayni metni 2 kere gonder, 2. istek MUCH daha hizli donuyor mu (cache hit)
[ ] nvidia-smi dmon ile GPU util, yuk altinda beklenen seviyede (0'dan farkli)
[ ] Grafana'da nv_inference_queue_duration_us goruniyor
[ ] Warmup calisiyor: container basladiktan SONRAKI ilk istek de hizli (cold-start yok)
```

**Bu checklist tamamlanmadan** 3-makineli dağıtık mimariye geçilmemeli — çünkü dağıtık kurulumda 3 kopyanın her biri bu tek-instance sorunlarını miras alır, hata ayıklama 3 katına çıkar.

---

## 10. Sonraki Adım — 3 Makineye Çoğaltma

Bu doküman doğrulandıktan sonra, önceki diyagramdaki mimariye geçiş şu şekilde olur:

```
1. Bu model_repository'yi (config'ler + ağırlıklar) 3 makineye kopyala (degismez)
2. Her makinede AYRI bir Triton container calisir (bu dokumandaki docker-compose, 3 kere)
3. instance_group.count DEGISMEZ (her makine kendi lokal GPU'suna gore ayni sizing)
4. Redis + Postgres MERKEZI kalir (3 makine de aynisina baglanir)
5. Yonlendirici, kanal_id'ye gore hangi makinenin Triton'ina istek gonderilecegini belirler
```

Yani bu tek-instance planı, **değişmeden 3 kez kopyalanan** birim — dağıtık mimarinin karmaşıklığı, Triton config'inde değil, yönlendirme ve merkezi state (Redis/Postgres) katmanında.

---

*Bu plan, `nihai-olcekleme-plani.md`, `4050-test-protokolu.md` ve `marian-paralel-triton-ozellikleri.md` ile birlikte okunmalıdır. Bölüm 8'deki sayılar başlangıç noktasıdır — Bölüm 9'daki checklist ile doğrulanıp gerekirse ayarlanmalıdır.*