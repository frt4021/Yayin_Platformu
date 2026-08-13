# Nihai Ölçekleme Planı — Canlı Çoklu Dil Altyazı Sistemi

**Kapsam:** Mevcut sistemi (video-worker + Python stt-worker + Marian + Postgres/Redis + WS) mevcut donanımda (RTX 4050, 6GB) verimli çalışır hale getirmek, ve production (A100) ölçeğine giden yolu tanımlamak.

**Bu planın dayandığı NET bulgular (önceki analizlerden kesinleşmiş):**
- stt-worker Python, faster-whisper direkt kullanıyor, uvicorn/h11 (HTTP/1.1) sunuyor.
- Java (video-worker) → stt-worker bağlantısı: JDK HttpClient, **senkron/blocking**, ham PCM gövde, 120sn timeout.
- Java tarafı kuyruk: `Map<UUID,Deque>`, video-worker içinde, stt-worker'a görünmez.
- Whisper görevi: `task=translate`, hedef **İngilizce pivot**. Son kullanıcı pivot'u görmüyor.
- **Doğruluk esnek** — pivot "Marian'ın anlayacağı kadar" iyi olmalı, mükemmel olması gerekmez.
- **3 hedef dil (tr+de+ru) SABİT gereksinim** — hepsi her zaman üretilir, kesilemez.
- Video encode/decode (NVENC/NVDEC), Whisper'dan (SM) **ayrı donanım** — darboğaz değil, dokunulmayacak.
- Belirti: 15-16 kanalda STT kuyruğu monoton büyüyor, altyazı akmıyor.

**Raporun ana mesajı:** "Aynı donanımda (4050), kod/config optimizasyonlarıyla performans artışı — kaç kanaldan kaç kanala."

---

## AŞAMA 0 — TEŞHİS: Serileşme Nerede? (Yarım Gün, Kod Değişikliği Yok)

Ölçekleme yapmadan önce, kuyruğun neden büyüdüğünü KANITLA. Hipotez: eş zamanlı istekler bir yerde serileşiyor (ρ≥1). İki aday: Java tarafı sırayla gönderiyor, VEYA Python tarafı sırayla işliyor. Muhtemelen ikisi de.

### 0.1 Python Tarafı Serileşme Testi

stt-worker'a 6 isteği AYNI ANDA gönder, toplam süreye bak:

```python
import asyncio, aiohttp, time

async def bir_istek(session, pcm):
    async with session.post("http://stt-worker:8100/transcribe", data=pcm) as r:
        await r.read()

async def test():
    pcm = open("test_4sec.pcm", "rb").read()
    async with aiohttp.ClientSession() as s:
        # once tek istek suresi (referans)
        t = time.time(); await bir_istek(s, pcm)
        tek = time.time() - t
        # sonra 6 paralel
        t = time.time(); await asyncio.gather(*[bir_istek(s, pcm) for _ in range(6)])
        alti = time.time() - t
        print(f"tek={tek:.2f}s | 6-paralel={alti:.2f}s | oran={alti/tek:.1f}x")

asyncio.run(test())
```

**Yorum:**
```
oran ~1-1.5x  → Python paralel calisiyor. Serilesme JAVA tarafinda. (0.2'ye bak)
oran ~6x      → Python SERILESIYOR. Asama 1'deki Python duzeltmesi sart.
```

### 0.2 GPU Util Kontrolü (Eş Zamanlı)

Yük varken `nvidia-smi dmon -s um -d 1` çalıştır:
```
sm DUSUK (%20-40) + kuyruk buyuyor  → GPU bosta, serilesme var (beklenen)
sm YUKSEK (%85+) + kuyruk buyuyor   → GPU dolu, model agir (kucult — Asama 2)
```

### 0.3 Aşama 0 Çıktısı
```
[ ] Python serilesiyor mu?  EVET / HAYIR   (0.1 orani)
[ ] Java serilesiyor mu?    EVET / HAYIR   (tek thread mi cok thread mi — koda bak)
[ ] sm degeri yuk altinda:  ____%
→ Bu 3 cevap, Asama 1'de neyi once duzeltecegini belirler.
```

---

## AŞAMA 1 — SERİLEŞMEYİ KIR (1-2 Gün, En Büyük Kazanç)

Bu aşama, Triton'a geçmeden, mevcut mimaride GPU'yu kullanılır hale getirir.

### 1.1 Python: Event Loop Bloklamasını Kaldır (MUHTEMELEN EN KRİTİK)

**Sorun:** `async def transcribe` içinde `model.transcribe()` senkron/blocking. Bu, uvicorn event loop'unu bloklar — `async` olmasına rağmen istekler tek tek işlenir.

```python
from fastapi import FastAPI, Request
from faster_whisper import WhisperModel
import anyio, os

app = FastAPI()

model = WhisperModel(
    os.getenv("STT_MODEL", "small"),
    device="cuda",
    compute_type="int8_float16",
    num_workers=int(os.getenv("STT_NUM_WORKERS", "4")),   # CTranslate2 ic paralellik
)

def _blocking_transcribe(audio):
    segments, _ = model.transcribe(
        audio, task="translate",
        beam_size=int(os.getenv("STT_BEAM_SIZE", "1")),   # pivot esnek -> greedy yeter
    )
    return " ".join(s.text for s in segments)

@app.post("/transcribe")
async def transcribe(request: Request):
    pcm = await request.body()
    if not pcm:
        return {"error": "bos govde"}, 400
    audio = pcm_to_numpy(pcm)
    # KRITIK: blocking transcribe'i thread pool'a at, event loop serbest kalsin
    text = await anyio.to_thread.run_sync(_blocking_transcribe, audio)
    return {"text": text}
```

İki kritik satır: `num_workers` (CTranslate2 eş zamanlı işleme) ve `anyio.to_thread.run_sync` (event loop'u bloklamama). İkincisi olmadan `async` bir yalandır.

### 1.2 Java: Paralel Transcribe Çağrıları

Java tek thread'le sırayla gönderiyorsa, Python düzelse de serileşir. Her kanal kendi çağrısını paralel yapsın:

```java
private final ExecutorService sttExecutor =
    Executors.newFixedThreadPool(Integer.getInteger("stt.max.concurrency", 6));

void kanalSegmentiHazir(UUID kanalId, byte[] pcmSegment, long enqueueTs) {
    // stale-drop: cok eski segmenti gonderme (video ile senkron kaybolmus zaten)
    if (System.currentTimeMillis() - enqueueTs > 3000) {
        metrics.staleDropArttir(kanalId);
        return;
    }
    sttExecutor.submit(() -> {
        try {
            String pivot = sttClient.transcribe(pcmSegment);   // blocking ama ayri thread
            marianCevirParalel(kanalId, pivot);
        } catch (Exception e) {
            log.warn("STT hata kanal {}: {}", kanalId, e.getMessage());
        }
    });
}
```

### 1.3 Java ↔ Python Concurrency Eşitle

```
Java  stt.max.concurrency   = 6
Python STT_MAX_CONCURRENCY  = 6  (thread havuzu / num_workers ile uyumlu)
```
Java 20 gönderip Python 6 işlerse, 14 istek Python'da gizli kuyruklanır. İkisi eşit olmalı.

### 1.4 Marian: 3 Dili PARALEL Üret (SABİT 3 dil gereksinimi için kritik)

3 dil her zaman üretileceğine göre, bunları sıralı üretmek gecikmeyi 3x artırır. Paralel üret:

```python
import asyncio

async def marian_cevir_paralel(pivot_en, corrid):
    tr, de, ru = await asyncio.gather(
        marian_infer("en_tr", pivot_en),
        marian_infer("en_de", pivot_en),
        marian_infer("en_ru", pivot_en),
    )
    return {"tr": tr, "de": de, "ru": ru}
```
(Java tarafındaysa: 3 ayrı `CompletableFuture`, `allOf` ile birleştir.)

### 1.5 Model Küçültme (Pivot Esnek Olduğu İçin)

```
STT_MODEL=large-v3  →  STT_MODEL=small  (veya base, Asama 3'te test edilecek)
STT_BEAM_SIZE=5     →  STT_BEAM_SIZE=1  (greedy, pivot icin yeterli)
```
Beklenen kazanç: ~4-6x hız, ~4x VRAM tasarrufu. Kalite dogrulamasi Asama 3.2'de.

### 1.6 Kuyruk Görünürlüğü (Sessiz Drop'u Bitir)

Java tarafındaki `Map<UUID,Deque>` kuyruğuna metrik ekle:
```java
Gauge queueDepth = ...   // "stt_queue_depth", label: kanal_id
Counter drops = ...      // "stt_queue_drops_total", label: kanal_id
// Deque'e ekleme/cikarma noktalarinda guncelle; kapasite asiminda drops.inc()
```

### 1.7 Aşama 1 Çıkış Kriteri
```
16 kanalda:
[ ] stt_queue_depth MONOTON ARTMIYOR (yatay/osilasyon ok)
[ ] drop ~0
[ ] sm util belirgin yukseldi (0.2'deki degere gore)
[ ] altyazi akiyor, p95 gecikme olculebilir
```

---

## AŞAMA 2 — ÖLÇEKLEME MEKANİĞİ: Aynı Donanımda Nasıl Büyütülür (2-3 Gün)

Aşama 1 yangını söndürür. Bu aşama, "kaç kanala kadar çıkabilirim" sorusunun cevabını verecek ölçekleme kollarını tanımlar. **Hepsi 4050'de, donanım değiştirmeden.**

### 2.1 Ölçekleme Kolu 1 — Yatay: stt-worker Replica Sayısı

Tek stt-worker process'i yerine birden fazla, önlerinde basit yük dağıtımı:

```yaml
# docker-compose.yml
stt-worker:
  # ... mevcut config
  deploy:
    replicas: 2         # 4050 VRAM'i kucuk modelle birden fazla replica'ya izin verir
```

**VRAM matematiği (small model ile):**
```
small (int8_float16)         : ~500 MB
+ activation/batch buffer     : ~300 MB
= replica basi                : ~800 MB
6GB - 1GB(overhead) = 5GB kullanilabilir
→ 5000/800 ≈ 6 replica TEORIK, ama VAD+Marian+video da VRAM istiyor
→ GERCEKCI: 2-3 stt-worker replica (Asama 3 sweep'te dogrulanacak)
```

Yük dağıtımı: Java tarafında round-robin (`stt-worker-1:8100`, `stt-worker-2:8100`) veya bir reverse proxy (nginx upstream). Kanal ID'ye göre sabit atama (kanal 1-8 → replica 1, 9-16 → replica 2) da olur — bu, aynı kanalın hep aynı replica'ya gitmesini sağlar (context tutarlılığı için faydalı).

### 2.2 Ölçekleme Kolu 2 — Dikey: Replica İçi Concurrency

Her replica'nın `STT_NUM_WORKERS` ve `STT_MAX_CONCURRENCY` değeri. Bunu artırmak, tek replica'nın daha fazla eş zamanlı istek işlemesini sağlar — ama GPU doyunca fayda düşer.

```
Kol 1 (replica sayisi)  → bagimsiz process'ler, VRAM'e mal olur
Kol 2 (ic concurrency)  → tek process ici paralellik, GPU compute'a mal olur
```

### 2.3 Ölçekleme Kolu 3 — Model Boyutu (Kapasite ↔ Kalite)

```
base   : en hizli, en cok kanal, pivot kalitesi en dusuk
small  : dengeli (baslangic onerisi)
medium : daha yavas, daha az kanal, pivot kalitesi daha iyi
```
Pivot esnek olduğu için `base`/`small` muhtemelen yeterli — Aşama 3.2'de kanıtlanacak.

### 2.4 Ölçekleme Formülü (Kapasite Tahmini)

```
Toplam kanal kapasitesi ≈ replica_sayisi × replica_basi_kanal

replica_basi_kanal = (1 / segment_isleme_suresi) × segment_suresi × ic_concurrency × (1/aktif_oran) × 0.75
                                                                                                    └─ rho<0.75 guvenlik payi

ORNEK (small, olculecek degerlerle doldurulacak):
  segment_isleme_suresi = 0.3s (small, 4050 — Asama 3'te olc)
  segment_suresi = 4s
  ic_concurrency = 4
  aktif_oran = 0.4
  → replica_basi = (1/0.3) × 4 × 4 × (1/0.4) × 0.75 ≈ 40 kanal (TEORIK, iyimser)
  → 2 replica ile teorik ~80 kanal — ama bu ust sinir, gercek Asama 3 sweep'te
```

---

## AŞAMA 3 — TEST: Önce/Sonra Ölçüm (Rapor Verisi) (2-3 Gün)

Raporun "performans artışı" iddiası, önce/sonra karşılaştırması gerektirir.

### 3.1 Baseline vs Optimize Karşılaştırma

| Ölçüm | ÖNCE (large-v3, blocking, seri) | SONRA (small, async, paralel) |
|---|---|---|
| Stabil kanal sayisi | ______ | ______ |
| 16 kanalda kuyruk trendi | ______ | ______ |
| 16 kanalda drop/dk | ______ | ______ |
| sm util (16 kanal) | ______% | ______% |
| p95 gecikme (16 kanal) | ______ ms | ______ ms |
| Tek segment RTF | ______x | ______x |

Bu tablo raporun **kalbi** — "aynı donanım, şu değişikliklerle N→M kanal" kanıtı.

### 3.2 Pivot Kalite Doğrulaması (small yeterli mi?)

```
10-15 tipik cumle (Turkce+Rusca), her modelle pivot uret, Ingilizce ciktiyi oku:
[ ] base  : anlam korunuyor mu? (Marian bunu dogru cevirebilir mi?)
[ ] small : anlam korunuyor mu?
[ ] medium: (referans)
→ Anlami koruyan EN KUCUK modeli sec. WER degil, ANLAM korunumu bak.
```

### 3.3 Kapasite Sweep (Gerçek Tavan)

```
Secilen model + optimize config ile: 8 → 16 → 24 → 32 kanal kademeli
Her kademede: kuyruk trendi, drop, sm util, p95 gecikme
→ Kirilma noktasi = 4050'nin bu config'teki gercek tavani
```

### 3.4 Test Scripti (kademeli yük)

```python
import asyncio, aiohttp, time
async def kanal_dongusu(session, pcm, stop, lat):
    while time.time() < stop:
        t = time.time()
        async with session.post("http://stt-worker:8100/transcribe", data=pcm) as r:
            await r.read()
        lat.append(time.time()-t)
        await asyncio.sleep(4)   # gercekci segment araligi

async def yuk(n, sure=120):
    pcm = open("test_4sec.pcm","rb").read(); lat=[]; stop=time.time()+sure
    async with aiohttp.ClientSession() as s:
        await asyncio.gather(*[kanal_dongusu(s,pcm,stop,lat) for _ in range(n)])
    lat.sort(); print(f"kanal={n} p50={lat[len(lat)//2]*1000:.0f}ms p95={lat[int(len(lat)*0.95)]*1000:.0f}ms")

async def sweep():
    for n in [8,16,24,32]: await yuk(n)
asyncio.run(sweep())
```

---

## AŞAMA 4 — AŞIRI YÜK MÜDAHALESİ (Runbook) (1 Gün)

Kapasite aşıldığında ne yapılır. Sırayla, en erken müdahaleden son çareye.

```
Katman 1 — ALARM: kuyruk turevi pozitif (deriv(stt_queue_depth[5m])>0) 5dk → operatore uyari
Katman 2 — BACKPRESSURE: aktif_kanal >= tavan → yeni kanali REDDET (503 + Retry-After)
                          Mevcut kanallar korunur, yeni yuk alinmaz.
Katman 3 — STALE-DROP AGRESIF: eski segment esigi 3sn→1.5sn → kuyruk hizli bosalir,
                                altyazi bazi yerde atlar ama CANLI KENARA yetisir
Katman 4 — MODEL DOWNGRADE: small→base gecici → kapasite anlik artar, pivot kalitesi biraz duser
```

**Not:** Onceki planlardaki "selektif dil kesme" (en az izlenen dili kes) katmani KALDIRILDI —
3 dil sabit gereksinim, izlenme sinyali yok, kesilemez.

**Ilke:** Her mudahale LOGLANIR + METRIK yayinlar. Sessiz dusurme YASAK (mevcut sorunun kokuydu).
Yuk dusunce geri donus otomatik ama HYSTERESIS ile (flapping onleme).

---

## AŞAMA 5 — PRODUCTION'A GEÇİŞ (A100) — Sadece Değişenler

4050'de doğrulanan mimari A100'de aynı kalır. Sadece şu değerler A100'de YENİDEN ölçülür:

| Ne | 4050 (dev) | A100 (prod) — yeniden ölç |
|---|---|---|
| STT_MODEL | small (zorunlu, kucuk kart) | medium/large-v3 denenebilir (VRAM bol, kalite artar) |
| stt-worker replica | 2-3 (VRAM sinirli) | çok daha fazla (40GB) VEYA Triton'a geç |
| ic concurrency | 4-6 | sweep ile yeniden bul (yuksek cikar) |
| Kapasite | Asama 3 sweep sonucu | A100'de sweep TEKRAR (carpanla tahmin YETMEZ) |

**A100'de Triton kararı:** 4050'de manuel replica + async yeterli. A100'de 100 kanal hedefi için
Triton (dynamic + sequence batching, CORRID=kanal_id) manuel replica'dan daha verimli olur —
ama bu ayri bir asama, once 4050 optimizasyonu kanitlanmali.

---

## ÖZET — UYGULAMA SIRASI

```
1. [Asama 0] 6-paralel-istek testi + nvidia-smi dmon → serilesme nerede? (yarim gun)
2. [Asama 1.1] Python: anyio.to_thread + num_workers → event loop bloklamasini kaldir
3. [Asama 1.5] STT_MODEL=small + beam_size=1 → pivot esnek, hiz icin
4. [Asama 1.2-1.3] Java: ExecutorService paralel + concurrency esitle
5. [Asama 1.4] Marian 3 dil paralel
6. [Asama 1.6] Kuyruk metrigi (drop gorunur)
7. [Asama 1.7] 16 kanalda kuyruk YATAY mi? → yangin sondu mu?
8. [Asama 3.1] ONCE/SONRA tablosu doldur → rapor verisi
9. [Asama 3.2-3.3] Pivot kalite + kapasite sweep → gercek tavan
10.[Asama 4] Asiri yuk runbook kur
11.[Asama 5] A100'e gecis (yeniden olc)
```

**En kritik tek adım:** Adım 2 (anyio.to_thread). Blocking transcribe event loop'u bloklarsa,
diger her optimizasyon bosuna — cunku Python zaten tek tek isliyordur. Once bunu duzelt, olc, sonra devam.

---

*Bu plan, önceki tüm analizlerin (WhisperLive mimarisi, Triton batching, kuyruk teorisi, VRAM/KV-cache hesabı, blocking HTTP mimarisi tespiti, pivot İngilizce + esnek doğruluk, 3 dil sabit gereksinim) senin gerçek sistemine uygulanmış nihai halidir. Tüm sayısal değerler başlangıç noktasıdır; Aşama 3 testleriyle kesinleştirilecektir.*