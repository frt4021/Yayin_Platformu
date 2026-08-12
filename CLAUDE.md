# Yayın Merkezi — Claude için proje notu

Bu dosya oturumlar arasında taşınan bağlam. **Son güncelleme: 12 Ağustos 2026.**

---

## Kullanıcı nasıl çalışıyor

- **Türkçe konuşuluyor.** Kod, yorum ve log mesajları da Türkçe.
- **Build ve ayağa kaldırmayı kullanıcı kontrol eder.** İstenmeden
  `docker compose build` / `up` çalıştırma; birçok kez reddedildi.
- **Ölçmeden sayı verme.** Bu projede GPU'da hiçbir ölçüm yapılmadı; daha önce
  iki kez ölçülmemiş GPU sayısı verildi ve düzeltildi. Bilmiyorsan "ölçülmedi"
  yaz.
- Kod bu makinede değiştirilir, test bazen `192.168.1.200`'de yapılır.
- Yorumlar **neden** açıklar, ne yaptığını değil. Ölçülen sayılar yoruma
  yazılır ("ölçüldü: …").

---

## Mimari — bir bakışta

```
kaynak → MediaMTX (RTSP/HLS) → tarayıcı
              │
              ├── video-worker : DVR kaydı, VAD, klip/video işleme
              └── backend      : API, WebSocket, kuyruk yönetimi
```

- **Aynı jar iki konteynerde çalışıyor** (backend + video-worker). Hangi
  bileşenin nerede açılacağı bayraklarla: `dvr.recorder-enabled`,
  `vad.enabled`, `clips.worker.enabled`. Bayrak unutulursa iş **iki kez**
  yapılır.
- **Konteynerler arası tek bağ Redis ve Postgres.** Doğrudan çağrı yok.
- Doğruluk kaynağı **her zaman veritabanı**; Redis yalnızca bildirim taşır ve
  kaybolursa yoklama toparlar.
- Quarkus + Panache + Flyway (V1–V21), Keycloak OIDC, MinIO.

---

## Canlı altyazı — bu oturumun ana konusu

### Boru hattı

```
ffmpeg(RTSP) → Silero VAD → bölüt → kuyruk(64) → stt-worker
    → Whisper(task=translate) → EN pivot → Marian → tr/de/ru
    → Postgres → Redis(altyazi:<id>) → WebSocket → tarayıcı
```

### Kritik kural

`SubtitleOverlay.tsx:131` altyazıyı **mutlak zaman damgasıyla** eşliyor:

```ts
baslangic <= playingDate() < bitis
```

**Geç kalan altyazı geç gösterilmez, HİÇ gösterilmez.** Hiçbir yerde hata
çıkmaz, ekran boş kalır, loglar temiz görünür. Bu projedeki en sinsi
davranış — altyazıyla ilgili her teşhiste önce buraya bak.

### Ölçülen kapasite (CPU, 8 çekirdek, `small`, int8)

```json
{ "audio_s": 2537.7, "processing_s": 3190.3, "translation_s": 577.5,
  "realtime_factor": 0.8 }
```

| | Değer |
|---|---|
| Çözümleme | 0,80× |
| Çeviri | 4,39× — CPU'da ve **seri** |
| **Toplam** | **0,67×** |
| Gereken | eşzamanlı çözümlenen kanal sayısı |

Yan ölçümler: `stt-worker` %413 CPU, `/health` 46,9 sn'de cevap verdi,
15 dakikada 141 bölüt kuyruktan düştü, gecikme bir ara 300 sn'ye çıktı.

Üretim gecikmesi: **p50 ~13 sn, p95 ~23 sn.**

### `ALTYAZI_HLS_GERIDE` (bu oturumda eklendi)

İzleyiciyi canlı kenardan geriye alır = **altyazının bütçesi**.

```
bütçe = ALTYAZI_HLS_GERIDE × EXT-X-TARGETDURATION
```

Zincir: `.env` → `altyazi.hls-geride` → `OynaticiAyarResource`
(`GET /api/ayarlar/oynatici`, kimlik istemiyor) → `oynaticiAyarlari.ts` →
`HlsPlayer.tsx` `liveSyncDurationCount`.

`.env` değiştir + backend'i yeniden başlat yeter; frontend imajı kurulmuyor.

Varsayılan: `yapilandir.sh` CPU'da 12, NVENC'te 5.

**Uyarılar:**

- **TARGETDURATION kanaldan kanala değişiyor** (ölçüldü: kanal1=2, kanal2=3).
  `hlsSegmentDuration` ayarlanmamış; gerçek uzunluğu kaynağın GOP'u belirliyor.
  En kısa bölütlü kanala göre seç.
- **Sıfır yazılamaz.** Oynatma listesi `PART-HOLD-BACK=0.5` ilan ediyor ve
  `lowLatencyMode` açık; kullanıcı ayarı yoksa hls.js onu kullanır ve bütçe
  yarım saniye olur. `oynaticiAyarlari.ts` sıfırı reddedip 8'e düşürür.
- Bu **gecikmeyi çözmüyor, saklıyor**. GPU'da gecikme düşünce geri indirilmeli.

### `ALTYAZI_BUTCE_MS` yanıltıcı

**Hiçbir şeyi düşürmüyor.** Yalnızca `ALTYAZI KAPSAMA` log satırındaki yüzdeyi
hesaplıyor (`SubtitleLagMetrics`). Kullanıcıya bunu birden çok kez açıklamak
gerekti.

### Redis aboneliği — düzeltildi

**Belirti:** altyazı bir kez çalışıp sessizce kesiliyordu.

```
11:36:59.227  WARN No handler waiting for message: [psubscribe, altyazi:*, 1]
11:37:40.211  INFO Altyazı aboneliği açıldı        ← 41 SANİYE bloke
```

İki sebep birlikteydi:

1. Pub/sub, klip kuyruğunun `BLMOVE`'uyla **aynı Redis havuzunu** paylaşıyordu
   → ayrı istemci: `quarkus.redis.pubsub.*` + `@RedisClientName("pubsub")`
2. Her izleyici gidişinde abonelik bırakılıp yeniden açılıyordu → desenle
   **tek** abonelik (`altyazi:*`), süreç boyunca açık

Ders: **Redis pub/sub aboneliği komut trafiğiyle havuz paylaşmamalı.**

---

## Açık işler (öncelik sırasıyla)

### 1. Yalnızca izlenen kanalı çözümle

`VadService.java:134` — `Channel.listActive()` + "yayında mı", **izleyici
koşulu yok**. Kimse bakmasa da her kanal Whisper + 3 dil çeviriden geçiyor.

Kazanç: gereken kapasite = kanal sayısı → izlenen kanal sayısı (tipik 1).
Boru hazır: `DvrSignal` aynı şekli taşıyor; backend izleyicileri biliyor
(`SubtitleBroadcaster.sessions`).

### 2. Kuyruk yanlış ucundan düşürüyor

`VadService.java:109` — `ArrayBlockingQueue<>(64)`, `.env`'den ayarlanamıyor.
`offer()` → dolduğunda **en yeni** atılıyor; `take()` → **en eski** alınıyor.
Canlı altyazıda kuyruktakiler zaten bütçesini kaçırmış olanlar.

```
64 bölüt ÷ 0,30 bölüt/sn ≈ 214 saniye   ← gecikmenin TABANI
```

Kuyruk bir kez dolduğunda GPU alsan bile 3,5 dakikanın altına inmiyor.

Önerilen (on satır): `sttDongusu` bölütü alırken yaşına bakıp bütçeyi aşanı
hiç çözümlemesin.

### 3. Çeviriyi GPU'ya al, kilidi kaldır

`stt-worker/app/translate.py`: `MarianMTModel.from_pretrained(...)` — `.to("cuda")`
**yok**; `with self._lock, torch.no_grad():` — **seri**.

**Kart ne kadar hızlı olursa olsun tavan ~4,4 kanal.** 20 kanal hedefi için şart.

### 4. DVR / klip — yazıldı, DENENMEDİ

- `DvrSignalEvent` + `DvrSignal`: Redis üzerinden `BASLAT` / `KES`
- `SegmentStream.kes()`: segmenti ilk paket sınırında kapatır
- `RecordingService.stop`: senkron doğrulama kaldırıldı, klip kuyruğa giriyor
- `ClipWorker.dvrBekle`: kaydın çizelgeye düşmesini bekleyip kırpıyor
  (`clips.dvr-bekleme`, 45s)

Sebep: 30 sn'lik segment kapanmadan çizelgeye satır yazılmıyordu; 6-8 sn'lik
manuel kayıtlar "Bu aralıkta diske yazılmış kayıt yok" veriyordu.

`SegmentStreamTest`'teki üç yeni kesme testi **koşulmadı**.

### 5. ffmpeg kaydedici çırpınıyor

Teşhis edildi, **uygulanmadı**: `Could not find codec parameters … analyzeduration (0)`.
Öneri: `-analyzeduration 5000000 -probesize 10000000`.

---

## Bu projede yaşanmış tuzaklar

| Tuzak | Belirti |
|---|---|
| Aynı jar iki konteynerde | Bayrak unutulursa her iş **iki kez** yapılır |
| `STT_MODEL`/`STT_DEVICE`/`STT_TARGET_LANGS` derleme argümanı | `.env`'de değiştirip imaj kurmazsan **hiçbir hata çıkmaz**, eski model çalışır |
| Keycloak issuer uyuşmazlığı | Host'tan alınan token `iss=localhost:8080`, backend `keycloak:8080` bekler → 401. Auth'u **konteyner içinden** yap |
| nginx `$host` vs `$http_host` | 80 dışı portta yayın akmıyordu; `proxy_redirect`'te `$scheme://$http_host` şart |
| MediaMTX master playlist | En yüksek bant genişliğini seçiyor, segment sınırını aşarsa kanal **sessizce ölü** kalıyor (`max recorded size exceeded`) |
| `ffprobe` birleştirilmiş TS'te `aac\naac` basıyor | Tam eşitlik kontrolü kaçırıyordu → 1276 baytlık boş klip **HAZIR** işaretleniyordu. İlk satır alınmalı |
| MediaMTX S3 desteklemiyor | 1.19.3 ikilisinde izi bile yok; DVR ffmpeg + MinIO SDK ile yazılıyor |
| ffmpeg MinIO'ya PUT edemiyor | Chunked PUT → HTTP 411, çıkış kodu 0, sessizce 0 baytlık nesne |

---

## Faydalı komutlar

```bash
# Altyazı kapsaması ve p95 gecikme
docker compose logs backend | grep "ALTYAZI KAPSAMA" | tail -5

# STT kapasitesi
curl -s localhost:8100/metrics | python3 -m json.tool

# Kuyruk düşmeleri (kapsama satırında GÖRÜNMEZ)
docker compose logs video-worker | grep -c "kuyruğu dolu"

# Redis'te altyazı akıyor mu
docker compose exec -T redis redis-cli psubscribe 'altyazi:*'

# Bölüt süresi (bütçe hesabı için)
curl -s http://localhost:8888/<path>/index.m3u8 | grep TARGETDURATION

# MediaMTX'te gerçekten yayında olanlar
curl -s localhost:9997/v3/paths/list | python3 -m json.tool
```

Veritabanı adı **`yayin_merkezi`** (`yayin` değil), kullanıcı `app_user`.

---

## Belgeler

| Belge | İçerik |
|---|---|
| `docs/altyazi-devir-notu.md` | Bu oturumun tam dökümü |
| `docs/altyazi-gpu-olcum.md` | Ölçüm reçetesi: belirti → hangi değer |
| `docs/altyazi-acik-isler.md` | Açık işler 1-3'ün ayrıntısı |
| `docs/altyazi-klip-video-plani.md` | Klip/video altyazısı — plan |
| `docs/olcekleme-plani.md` | Yüzlerce kanal/izleyici — plan |
| `docs/teknik-referans-modul.md` | Modül modül teknik referans |
| `README.md` → "Canlı altyazı: bütçe ve gecikme" | Özet |
