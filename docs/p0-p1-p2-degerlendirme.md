# P0/P1/P2 planı — doğrulama, uygulama ve gerekçe

Sana gelen dışarıdan plan bu projenin gerçek koduna bakmadan yazılmış —
bazı maddeler zaten yapılmış, biri bilerek atlanmış bir tasarım kararını
tersine çevirmeyi öneriyor, biri de ölçmeden büyük bir altyapı değişikliğine
gidiyor. Aşağıda her madde, gerçek kodla karşılaştırılmış hâliyle.

---

## P0.1 — "faster-whisper'a geç" → **zaten yapılmış**

`stt-worker/app/stt.py:45,54-59`:

```python
from faster_whisper import WhisperModel
...
self._model = WhisperModel(
    path,
    device=SETTINGS.device,
    compute_type=SETTINGS.compute_type,   # int8_float16
    local_files_only=True,
)
```

Bu proje zaten CTranslate2 tabanlı `faster-whisper` kullanıyor, ham/naif
`whisper.load_model` değil. Üstüne `BatchedInferencePipeline` de zaten var
(`stt.py:70-71`) — tek sesin kendi içindeki bölütleri yığınlıyor.

**Ölçülen kanıt** (bu oturumda, canlı sistemde): tek segment işleme süresi
800ms–2200ms, 1-4 saniyelik ses için — yani **gerçek zamandan 1,6-3,6× daha
hızlı.** Whisper'ın kendisi yavaş değil; öneri gereksiz.

---

## P0.2 — "Tek consumer'ı çoğalt" → **kısmen var, ek fayda şüpheli**

Zaten var: `VadService.java` içinde 2 gönderici iş parçacığı
(`ensureSttPool`, `Executors.newFixedThreadPool(2, ...)`) ve stt-worker
tarafında `Semaphore(STT_MAX_CONCURRENCY=4)` — aynı anda 4 bölüt GPU'da.

**Neden ek Whisper process'i açmak muhtemelen işe yaramaz:** darboğaz iş
parçacığı sayısı değil, GPU'nun hesap gücü. 16 kanalda GPU ölçülen
kullanım **%100**; 1 kanalda bazen **%0**. Yani mevcut 4 eşzamanlılık
GPU'yu doldurabiliyor — ek process açmak aynı GPU'yu daha çok isteğin
paylaşmaya çalışması demek, gerçek paralellik değil. Asıl soru
`STT_MAX_CONCURRENCY`'nin 4'ten yükseltilip yükseltilemeyeceği (VRAM'e
bağlı, ölçülmeli) — bkz. sonuç bölümü.

---

## P0.3 — "Kuyruk boyutunu büyütme" → **zaten yapılmıyor, aynı fikirdeyiz**

Bu oturumdan önce de aynı sonuca ulaşılmıştı (`docs/gecikme-onlemleri`
notları). Dokunulmadı.

---

## P1.1 — Kanal-bazlı kuyruk + stale-drop → **bu oturumda uygulandı**

`src/main/java/org/example/VAD/VadService.java` değişti. Önceki tasarım:
tek paylaşımlı `ArrayBlockingQueue<64>`, dolunca **yeni** bölüt reddedilir
(`offer()`), tüketim hep **en eskiden** (`take()`) — konuşkan bir kanal
kapasitenin tamamını doldurup sessiz kanalların taze bölütünü de kapıda
reddettirebiliyordu ("head-of-line blocking").

**Yeni tasarım:**

```java
private static final int KANAL_KUYRUK_KAPASITESI = 4;
private final Map<UUID, Deque<SpeechSegment>> bekleyen = new ConcurrentHashMap<>();
private final BlockingQueue<UUID> hazir = new LinkedBlockingQueue<>();
```

- **Kanal başına ayrı, küçük kuyruk** (kapasite 4) — bir kanalın tavanı
  diğerini etkilemiyor.
- **Yaş kontrolü, kuyruğa girmeden önce** (`kuyrugaEkle`): bölüt zaten
  `altyazi.butce-ms` (`ALTYAZI_BUTCE_MS`, varsayılan 8000ms) bütçesini
  aşmışsa hiç kuyruğa alınmıyor — işlense de mutlak zaman damgası
  eşleşmesi (`SubtitleOverlay.tsx`) onu asla yakalayamayacağı için GPU'yu
  boşuna harcamamak için.
- **Dolduğunda en eski atılıyor, yeni giriyor** — önceki davranışın tam
  tersi.
- **Round-robin tüketim** (`hazir` kuyruğu + `kanaldanAl`): bir kanal
  işlendikten sonra kuyruğunda başka bölüt kaldıysa sıranın **sonuna**
  yeniden ekleniyor, böylece art arda gelen bölütler aynı kanaldan olsa
  bile diğer kanallar aradan geçebiliyor.
- Kanal durunca (`stop()`) kendi kuyruğu da temizleniyor — bellek sızıntısı
  olmasın diye.

Derleme doğrulandı (`./mvnw compile`, çıkış kodu 0). **GPU üzerinde henüz
canlı test edilmedi** — sürücü uyuşmazlığı yüzünden makine şu an GPU
erişemiyor (aşağıda not).

---

## P1.2 — "Postgres'i kritik yoldan çıkar" → **bilerek uygulanmadı**

Kod, bu sıralamayı **bilinçli** seçmiş — `VadService.kaydet()` içindeki
yorum:

```java
// Once veritabani, SONRA yayin. Ters sirada olsaydi izleyici
// altyaziyi gorur ama sayfayi yenilediginde kaybolurdu.
```

Yani sıralama zaten "Postgres → Redis" değil "önce garantile, sonra
göster" mantığı. Redis'i öne almak, izleyicinin gördüğü bir altyazının
sayfa yenilendiğinde kaybolabilmesi riskini getirir — **tutarlılık
kaybı** — karşılığında kazanılacak şey tek bir yerel Postgres `INSERT`
süresi (tipik olarak birkaç milisaniye), yani şu anki 90 saniyelik
sorunun yanında ölçülemeyecek kadar küçük bir kazanç.

**Öneri: değiştirilmesin**, tersini destekleyen bir ölçüm çıkmadıkça.
İstersen bu INSERT'in gerçek süresini ölçüp raporlayabilirim — ama şu ana
kadarki her ölçüm gecikmenin GPU kuyruğunda biriktiğini gösteriyor,
Postgres'te değil.

---

## P2 — Triton'a geçiş → **bilerek uygulanmadı, ölçüm önce gerekiyor**

Bu, tek oturumda alınacak bir karar değil ve şu an **VRAM'e sığmayabilir**:
6 GB'lık kartta `large-v3` (~1,6 GB) + 3 dil Marian (~0,9 GB) zaten ~2,5 GB
kullanıyor; Triton sunucusunun kendi ek yükü (server süreç belleği, model
repository yönetimi, dynamic batching tamponları) buna eklenince kalan
alan bilinmiyor — ölçülmeden taahhüt edilecek bir şey değil.

Ayrıca bu proje **zaten aynı fikri not etmiş**, kendi kodunda:

`stt.py:61-68`:
```python
# Asil kazanc KANALLAR ARASI yiginlamada: 20 kanalin bolutleri
# tek yiginda toplanirsa GPU bosta beklemez. O, istek duzeyinde
# bir kuyruk gerektiriyor ve HENUZ YOK -- kart geldiginde
# olculup eklenecek.
```

Yani "kanallar arası batching" fikri (Triton'ın `dynamic_batching` +
`sequence_batching` ile çözeceği şey) zaten planlanmış, bilerek
**ölçülmeden ertelenmiş.** Bu projenin genel ilkesiyle de örtüşüyor
(`CLAUDE.md`: "Ölçmeden sayı verme").

**Öneri:** Triton'a geçmeden önce şunu ölç: `STT_MAX_CONCURRENCY`'yi
kademeli yükseltip (`nvidia-smi` ile VRAM/util izleyerek) throughput'un
nerede düzleştiğini bul. Eğer 4'ten 8'e çıkarmak zaten belirgin fayda
veriyorsa, Triton'ın karmaşıklığına gerek kalmayabilir. Fayda düzleşiyorsa
o zaman Triton'ın dynamic batching'i gerçek bir kazanç sağlar — ama bu
karar ölçümden sonra verilmeli.

---

## Marian sıralı mı paralel mi — **sıralı, biliniyor**

Bu oturumda zaten incelendi: `translate.py`'da tek segmentin TR/DE/RU
çevirisi `translate()` içindeki `for` döngüsüyle **sıralı** çalışıyor
(aynı thread'de, biri bitmeden diğeri başlamıyor). Bu oturumda değişen şey
bu değildi — **farklı segmentler/kanallar arasındaki** kilit paylaşımı
kaldırıldı (dil başına ayrı kilit + GPU). Tek bir segmentin 3 dilini
gerçekten paralel yapmak (senin örneğindeki `asyncio.gather` fikri, burada
`threading` karşılığıyla) istersen ayrı bir değişiklik — GIL,
`model.generate()` gibi C-uzantılı ağır işler sırasında serbest kaldığı
için thread'lerle gerçek kazanç mümkün, ama henüz yapılmadı.

---

## İstediğin 3 sayı

| # | Soru | Cevap |
|---|---|---|
| 1 | Kaç kanalda drop başlıyor? | **Tam eşik ölçülmedi.** İki uç nokta var: 1 kanal → %0 drop, 16 kanal → ~%78 drop. Ara değerler (2, 4, 8, 12) test edilmedi — GPU şu an erişilemez durumda (aşağıya bak). |
| 2 | Whisper `large-v3` tek segment süresi | **Ölçüldü:** 800ms–2200ms, 1-4 sn'lik ses için (1,6×–3,6× gerçek zaman). Whisper hızlı — darboğaz burada değil. |
| 3 | Marian sıralı mı paralel mi? | **Sıralı** (yukarıda). |

---

## Şu anki blokaj

GPU'ya erişim şu an **kapalı**: `unattended-upgrades` bu sabah NVIDIA
sürücüsünü diskte güncelledi (`580.126.09` → `580.173.02`) ama makine
yeniden başlatılmadı — çalışan kernel modülü ile diskteki kütüphaneler
uyuşmuyor (`Driver/library version mismatch`). #1'i ölçmek ve P1.1'i canlı
test etmek için **önce reboot gerekiyor.**

---

## Özet — ne değişti, ne değişmedi

| Madde | Durum |
|---|---|
| P0.1 faster-whisper | Zaten yapılmıştı, dokunulmadı |
| P0.2 çoklu consumer | Yapılmadı — muhtemelen fayda vermez, gerçek kısıt GPU hesabı |
| P0.3 kuyruk büyütme | Zaten yapılmıyor |
| P1.1 kanal-bazlı kuyruk + stale-drop | **Uygulandı**, derlendi, GPU testi bekliyor |
| P1.2 Postgres'i kritik yoldan çıkar | Bilerek yapılmadı — tutarlılık riski, kazanç ölçülmedi |
| P2 Triton | Bilerek yapılmadı — VRAM riski, önce ölçüm gerekiyor |
| Marian paralelleştirme (3 dil, tek segment) | Yapılmadı, ayrı bir karar |
