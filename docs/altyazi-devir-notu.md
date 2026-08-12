# Canlı altyazı — devir notu

**12 Ağustos 2026.** Bir oturumluk teşhis ve düzeltmelerin özeti. Amaç:
başka bir makinede (GPU'lu) kaldığı yerden devam edebilmek.

> Bu belgedeki **her sayı ölçüldü.** GPU'ya dair hiçbir sayı yok — bu projede
> NVIDIA kartı üzerinde hiçbir ölçüm yapılmadı.

---

## 1. Tek cümlelik özet

Altyazı boru hattının **tamamı çalışıyor**; altyazı tarayıcıya **ulaşıyor**
ama **ekrana basılmıyor**, çünkü izleyici o saniyeyi geçtikten sonra
üretiliyor.

---

## 2. Bulunan üç ayrı sorun

### 2.1 Redis aboneliği kırıktı — DÜZELTİLDİ

**Belirti:** altyazı bir kez çalışıp sessizce kesiliyordu. Üretim, çeviri ve
Redis'in üçü de sağlamdı; hiçbir yerde hata yoktu.

**Ölçülen:**

```
11:36:59.227  WARN io.vertx...RedisStandaloneConnection
              No handler waiting for message: [psubscribe, altyazi:*, 1]
11:37:40.211  INFO Altyazı aboneliği açıldı          ← 41 SANİYE sonra
```

Abonelik dönene kadar tek bir mesaj dağıtılmadı.

**İki sebep birlikteydi:**

| Sebep | Düzeltme |
|---|---|
| Pub/sub, klip kuyruğunun `BLMOVE`'uyla aynı Redis havuzunu paylaşıyordu | Ayrı istemci: `quarkus.redis.pubsub.*` |
| Her izleyici gidişinde abonelik bırakılıp yeniden açılıyordu | Desenle **tek** abonelik (`altyazi:*`), süreç boyunca açık |

Dosyalar: `SubtitleBroadcaster.java`, `SubtitleEvent.java`,
`application.properties`.

**Doğrulandı:** bağlan → kop → yeniden bağlan turlarında mesaj akıyor.

### 2.2 Altyazı ekrana basılmıyor — GEÇİCİ ÇÖZÜM VAR

`SubtitleOverlay.tsx:131`:

```ts
baslangic <= playingDate() < bitis
```

Altyazı, izleyici o saniyeye **varmadan** hazır olmalı. Geç kalan altyazı geç
değil **hiç** gösterilmiyor.

```
üretim gecikmesi  p50 ~13 sn, p95 ~23 sn
bütçe             6 sn  (liveSyncDurationCount sabit 3 × TARGETDURATION 2)
```

**Çözüm:** bütçe `.env`'den ayarlanabilir hâle getirildi →
`ALTYAZI_HLS_GERIDE`. Bkz. §4.

**Bu gecikmeyi çözmüyor, saklıyor.** İzleyici o kadar geriden izliyor.

### 2.3 STT yetişmiyor — AÇIK

```json
{ "audio_s": 2537.7, "processing_s": 3190.3, "translation_s": 577.5,
  "realtime_factor": 0.8, "model": "small", "device": "cpu" }
```

| Ölçüm | Değer |
|---|---|
| Çözümleme | 0,80× |
| Çeviri | 4,39× — **CPU'da ve seri** |
| **Toplam** | **0,67×** |
| Gereken | eşzamanlı çözümlenen kanal sayısı (o an 2) |

Yan bulgular:

- `stt-worker` %413 CPU (8 çekirdek), `/health` **46,9 saniyede** cevap verdi
- 15 dakikada **141** bölüt kuyruktan düştü
- Bir ara gecikme **300 saniyeye** çıktı

---

## 3. Sistem şu an nerede

**Yığın kapalı.** Kod commit edilmiş (`2ff5416`).

| Değişiklik | Kod | İmaj | Ayakta |
|---|---|---|---|
| Tek abonelik (`altyazi:*`) | ✓ | ✓ | daha önce doğrulandı |
| Ayrı pub/sub Redis istemcisi | ✓ | ✓ | **hayır** |
| `ALTYAZI_HLS_GERIDE` | ✓ | **hayır** | hayır |
| DVR kesme sinyali + klip beklemesi | ✓ | ✓ (backend, video-worker) | **hayır** |

`SegmentStreamTest`'e eklenen üç kesme testi **koşulmadı**.

---

## 4. `ALTYAZI_HLS_GERIDE` — yeni alan

### Ne yapıyor

İzleyiciyi canlı kenardan geriye alıyor. Ne kadar geriden izlerse, altyazının
yetişmek için o kadar süresi var.

```
bütçe = ALTYAZI_HLS_GERIDE × EXT-X-TARGETDURATION
```

### Zincir

```
.env ALTYAZI_HLS_GERIDE
  → application.properties  altyazi.hls-geride
  → OynaticiAyarResource    GET /api/ayarlar/oynatici     (kimlik istemiyor)
  → oynaticiAyarlari.ts     uygulama açılmadan bir kez
  → HlsPlayer.tsx           liveSyncDurationCount
```

`.env` değiştirip **backend'i yeniden başlatmak yeterli** — frontend imajı
kurulmuyor, tarayıcı yenilenince alıyor.

### Değeri seçme

```bash
# 1) p95 gecikmeyi oku  ("(varsayım)" yazıyorsa önce bir sekmede kanalı aç)
docker compose logs backend | grep "ALTYAZI KAPSAMA" | tail -5

# 2) En kısa bölütü öğren
curl -s http://localhost:8888/<path>/index.m3u8 | grep TARGETDURATION

# 3) Böl, yukarı yuvarla, pay ekle
#    22,7 sn / 2 sn = 11,35  →  12
```

### ⚠️ TARGETDURATION kanaldan kanala değişiyor

```
kanal1 -> TARGETDURATION 2    (EXTINF 1.96)
kanal2 -> TARGETDURATION 3    (EXTINF 2.93)
```

`mediamtx.yml`'de `hlsSegmentDuration` ayarlanmamış; varsayılan bir **alt
sınır** ve gerçek bölüt uzunluğunu kaynağın anahtar kare aralığı belirliyor.
**En kısa bölütlü kanala göre seçin.**

### ⚠️ Sıfır yazmayın

Oynatma listesi `PART-HOLD-BACK=0.5` ilan ediyor ve `lowLatencyMode` açık.
Kullanıcı ayarı verilmezse hls.js **onu** kullanıyor, bütçe yarım saniyeye
düşüyor. `oynaticiAyarlari.ts` sıfır ve negatifi reddedip 8'e düşürüyor.

### Varsayılanlar

`yapilandir.sh`: CPU'da **12**, NVENC bulursa **5**.

---

## 5. GPU'lu makinede ilk adımlar

```bash
git pull

./yapilandir.sh --zorla          # STT_DEVICE=cuda, STT_RUNTIME=nvidia,
                                 # large-v3, int8_float16, ALTYAZI_HLS_GERIDE=5

docker compose build stt-worker  # ← ATLAMAYIN
./baslat.sh
```

**İmaj kurulumu şart.** Taban imaj ve torch sürümü `STT_DEVICE`'tan türüyor
(`stt-worker/Dockerfile:17-29`). Kurulmazsa CPU imajı kalır, GPU **sessizce**
kullanılmaz — hiçbir hata görmezsiniz, yalnızca yavaş olur.

Doğrulama:

```bash
curl -s localhost:8100/metrics | grep device     # "cuda" yazmalı
docker exec stt-worker nvidia-smi                # kartı görmeli
```

Beş dakika sonra:

```bash
curl -s localhost:8100/metrics | python3 -m json.tool
docker compose logs backend | grep "ALTYAZI KAPSAMA" | tail -5
```

`realtime_factor` **çözümlenen kanal sayısını geçmeli.** Geçiyorsa birikme
durur, p95 oturur ve `ALTYAZI_HLS_GERIDE` indirilebilir.

### 4050 için not

**Ölçülmedi.** Bilinen tek sert sınır VRAM (6 GB):

| | `int8_float16` | `float16` |
|---|---|---|
| `large-v3` | ~1,6 GB | ~3,1 GB |
| Marian (dil başına) | ~0,3 GB | ~0,3 GB |

Whisper modeli **tekil** (`stt.py:34`), eşzamanlılık semaforla sınırlanıyor —
`STT_MAX_CONCURRENCY` model ağırlığını çoğaltmıyor.

**Çeviri GPU'ya hiç gitmiyor** (§7.2). Kart ne kadar hızlı olursa olsun
**tavan ~4,4 kanal.**

---

## 6. `.env` alanları — ne yapar, değiştirirsen ne olur

### Yeniden kurulum isteyenler

`docker-compose.yaml:257-263` bunları derleme argümanı olarak da geçiriyor:
**`STT_MODEL`, `STT_DEVICE`, `STT_TARGET_LANGS`**. Kurmadan değiştirirsen
hiçbir hata almazsın, eski model çalışmaya devam eder.

### Ses → bölüt

| Alan | Etki |
|---|---|
| `VAD_MAX_SEGMENT_MS=6000` | Zorla kesim süresi. Kısaltmak gecikmeyi düşürür **ama kapasiteyi de** — Whisper kodlayıcısı ne verirsen ver 30 sn'lik pencerede çalışıyor (ölçüldü: ort. bölüt 4,5 sn, maliyet 5,67 sn) |
| `VAD_MIN_SILENCE_MS=400` | "Cümle bitti" eşiği. Kısaltmak nefes aralarında cümleyi böler |
| `VAD_MIN_EMIT_MS=0` | Yayınlanmadan önceki en kısa uzunluk. Artırmak (1500) kısa bölütlerin kuyruğu tıkamasını engeller |
| `VAD_OVERLAP_MS=800` | Zorla kesimde örtüşme. Artırmak bağlamı korur ama **aynı sesi iki kez çözümler** |
| `VAD_MAX_CHANNELS=20` | Kaç kanal işlensin — yükü doğrudan böler |

### Bölüt → metin

| Alan | Etki |
|---|---|
| `STT_MODEL=small` | Kalite ↔ hız. **İmaj kurulumu ister** |
| `STT_DEVICE=cpu` | `cuda` ile GPU. **İmaj kurulumu ister** |
| `STT_COMPUTE_TYPE=int8` | Hassasiyet. GPU'da `int8_float16`. Kalite etkisi **ölçülmedi** |
| `STT_BEAM_SIZE=5` | Aday sayısı. **`1` en ucuz hız kazancı** — model küçültmeden |
| `STT_BATCH_SIZE=8` | Yalnızca `>1` **ve** `BatchedInferencePipeline` varsa etkili (`stt.py:61-73`). CPU'da neredeyse etkisiz |
| `STT_MAX_CONCURRENCY=2` | Eşzamanlı çözümleme. CPU'da artırmak **zarar verir** |
| `STT_TARGET_LANGS=tr,de,ru` | **Kapasiteyi en ucuz artıran alan.** Üç dil = 1,03 sn/bölüt; tek dil ≈ 0,34. **İmaj kurulumu ister** |
| `VAD_STT_ENABLED=true` | `false` → VAD çalışır, çözümleme yapılmaz |
| `STT_RUNTIME=runc` | Docker çalışma zamanı. `STT_DEVICE` ile **birlikte** değişmeli |

### Görünürlük

| Alan | Etki |
|---|---|
| `ALTYAZI_HLS_GERIDE=12` | **Altyazının görünüp görünmemesini belirleyen tek alan** |
| `ALTYAZI_BUTCE_MS=8000` | ⚠️ **Hiçbir şey yapmıyor** — yalnızca rapor satırındaki yüzdeyi hesaplıyor |
| `ALTYAZI_RAPOR_ARALIGI=60s` | Özet log sıklığı |

### Sıkışıksa sırayla

```
1. STT_TARGET_LANGS=tr        imaj kurulumu, en büyük kazanç
2. STT_BEAM_SIZE=1            anında, ücretsiz
3. VAD_MIN_EMIT_MS=1500       kısa bölütler kuyruğu tıkamasın
4. VAD_MAX_CHANNELS=1         test için
5. STT_MODEL=tiny             imaj kurulumu, kalite belirgin düşer
```

**Tek seferde tek değer**, sonra 5 dakika bekle.

---

## 7. Açık işler

### 7.1 Yalnızca izlenen kanalı çözümle

`VadService.java:134` — `Channel.listActive()` + "yayında mı". **İzleyici
koşulu yok.** Kimse bakmasa da her kanal VAD + Whisper + 3 dil çeviriden
geçiyor.

Kazanç: gereken kapasite = kanal sayısı → **izlenen kanal sayısı** (tipik 1).

Boru hazır: `DvrSignal` (`src/main/java/org/example/dvr/DvrSignal.java`) aynı
şekli taşıyor. Backend izleyicileri biliyor
(`SubtitleBroadcaster.sessions`).

### 7.2 Çeviriyi GPU'ya al, kilidi kaldır

`stt-worker/app/translate.py`:

```python
model = MarianMTModel.from_pretrained(path, ...)   # .to("cuda") YOK
with self._lock, torch.no_grad():                  # SERİ
```

Kazanç: ~4,4 kanallık tavanı kaldırır. 20 kanal hedefi için **şart**.

### 7.3 Kuyruk yanlış ucundan düşürüyor

`VadService.java:109` — `ArrayBlockingQueue<>(64)`, `.env`'den
**ayarlanamıyor**. `offer()` kullanıldığı için dolduğunda **en yeni gelen**
atılıyor; çıkış `take()` ile **en eski**.

Canlı altyazıda kuyruktakiler bütçesini kaçırmış olanlar, düşürülen ise
yetişme şansı olan tek bölüt. **Sistem tam olarak yanlış olanları saklıyor.**

Ve derinlik gecikmenin tabanını koyuyor:

```
64 bölüt ÷ 0,30 bölüt/sn ≈ 214 saniye
```

Ölçülen gecikme 280-320 sn'ydi. Kuyruk bir kez dolduğunda **GPU alsan bile**
gecikme 3,5 dakikanın altına inmiyor.

Önerilen düzeltme (on satır): `sttDongusu` bölütü alırken yaşına bakıp
bütçeyi aşanı hiç çözümlemesin. Kuyruk kendiliğinden taze kalır.

Ayrıntı: `docs/altyazi-acik-isler.md`.

### 7.4 DVR / klip — bu oturumda yazıldı, denenmedi

- `DvrSignalEvent` + `DvrSignal`: Redis üzerinden `BASLAT` / `KES`
- `SegmentStream.kes()`: segmenti ilk paket sınırında kapatıyor
- `RecordingService.stop`: artık senkron doğrulama yok, klip kuyruğa giriyor
- `ClipWorker.dvrBekle`: kaydın çizelgeye düşmesini bekleyip kırpıyor
  (`clips.dvr-bekleme`, varsayılan 45s)

Sebep: 30 sn'lik segment kapanmadan çizelgeye satır yazılmıyordu; 6-8 sn'lik
manuel kayıtlar **"Bu aralıkta diske yazılmış kayıt yok"** veriyordu.

**Denenmedi.** `SegmentStreamTest`'teki üç yeni test de koşulmadı.

---

## 8. İlgili belgeler

| Belge | İçerik |
|---|---|
| `docs/altyazi-gpu-olcum.md` | Ölçüm reçetesi: hangi belirti → hangi değer |
| `docs/altyazi-acik-isler.md` | §7.1-7.3'ün ayrıntısı |
| `docs/altyazi-klip-video-plani.md` | Klip ve yüklenen videolara altyazı — plan |
| `docs/olcekleme-plani.md` | Yüzlerce kanal / izleyici — plan |
| `README.md` → "Canlı altyazı: bütçe ve gecikme" | Özet |

⚠️ **Bu belgelerin dördü de `git` tarafından izlenmiyor.** Diğer makineye
gitmeleri için önce eklenmeleri gerekiyor:

```bash
git add docs/*.md && git commit -m "altyazı belgeleri"
```

`.env` **gitignore'da** — yeni makinede `./yapilandir.sh` üretecek.
