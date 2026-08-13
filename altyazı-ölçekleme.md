# 4050 Kapasite Testi — Ölçüm Protokolü ve Veri Toplama Şablonu

**Amaç:** RTX 4050 (6GB) üzerinde canlı altyazı sisteminin gerçek kapasite sınırını (kaç kanalda kırıldığını) kademeli olarak ölçmek ve production (A100) tahmini için sağlam veri toplamak.

**İlke:** Hiçbir sayıyı tahmin etme. Her değeri ölç, bu şablona kaydet. Test bitince bu doldurulmuş şablon, raporun ham verisi olur.

---

## BÖLÜM A — TEST ÖNCESİ HAZIRLIK (Bir Kere Yapılır)

### A.1 Ölçüm Araçlarının Kurulu Olduğunu Doğrula

Test sırasında şu 3 veri kaynağına ihtiyacın var. Test başlamadan önce üçünün de çalıştığını doğrula:

```
[ ] 1. GPU izleme:   nvidia-smi dmon -s um -d 1   (util + bellek, saniyede 1)
[ ] 2. Kuyruk metriği: stt_queue_depth ve stt_queue_drops_total Grafana'da görünüyor mu?
       (Eğer YOKSA, önce bunları ekle — bu metrikler olmadan test anlamsız)
[ ] 3. Gecikme logu: docker compose logs backend | grep "ALTYAZI KAPSAMA" çıktı veriyor mu?
```

### A.2 Drop Metriği Yoksa — Önce Bunu Ekle (Zorunlu)

Test sırasında sessizce atılan segmentleri göremezsen, "çalışmadı" dışında hiçbir şey öğrenemezsin. Minimum enstrümantasyon:

```python
# stt-worker içinde, kuyruğa ekleme noktasında
from prometheus_client import Counter, Gauge

queue_drops = Counter("stt_queue_drops_total", "Atilan segment", ["kanal_id"])
queue_depth = Gauge("stt_queue_depth", "Kuyruk derinligi", ["kanal_id"])

# put_nowait yaptığın yerde:
try:
    queue.put_nowait(item)
    queue_depth.labels(kanal_id=kanal_id).set(queue.qsize())
except QueueFull:
    queue_drops.labels(kanal_id=kanal_id).inc()   # ← artik gorunur
```

### A.3 Test Ses Kaynakları

En az 4 FARKLI ses dosyası hazırla (aynı dosyayı tüm kanallara basmak, karışma testini imkansız kılar):
```
[ ] ornek1.mp4  (farkli konusmaci/icerik)
[ ] ornek2.mp4
[ ] ornek3.mp4
[ ] ornek4.mp4
```
Mümkünse her birinde ayırt edici bir söz olsun ("kanal bir burada" gibi) — karışma kontrolü için.

### A.4 Baseline (Boşta) Ölçüm — Referans Noktası

Hiç kanal yokken, sadece modeller yüklüyken:
```
nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu --format=csv
```

| Metrik | Değer |
|---|---|
| Boşta VRAM kullanımı (modeller yüklü, kanal yok) | __________ MB / 6144 MB |
| Boşta GPU util | __________ % |

Bu, "modellerin kendisi ne kadar yer/güç tutuyor" referansın. Sonraki ölçümlerden bunu çıkararak "kanalların getirdiği ek yük"ü izole edersin.

---

## BÖLÜM B — KADEMELİ YÜK TESTİ PROTOKOLÜ

### B.1 Test Kademeleri

Her kademede AYNI adımları uygula. 16'da başlamıyoruz — kırılmanın NEREDE olduğunu görmek için kademeli çıkıyoruz:

```
Kademe 1:  4 kanal
Kademe 2:  8 kanal
Kademe 3: 12 kanal
Kademe 4: 16 kanal   ← senin hedef test noktan
```

### B.2 Her Kademede İzlenecek Prosedür

```
1. N kanalı başlat (ffmpeg ile RTSP push — aşağıdaki komut)
2. 2 DAKİKA bekle (sistem stabilize olsun, ilk warmup etkisi geçsin)
3. Sonraki 5 DAKİKA boyunca gözlemle ve şablonu doldur:
   - nvidia-smi dmon çıktısından: ortalama GPU util, ortalama/tepe VRAM
   - Grafana stt_queue_depth: trend YATAY mı, ARTIYOR mu? (en kritik gözlem)
   - Grafana stt_queue_drops_total: 5 dakikada kaç drop oldu?
   - backend log ALTYAZI KAPSAMA: p50 ve p95 gecikme
   - Gözle: altyazı akıyor mu, akmıyor mu, kesik kesik mi?
4. Kanalları durdur, 1 dakika bekle (kuyruk boşalsın), sonraki kademeye geç
```

Kanal başlatma komutu:
```bash
dosyalar=(ornek1.mp4 ornek2.mp4 ornek3.mp4 ornek4.mp4)
N=4   # her kademede degistir: 4, 8, 12, 16
for i in $(seq 1 $N); do
  dosya=${dosyalar[$((i % 4))]}
  ffmpeg -re -stream_loop -1 -i "$dosya" -c copy \
    -f rtsp rtsp://mediamtx:8554/test-kanal-$i > /tmp/kanal-$i.log 2>&1 &
done
echo "$N kanal baslatildi"

# Durdurmak icin:
# pkill -f "test-kanal"
```

---

## BÖLÜM C — VERİ TOPLAMA ŞABLONU (Test Ederken Doldur)

### C.1 Ana Sonuç Tablosu

| Kademe | GPU Util (ort %) | VRAM (tepe MB) | Kuyruk Trendi | Drop (5dk toplam) | p50 gecikme | p95 gecikme | Altyazı Durumu |
|--------|------------------|----------------|---------------|-------------------|-------------|-------------|----------------|
| 4 kanal  | ______ | ______ | [ ] yatay [ ] artıyor | ______ | ______ ms | ______ ms | [ ] akıyor [ ] kesik [ ] akmıyor |
| 8 kanal  | ______ | ______ | [ ] yatay [ ] artıyor | ______ | ______ ms | ______ ms | [ ] akıyor [ ] kesik [ ] akmıyor |
| 12 kanal | ______ | ______ | [ ] yatay [ ] artıyor | ______ | ______ ms | ______ ms | [ ] akıyor [ ] kesik [ ] akmıyor |
| 16 kanal | ______ | ______ | [ ] yatay [ ] artıyor | ______ | ______ ms | ______ ms | [ ] akıyor [ ] kesik [ ] akmıyor |

### C.2 Kırılma Noktası Tespiti

Yukarıdaki tabloyu doldurduktan sonra, kırılmanın nerede başladığını işaretle:

```
Sistemin STABIL çalıştığı en yüksek kanal sayısı: __________ kanal
  (kuyruk yatay, drop=0 veya çok düşük, altyazı akıyor)

Kırılmanın BAŞLADIĞI kanal sayısı: __________ kanal
  (kuyruk artmaya, drop olmaya başladı ama sistem hala kısmen çalışıyor)

Sistemin TAMAMEN çöktüğü kanal sayısı: __________ kanal
  (kuyruk patlıyor, altyazı akmıyor)
```

### C.3 Tek Segment İşleme Süresi (RTF) — A100 Tahmini İçin Kritik

Bu, production (A100) tahminini yapabilmen için EN ÖNEMLİ ölçüm. İzole olarak (tek kanal, tek segment) ölç:

```python
import time
from faster_whisper import WhisperModel

model = WhisperModel("large-v3", device="cuda", compute_type="int8_float16")
# Isınma (ölçüme katma)
list(model.transcribe("test_4sec.wav", task="translate")[0])

# Gerçek ölçüm — 10 tekrar
times = []
for _ in range(10):
    t = time.time()
    list(model.transcribe("test_4sec.wav", task="translate", beam_size=5)[0])
    times.append(time.time() - t)

avg = sum(times) / len(times)
print(f"Ortalama islem suresi (4sn segment): {avg:.2f}s")
print(f"RTF (gercek zaman katsayisi): {4.0/avg:.2f}x")
```

| Metrik | Değer |
|---|---|
| 4sn segmentin ortalama işleme süresi | __________ saniye |
| RTF (4.0 / işleme süresi) | __________ x |

### C.4 Karışma Kontrolü (Kalite Doğrulama)

16 kanal çalışırken, 3-4 farklı kanalın altyazı çıktısını yan yana aç. Bir kanalın çıktısında başka kanalın içeriği sızmış mı?

```
[ ] Karışma YOK — her kanal kendi içeriğini gösteriyor (BEKLENEN/İYİ)
[ ] Karışma VAR — kanal ___ içinde kanal ___ içeriği görüldü (KÖTÜ, not al)
```

**Not:** 4050'de tek instance + tek model senaryosunda karışma beklenmez (paralellik zaten sınırlı). Karışma görürsen, bu concurrency/state yönetiminde bir bug işaretidir ve A100'e taşımadan önce çözülmelidir.

---

## BÖLÜM D — YORUMLAMA REHBERİ (Sonuçlar Ne Anlama Geliyor)

Testi bitirince, topladığın verilere göre hangi senaryoda olduğunu belirle:

### Senaryo 1: GPU util DÜŞÜK (%20-40) ama kuyruk büyüyor
```
ANLAM: GPU boşta bekliyor ama işlem yetişmiyor → concurrency SERİLEŞİYOR (kod sorunu)
SONUÇ: Bu bir 4050 kapasite sorunu DEĞİL, bir yazılım sorunu.
       A100'e taşısan da düzelmez. Önce num_workers / çoklu-instance düzeltmesi gerekir.
RAPORA YAZILACAK: "Mimari darboğaz tespit edildi, donanımdan bağımsız, düzeltilmeli"
```

### Senaryo 2: GPU util YÜKSEK (%85-100) ve kuyruk büyüyor
```
ANLAM: GPU gerçekten dolu, kart bu yükü fiziksel olarak kaldıramıyor
SONUÇ: Bu GERÇEK bir 4050 kapasite sınırı. large-v3 bu kart için ağır.
       A100'de bu sorun OLMAYACAK (çok daha güçlü).
RAPORA YAZILACAK: "4050 kapasite sınırı N kanalda ölçüldü, A100 tahmini çarpanla yapıldı"
```

### Senaryo 3: VRAM %90+ / OOM hatası
```
ANLAM: 6GB bellek doldu
SONUÇ: Bellek sınırı. Instance sayısını/batch'i düşürmen veya medium modele geçmen gerekir.
       A100'de (40GB) bu sorun OLMAYACAK.
RAPORA YAZILACAK: "4050 VRAM sınırı, A100'de geçerli değil"
```

**Bu ayrım raporun kalbi:** Senaryo 1 (kod sorunu, her yerde geçerli) ile Senaryo 2/3 (4050'ye özel donanım sınırı, A100'de yok) tamamen farklı sonuçlar. Aynı belirti (kuyruk büyümesi) iki farklı kökten gelebilir — GPU util seni doğru köke götürür.

---

## BÖLÜM E — A100'E EKSTRAPOLASYON (Rapor İçin)

Test verilerini topladıktan sonra, production tahminini şöyle yaparsın (kaba çarpan yöntemi):

```
4050'de ölçülen stabil kanal sayısı: S_4050  (Bölüm C.2'den)
4050'de ölçülen RTF: RTF_4050  (Bölüm C.3'ten)

A100 avantaj çarpanı (kaba, compute + bant genişliği ortalaması): ~5-8x
  (KESIN DEĞİL — A100'de mutlaka yeniden ölçülmeli, bu sadece bir başlangıç tahmini)

A100 tahmini stabil kanal ≈ S_4050 × 5-8

ÖRNEK (varsayımsal): 4050'de 8 kanal stabilse → A100'de ~40-64 kanal tahmini
```

**Rapora MUTLAKA yazılacak uyarı:** Bu ekstrapolasyon bir TAHMİNDİR. A100'ün gerçek kapasitesi, ancak A100 üzerinde aynı test tekrarlanarak kesinleşir. 4050→A100 çarpanı, GPU mimarisi farkları (Tensor Core nesli, bellek tipi) nedeniyle doğrusal olmayabilir. Rapor, "100 kanal hedefi A100'de gerçekçi görünüyor ama prod'da doğrulanmalı" tonunda olmalı, "100 kanal garanti" değil.

---

## ÖZET — TEST GÜNÜ AKIŞI

```
1. Bölüm A: Araçları doğrula, drop metriğini ekle, baseline ölç          (~30 dk)
2. Bölüm C.3: Tek segment RTF ölç (izole)                                (~10 dk)
3. Bölüm B: 4→8→12→16 kademeli test, her kademede 7 dk                   (~35 dk)
4. Bölüm C: Şablonu doldur (test sırasında canlı)
5. Bölüm C.2: Kırılma noktasını belirle
6. Bölüm D: Hangi senaryodasın belirle (kod sorunu mu, donanım sınırı mı)
7. Bu doldurulmuş şablonu bana getir → gerçek verilerle raporu yazalım
```

Bu şablonu doldurup paylaştığında, tahmin içermeyen, tamamen senin ölçümlerine dayanan bir kapasite raporu yazabiliriz.