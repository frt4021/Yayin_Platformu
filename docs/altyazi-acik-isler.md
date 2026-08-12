# Canlı altyazı — açık işler

Ölçüldü, teşhis edildi, **yapılmadı.** İkisi de teslimi engellemiyor;
`ALTYAZI_HLS_GERIDE` bütçeyi büyüterek bugünü kurtarıyor. Ama bütçe büyütmek
gecikmeyi **çözmüyor, saklıyor** — izleyici o kadar geriden izliyor.

Aşağıdakiler gecikmeyi gerçekten düşüren iki iş.

---

## 1. Yalnızca izlenen kanalı çözümle

**Kazanç: gereken kapasite = kanal sayısı → izlenen kanal sayısı.**
Tipik olarak 1. Yirmi kanallı kurulumda **20 kat**.

### Bugünkü durum

`VadService.sync()` (`src/main/java/org/example/VAD/VadService.java:134`):

```java
for (Channel channel : Channel.listActive()) {
    var state = states.get(channel.mediamtxPath);
    if (state != null && state.ready()) {
        live.put(channel.id, channel);
    }
}
```

İzleyici koşulu **yok**. Aktif ve yayında olan her kanal VAD + Whisper + üç
dil çeviriden geçiyor, kimse bakmasa da.

Ölçüldü: tarayıcı hiç açık değilken iki kanal `stt-worker`'ı %413 CPU'da
doyuruyor, 15 dakikada 141 bölüt kuyruktan düşüyordu.

### Yapılacak

Backend izleyicileri zaten biliyor: `SubtitleBroadcaster.sessions`. VAD ise
video işçisinde — ayrı konteyner, doğrudan çağrı yok. Aradaki boru **hazır**:
DVR için kurulan `DvrSignal` (`src/main/java/org/example/dvr/DvrSignal.java`)
tam olarak bu şekli taşıyor.

1. `SubtitleBroadcaster.join`/`leave` içinde, bir kanalın izleyici sayısı
   0↔1 sınırını geçtiğinde sinyal fırlat
2. `VadService` sinyali dinleyip o kanalı hemen açsın/kapatsın
3. `sync()` yoklamasında da izleyici tablosuna bak — sinyal kaybolursa
   yoklama toparlasın (DVR'daki desenin aynısı: **doğruluk kaynağı
   veritabanı, sinyal yalnızca beklemeyi kaldırıyor**)

### Dikkat

- **Kayıt/klip altyazısı etkilenmemeli.** İleride kliplere altyazı eklenirse
  (bkz. `docs/altyazi-klip-video-plani.md`) o yol izleyiciden bağımsız
  çalışmalı.
- İlk izleyici bağlandığında ilk birkaç saniye altyazısız geçer. Kabul
  edilebilir: alternatifi kimsenin görmediği altyazıyı sürekli üretmek.

---

## 2. Çeviriyi GPU'ya al ve kilidi kaldır

**Kazanç: ~4,4 kanallık tavanı kaldırır.** 20 kanal hedefi için şart.

### Bugünkü durum

`stt-worker/app/translate.py`:

```python
model = MarianMTModel.from_pretrained(path, local_files_only=True)   # .to("cuda") YOK
...
with self._lock, torch.no_grad():                                    # SERİ
```

İki sorun bir arada: modeller **CPU'da** kalıyor ve çeviri **tek kilitle
sıraya giriyor**, yani tek çekirdek.

### Ölçüm

| | Değer |
|---|---|
| Bölüt | 563 |
| Ses | 2537,7 sn |
| Çözümleme | 3190,3 sn → 0,80× |
| **Çeviri** | **577,5 sn → 4,39×** |
| Toplam | 3767,8 sn → 0,67× |

Bölüt başına çeviri **1,03 saniye** (üç dil). Çözümlemeyi sıfırlasan bile
tavan burada.

### Yapılacak

1. `STT_DEVICE=cuda` iken Marian modellerini de `.to("cuda")` ile yükle
2. `self._lock`'u kaldır ya da dil başına ayır — üç dil paralel çevrilebilir
3. Dil başına ayrı model VRAM tutuyor: Marian ~300 MB × 3 ≈ 0,9 GB.
   **6 GB'lık kartta** (4050) `large-v3` int8_float16 (~1,6 GB) ile birlikte
   sığar ama `STT_BATCH_SIZE` ve `STT_MAX_CONCURRENCY` yeniden ölçülmeli

### Dikkat

- Whisper modeli tekil (`stt.py:34`), eşzamanlılık `Semaphore` ile
  sınırlanıyor — `STT_MAX_CONCURRENCY` model ağırlığını **çoğaltmıyor**.
  Marian'ı dil başına paralelleştirirken aynı deseni koruyun.
- `int8_float16` kalite etkisi **ölçülmedi**. Kart geldiğinde ilk ölçüm bu
  olmalı.

---

## Bu belgede olmayan şey: GPU sayıları

**Bu projede GPU'da hiçbir ölçüm yapılmadı** — geliştirme makinesinde NVIDIA
kartı yok. `yapilandir.sh`'ın NVENC dalında yazdığı değerler
(`VAD_MAX_SEGMENT_MS=4000`, `STT_BATCH_SIZE=16`, `STT_MAX_CONCURRENCY=4`,
`ALTYAZI_HLS_GERIDE=5`) **başlangıç noktası, ölçülmüş öneri değil**.

Ölçme reçetesi: `docs/altyazi-gpu-olcum.md`.

Bilinen tek sert sınır VRAM:

| Model | `int8_float16` | `float16` |
|---|---|---|
| `large-v3` | ~1,6 GB | ~3,1 GB |
| Marian (dil başına) | ~0,3 GB | ~0,3 GB |

RTX 4050 Laptop 6 GB. `large-v3` + 3 dil ≈ 2,5 GB; geri kalanı yığın ve ara
tamponlara gidiyor. `STT_BATCH_SIZE`'ı `nvidia-smi`'ye bakarak yükseltin.
