# Altyazı gecikmesi: GPU'da ölçüm ve ayar

Bu belge **tahmin yerine ölçüm** için. Altyazının yetişip yetişmediği tek bir
koşula bağlı ve iki tarafı da artık ölçülebiliyor.

---

## Koşul

```
üretim gecikmesi   <   HLS gecikmesi
└─ sunucu ölçüyor ─┘   └─ tarayıcı ölçüyor ─┘
```

**Üretim gecikmesi** — bölüt sesinin bittiği an ile altyazının yayınlandığı an
arasındaki fark. Bölüt penceresi + STT + çeviri.

**HLS gecikmesi** — izleyicinin canlı kenardan ne kadar geride olduğu. Bu
altyazının *bütçesi*: izleyici ne kadar geriden izlerse, altyazının yetişmek
için o kadar zamanı var.

> Geç kalan altyazı **geç gösterilmez, hiç gösterilmez**. Arayüz süzgeci
> `bitis > playingDate()` olduğu için ekranda hiçbir belirti kalmıyor.
> Ölçüm bu yüzden var.

---

## Nereye bakılacak

### 1. Kapsama — backend logu

```bash
docker compose logs backend | grep "ALTYAZI KAPSAMA"
```

```
ALTYAZI KAPSAMA TRT Haber — 47 bölüt: 31 tam, 12 kısmi, 4 görünmedi (%91 yetişti)
| gecikme ort 3120 ms, p50 2980 ms, p95 4400 ms, en kötü 5100 ms | bütçe 4200 ms (ölçüldü)
```

| Alan | Anlamı |
|---|---|
| **tam** | Bölütün tamamı boyunca ekranda kaldı |
| **kısmi** | Yetişti ama yalnızca sonuna doğru göründü — izleyici cümlenin başını kaçırdı |
| **görünmedi** | Hiç gösterilmedi |
| **p50 / p95** | Üretim gecikmesinin ortancası ve 95. yüzdeliği |
| **bütçe (ölçüldü)** | Tarayıcı gerçek HLS gecikmesini bildirdi |
| **bütçe (varsayım)** | Kimse bildirmedi; `ALTYAZI_BUTCE_MS` kullanılıyor |

**"varsayım" yazıyorsa önce onu düzeltin.** Kimse o kanalı izlemiyor demektir;
bir sekmede açıp bir dakika bekleyin, sonra tekrar bakın. Varsayıma göre
ayar yapmak tahmine göre ayar yapmaktır.

### 2. Kapasite — stt-worker

```bash
curl -s localhost:8100/metrics | python3 -m json.tool
```

`realtime_factor` doğrudan "kaç kanal taşınır" demek: 20 kanal için 20× lazım.

---

## Ayar döngüsü

Tek seferde **tek değer** değiştirin; ikisini birden değiştirince hangisinin
işe yaradığı anlaşılmıyor.

```
değeri değiştir → docker compose up -d → 5 dk bekle → iki sayıya bak
```

| Belirti | Sebep | Ne yapmalı |
|---|---|---|
| `realtime_factor` < kanal sayısı | STT yetişmiyor | `STT_MODEL` küçült (`large-v3` → `medium`) ya da `STT_MAX_CONCURRENCY` düşür |
| **görünmedi** > %10 | Üretim bütçeyi aşıyor | `VAD_MAX_SEGMENT_MS` kısalt |
| **kısmi** çok, **görünmedi** az | Sınırdasınız | `VAD_MAX_SEGMENT_MS` bir kademe daha kısalt |
| Metin kalitesi düşük | Pencere fazla kısa | `VAD_MAX_SEGMENT_MS` uzat — bütçe elveriyorsa |
| Kuyruk düşmesi logda | Çözümleme yetişmiyor | `STT_MAX_CONCURRENCY` artır (VRAM elveriyorsa) |

Kuyruk düşmeleri ayrı loglanıyor ve **kapsama satırında görünmez**:

```
WARN  Çözümleme kuyruğu dolu, bölüt düşürüldü: <kanal> [<zaman>]
```

İkisine birlikte bakın: bu satır *üretilemedi*, kapsama satırı *üretildi ama
yetişemedi* demek.

---

## Başlangıç değerleri

`yapilandir.sh` NVENC bulunca bunları yazıyor. **Ölçülmüş değil, başlangıç
noktası** — bu projede GPU'da hiçbir ölçüm yapılmadı.

| Alan | CPU | GPU |
|---|---|---|
| `STT_MODEL` | `small` | `large-v3` |
| `STT_DEVICE` | `cpu` | `cuda` |
| `STT_COMPUTE_TYPE` | `int8` | `int8_float16` |
| `VAD_MAX_SEGMENT_MS` | 6000 | **4000** |
| `STT_BATCH_SIZE` | 8 | **16** |
| `STT_MAX_CONCURRENCY` | 2 | **4** |

Kalite ↔ gecikme ekseninde üç hazır ayar:

```
Kalite oncelikli  : VAD_MAX_SEGMENT_MS=6000  STT_MODEL=large-v3
Denge             : VAD_MAX_SEGMENT_MS=4000  STT_MODEL=large-v3
Gecikme oncelikli : VAD_MAX_SEGMENT_MS=3000  STT_MODEL=medium
```

---

## GPU'ya geçerken

```bash
./yapilandir.sh --zorla
docker compose build stt-worker     # ← ATLAMAYIN
./baslat.sh
```

**İmajın yeniden kurulması şart.** Taban imaj ve torch sürümü
`STT_DEVICE`'tan türüyor (`Dockerfile:17-29`); kurulmazsa CPU imajı kalır ve
GPU **sessizce** kullanılmaz — hiçbir hata görmezsiniz, yalnızca yavaş olur.

Doğrulama:

```bash
curl -s localhost:8100/metrics | grep device    # "cuda" yazmalı
docker exec stt-worker nvidia-smi               # kartı görmeli
```

---

## Alt sınır

Gecikme sıfırlanamaz. Üç kalem var ve hiçbiri kaldırılamıyor:

| Kalem | Alt sınır | Neden |
|---|---|---|
| Bölüt penceresi | ~3 sn | Whisper 30 sn'lik pencerelerle eğitilmiş; kısa parçada bağlam kaybediyor |
| STT | ölçülmedi | Model çıkarımı |
| Çeviri | ölçülmedi | Cümle bazlı, dil başına bir geçiş |

Yalnızca birinci kalem mimari olarak biliniyor. **1-2 saniyeye inmek bu
tasarımla mümkün değil** — kısmi sonuç üretmek gerekir: bölüt kapanmadan ara
metin gösterip kesinleştikçe düzeltmek. Metin ekranda titrer, kesinleşme
mantığı gerekir; ayrı ve büyük bir iş.
