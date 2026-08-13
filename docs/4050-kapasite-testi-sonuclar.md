# 4050 Kapasite Testi — Doldurulmuş Sonuç Şablonu

**Test tarihi:** 13 Ağustos 2026. **Donanım:** RTX 4050 Laptop, 6141 MB VRAM.
**Yazılım durumu:** Aşama 0-1 optimizasyonları (bkz. `ölçekleme.md`) uygulanmış
hâlde ölçüldü — `STT_MODEL=small`, `STT_BEAM_SIZE=1`, `STT_MAX_CONCURRENCY=6`,
Python event-loop bloklaması kaldırılmış, Marian 3 dili paralel, kanal başına
kuyruk + stale-drop.

**Metodoloji notu — plan iki yerde uyarlandı:**
1. **A.3 (4 farklı ses dosyası)** → hazır dosya yoktu; onun yerine **16 farklı
   gerçek TRT/DW kanalı** kullanıldı (halihazırda çalışan, birbirinden
   tamamen farklı gerçek içerik — karışma testi açısından eşdeğer, hatta
   daha gerçekçi).
2. **A.2 (drop/kuyruk metriği)** → bu oturumdan önce zaten kurulmuştu:
   `altyazi_kuyruk_derinlik` (= `stt_queue_depth`), `altyazi_bolut_dusme_toplam`
   (= `stt_queue_drops_total`), `altyazi_gecikme_ms`/`altyazi_kapsama_yuzde`
   (= `ALTYAZI KAPSAMA` logunun metrik hâli) — hepsi Grafana'da.

---

## Bölüm A — Hazırlık

### A.1 Araç doğrulaması
```
[x] nvidia-smi ile GPU izleme çalışıyor
[x] Kuyruk derinliği + drop metrikleri Grafana'da (altyazi_kuyruk_derinlik, altyazi_bolut_dusme_toplam)
[x] Gecikme/kapsama metrik olarak mevcut (log grep'ten daha iyisi: altyazi_gecikme_ms, altyazi_kapsama_yuzde)
```

### A.4 Baseline (boşta) ölçüm

| Metrik | Değer |
|---|---|
| Boşta VRAM kullanımı (modeller yüklü, kanal yok) | **2242 MB** / 6141 MB |
| Boşta GPU util | **~%20-40** (dalgalı) |

**Not:** Boşta GPU util'in 0 değil %20-40 olması bu makinenin **masaüstü/laptop
kartı** olmasından — Xorg aynı GPU'yu paylaşıyor, güç çekimi ise düz 9W/80W
(gerçek hesap yükü yok). A100 (headless sunucu) bu gürültüden muaf olacak.

---

## Bölüm B/C.1 — Kademeli Yük Testi Ana Sonuç Tablosu

| Kademe | GPU Util (ort %) | VRAM (tepe MB) | Kuyruk Trendi | Drop (5dk toplam) | p50 gecikme | p95 gecikme | Altyazı Durumu |
|--------|------------------|----------------|---------------|-------------------|-------------|-------------|----------------|
| **4 kanal**  | 26,0% | 2643 MB | [x] yatay | **0** | 366–506 ms | 745–6002 ms* | [x] akıyor (**%100** kapsama) |
| **8 kanal**  | 41,3% | 3043 MB | [x] yatay | **0** | 339–774 ms | 434–6308 ms* | [x] akıyor (**%100** kapsama) |
| **12 kanal** | 68,2% | 3075 MB | [x] yatay** | **0 (pencerede)*** | 383–6212 ms | 539–10146 ms | [x/~] **kesik** (kapsama %70–100 arası, çoğu <%100) |
| **16 kanal** | 83,7% | 3106 MB | [~] **sürekli tavanda (4/4)** | **~25–41/kanal** | 10406–16204 ms | 14082–20492 ms | [x] **akmıyor** (kapsama çoğu kanalda **%0**) |

\* 8000ms bütçenin içinde kalıyor, "en kötü" değer bile — bu yüzden kapsama %100.
\*\* Kanal-bazlı kuyruk derinliği (`altyazi_kuyruk_derinlik`) 0→0 gösterdi, ama
bu YANILTICI olabilir — bkz. "Ölçüm sınırlaması" notu aşağıda.
\*\*\* 10 drop, ama TAMAMI warmup sırasında oldu (T0'da zaten vardı); 5 dakikalık
gözlem penceresinde YENİ drop = 0.

**Ölçüm sınırlaması (dürüstçe not edilmeli):** `altyazi_kuyruk_derinlik`
sadece kanal başına bekleyen bölüt sayısını ölçüyor — bir bölüt bir
"stt-gonderici" thread'i tarafından alındığı an bu sayaçtan düşüyor, ama
o thread GPU'ya HTTP isteğini attığında hâlâ cevap bekliyor olabilir.
6 gönderici thread'in tümü doluysa, yeni "hazır" kanal sinyalleri görünmez bir
şekilde `hazir` kuyruğunda birikir — bu ayrı bir metrik olarak **ölçülmedi**.
12 kanalda kuyruk derinliğinin 0 görünmesine rağmen gecikmenin zaten
yükselmiş olması (p95 10146ms) bunun kanıtı: gerçek birikim, ölçtüğüm
kanal-kuyruğunda değil, gönderici thread havuzunun kendisinde oluyor.

---

## Bölüm C.2 — Kırılma Noktası Tespiti

```
Sistemin STABIL çalıştığı en yüksek kanal sayısı: 8 kanal
  (kuyruk yatay, drop=0, kapsama %100, tüm kanallarda)

Kırılmanın BAŞLADIĞI kanal sayısı: 12 kanal
  (kapsama %70-95'e düşüyor, ufak drop var ama sistem KISMEN çalışıyor —
   TRT 1, TRT Turk, TRT Muzik hâlâ %100'de)

Sistemin TAMAMEN çöktüğü kanal sayısı: 16 kanal
  (11/16 kanalda kapsama %0, dakikada 5-8 bölüt/kanal düşme, gecikme 10-20 sn)
```

---

## Bölüm C.3 — Tek Segment İşleme Süresi (RTF)

İzole ölçüm (0 aktif kanal, gerçek 6 saniyelik konuşma sesi, 8 tekrar,
transkripsiyon + 3 dil çeviri BİRLİKTE — tam çalışan hat):

| Metrik | Değer |
|---|---|
| 6sn segmentin ortalama işleme süresi (transkripsiyon+çeviri) | **0,323 saniye** |
| RTF (6,0 / işlem süresi) | **18,59×** |

Örnek çıktı (doğruluk kontrolü): `"and it is connected to the weight of the
body or it is connected to the movement."` → tr: *"ve vücudun ağırlığına
bağlıdır..."*, ru: *"и он связан с весом тела..."*, de: *"und es ist mit dem
Gewicht..."* — üç dil de doğru üretildi.

**Karşılaştırma (bu oturumda ölçülen, eski large-v3+seri-çeviri mimarisi):**
Whisper large-v3 tek başına ~1,6-3,6× RTF veriyordu, 3 dilin sıralı çevirisi
ayrıca ~1,03 sn/bölüt ekliyordu → eski mimarinin birleşik RTF'si kabaca
**~1,3×** idi. Yeni mimari (`small` + `beam=1` + paralel çeviri) bunun
**~14 katı**.

---

## Bölüm C.4 — Karışma Kontrolü

16 kanal aktifken 4 farklı kanalın (DW English, TRT Haber, TRT Spor,
TRT Muzik) altyazı çıktısı Postgres'ten doğrudan okunarak karşılaştırıldı:

```
[x] Karışma YOK — her kanal kendi içeriğini gösteriyor (BEKLENEN/İYİ)
```

- **DW English**: AI/savunma teknolojisi belgeseli (Silicon Valley, dronlar, Rusya-Ukrayna) — tutarlı
- **TRT Haber**: haber bülteni (sağlık, gıda güvenliği, hukuk) — tutarlı, "Aydın Tular, news" ile bitiyor
- **TRT Spor**: futbol sohbeti ("Adana Demir Sport", "this season") — tutarlı
- **TRT Muzik**: şarkı sözleri (aşk temalı) — tutarlı

16 kanalda bile hiçbir kanalın çıktısında başka bir kanalın içeriği görülmedi.

---

## Bölüm D — Hangi Senaryodayız?

Ne Senaryo 2 ne Senaryo 3 tam uymuyor — **kademeye göre değişen, karma bir
tablo:**

**12 kanalda (kırılmanın başladığı nokta): Senaryo 1'e yakın.**
GPU util henüz **%68,2** — doymamış, ama kapsama zaten düşüyor. Bu, "concurrency
SERİLEŞİYOR" belirtisiyle örtüşüyor: darboğaz `STT_MAX_CONCURRENCY`/
`vad.stt-gonderici-sayisi` = **6** sabit tavanı — 12 kanal, 6 kişilik bir hatta
sıraya giriyor.

**16 kanalda (tam çöküş): Senaryo 1 + Senaryo 2 birlikte.**
GPU util **%83,7**'ye çıkmış — artık gerçekten de doymaya yaklaşıyor. Yani
6 kanaldan sonra önce concurrency tavanı vuruyor (yazılım sınırı), kanal
sayısı büyüdükçe buna gerçek GPU doygunluğu da ekleniyor (donanım sınırı).

**Senaryo 3 (VRAM) TÜM kademelerde kesin olarak ELENDİ:**
4→16 kanal arası VRAM 2643MB→3106MB — sadece ~460MB fark, ve toplam
kullanım hep **6141MB'nin yarısının altında** kaldı. Model ağırlıkları tek
kopya paylaşıldığı ve eşzamanlılık sabit 6'da tavanlandığı için VRAM hiçbir
zaman kanal sayısıyla orantılı büyümüyor.

**RAPORA YAZILACAK:**
> "8 kanalda tam stabil, 12 kanalda kısmi bozulma başlıyor (önce yazılımdaki
> sabit eşzamanlılık tavanı — 6 — yüzünden, GPU henüz dolu değil), 16 kanalda
> hem bu tavan hem gerçek GPU doygunluğu (%83,7) birleşip tam çöküşe yol
> açıyor. VRAM hiçbir kademede sınırlayıcı değildi (tepe 3106MB / 6141MB) —
> bu, `STT_MAX_CONCURRENCY`'yi VRAM'e güvenerek yükseltmek için somut kanıt."

---

## Bölüm E — A100'e Ekstrapolasyon

```
4050'de ölçülen stabil kanal sayısı:  S_4050 = 8
4050'de ölçülen RTF (tam hat):        RTF_4050 = 18,59×

A100 avantaj çarpanı (kaba tahmin):   ~5-8×

A100 tahmini stabil kanal ≈ 8 × 5-8 ≈ 40-64 kanal
```

**Ek bir gözlem — bu tahmini iyimser yapan bir faktör var:** 4050'de VRAM
kullanımı zaten %50'nin altındaydı, yani buradaki 8 kanallık sınır **VRAM'den
değil, `STT_MAX_CONCURRENCY=6` sabit tavanından** geliyordu. A100'de (40GB
VRAM) bu tavan çok daha yükseğe çekilebilir (VRAM buna izin verir) — yani
A100 tahmini sadece "daha hızlı GPU" çarpanına değil, **"aynı zamanda daha
yüksek concurrency ayarı" avantajına** da dayanıyor. Bu iki etki çarpanı
5-8×'in üstüne taşıyabilir, ama bu da bir tahmin — A100'de sweep yapılmadan
kesinleşmez.

**ZORUNLU UYARI:** Bu bir **TAHMİNDİR**. A100'ün gerçek kapasitesi ancak
A100 üzerinde aynı 4 kademeli test tekrarlanarak kesinleşir. 4050→A100
çarpanı GPU mimarisi farkları (Tensor Core nesli, bellek bant genişliği)
yüzünden doğrusal olmayabilir. **"40-64 kanal A100'de gerçekçi görünüyor ama
prod'da doğrulanmalı"** — "64 kanal garanti" değil.

---

## Ek bulgu — bu test sırasında düzeltilen gerçek bir regresyon

İmaj yeniden kurulurken (Aşama 1 kodu için gerekli) iki dış/yan sorun çıktı,
ikisi de düzeltildi:

1. **PyPI'da `nvidia-cudnn-cu12==9.1.0.70` kaldırılmıştı** — torch 2.4-2.6/cu124
   serisinin tamamı buna bağımlıydı, build'i kırıyordu. Çözüm: `TORCH_INDEX`
   `cu126`'ya çevrildi.
2. **cu126'daki daha yeni torch, Marian çevirisinde bir çekirdeği Triton
   (torch.compile alt katmanı) ile JIT-derlemeye çalışıyordu ve container'da
   derleyici yoktu** — bu, çevirinin **%100 başarısız** olmasına yol açıyordu
   (fark edilmeseydi RTF ölçümü yanlış çıkacaktı: transcribe hızlı ama
   translate hep boş dönüyordu). Çözüm: `gcc` + `libc6-dev` + `python3-dev`
   eklendi.

Bu ikisi düzeltilmeden Bölüm C.3'teki RTF ölçümü geçersiz olurdu — ilk
denemede fark edildi (`translations: {}` boş dönüyordu) ve ölçüme
başlamadan düzeltildi.
