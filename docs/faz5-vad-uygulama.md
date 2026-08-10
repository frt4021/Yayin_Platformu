# Faz 5.1 — VAD Uygulama Yol Haritası

İskelet `org.example.VAD` altında hazır ve derleniyor. Bu belge, doldurma
sırasını ve her adımın **nasıl doğrulanacağını** anlatıyor.

Sıranın gerekçesi: her adım bir öncekine güvenerek ilerliyor ve kendi başına
doğrulanabiliyor. Baştan canlı akışla çok kanala girilirse, bir şey bozulduğunda
model mi, okuma mı, durum makinesi mi bozuk ayırt edilemez — **üçü de sessizce
yanlış çalışmakta usta.**

---

## Yapılandırma

Model sürümü ve yolu `.env`'den geliyor:

```bash
# --- VAD ---
VAD_ENABLED=false                                  # hat kurulana kadar kapalı
VAD_MODEL_PATH=/models/silero_vad.onnx             # imaja gömülü
VAD_MODEL_VERSION=v5                               # v4 | v5 — aşağıyı oku
VAD_MAX_CHANNELS=20
```

`application.properties` karşılığı:

```properties
vad.enabled=${VAD_ENABLED:false}
vad.model-path=${VAD_MODEL_PATH:/models/silero_vad.onnx}
vad.model-version=${VAD_MODEL_VERSION:v5}
vad.max-channels=${VAD_MAX_CHANNELS:20}
```

### Sürüm bir ayar değil, sözleşme

`VAD_MODEL_VERSION` kozmetik bir alan değil — **modelin girdi biçimini
belirliyor**:

| | v4 | v5 |
|---|---|---|
| Kare (16 kHz) | 1536 örnek | **512 örnek** |
| Bağlam | yok | **64 örnek** |
| Girdi uzunluğu | 1536 | **576** |

`VadConfig` şu an **v5'e göre** yazılı. Yanlış sürüm verilirse iki şey olur ve
ikisi de kötü:

- **Şekil uyuşmazsa** ONNX Runtime patlar — ölçüldü, v5 modeline 1024 ve 1536
  örnek verildiğinde `INVALID_ARGUMENT ... LSTM node` hatası geliyor. Bu iyi
  senaryo: gürültülü ve hemen fark ediliyor.
- **Şekil uyar ama semantik uymazsa** hiçbir şey patlamaz, sonuçlar sessizce
  bozulur. Ölçüldü: v5 modeline bağlamsız 512 örnek verildiğinde konuşma oranı
  **%97 yerine %0** çıkıyor — ses RMS'i 0,11 ve tepe değeri 0,95 iken.

**Bu yüzden açılışta doğrulama şart.** Model yüklenirken girdi şekli okunup
beklenenle karşılaştırılmalı:

```java
// beklenen v5: input [None, None] · state [2, None, 128] · sr []
// uyuşmuyorsa AÇIKÇA PATLA, "belki çalışır" deme
```

Sürüm değişirse **altın referans dosyası da değişir** (aşağıya bak) — v4 ile
v5 aynı sesten farklı skorlar üretir.

---

## Adım 0 — Hazırlık

- `pom.xml`'e bağımlılığı ekle:

```xml
<dependency>
  <groupId>com.microsoft.onnxruntime</groupId>
  <artifactId>onnxruntime</artifactId>
  <version>1.20.0</version>
</dependency>
```

- `silero_vad.onnx` (2,2 MB) → `src/main/resources/models/`
- Yukarıdaki `.env` ve `application.properties` alanlarını ekle

> **Çalışma anında indirme.** Kapalı ağ kuralı burada da geçerli: model
> imajda olacak. HuggingFace'ten çeken bir kod, üretimde sessizce başarısız
> olur.

**Bitti sayılır:** `mvn compile` geçiyor, model jar'ın içinde.

---

## Adım 1 — `SileroVad`

Modeli yükle, `score()`'u yaz. Tek dosya, kısa. **Ama en kritik kontrol noktası
burası** — altın referansı tutturmadan devam edilirse, sonraki her hata yanlış
yerde aranır.

### Doğrulama — altın referans

`src/test/resources/vad/` altında hazır:

| Dosya | İçerik |
|---|---|
| `ornek-60sn.pcm` | TRT Haber'den 60 sn · 16 kHz mono s16le |
| `altin-skorlar.txt` | Python/onnxruntime'ın ürettiği ilk 64 kare skoru |

Birim testi: aynı PCM'i besle, ilk 64 kareyi skorla, dosyayla karşılaştır.
Tolerans `1e-4`.

Beklenen ilk sekiz kare:

```
0.3687  0.7421  0.9499  0.9954  0.9976  0.9962  0.9983  0.9980
```

**Tutmuyorsa şüpheliler, olasılık sırasıyla:**

1. 64 örneklik bağlam eklenmiyor → *hepsi sıfıra yakın çıkar*
2. Bayt sırası ters (`s16le` little-endian) → *skorlar rastgele*
3. `state` kareler arası taşınmıyor → *ilk kare doğru, sonrası bozuk*

Bunu birim testi yap; sonra her değişiklikte kendiliğinden çalışır.

---

## Adım 2 — `AudioStream`

ffmpeg'i başlat, kare oku. Henüz VAD yok, yalnızca okuma.

```
ffmpeg -v error -rtsp_transport tcp -allowed_media_types audio \
       -i rtsp://mediamtx:8554/<path> \
       -vn -ac 1 -ar 16000 -f s16le -
```

`-allowed_media_types audio` ölçüldü: CPU **%1,5 → %0,8**. Video track'i RTSP'de
hiç SETUP edilmiyor.

### Doğrulama

60 saniye oku, sonra:

| | Beklenen |
|---|---|
| Okunan kare | ≈ **1875** (60 sn ÷ 32 ms) |
| Son `currentFrameStart()` | ≈ `anchor` + 60 sn |

**Sapma varsa:**

- Kare sayısı tutmuyor → `readFully` kullanılmamış, kısa okuma kareleri kaydırmış
- Süreç ~30 sn sonra donuyor → stderr okunmuyor, boru dolmuş

---

## Adım 3 — `SpeechSegmenter`

Durum makinesi. En çok emek buraya gidecek.

**Önce dosyayla çalış**, canlı akışla değil: `ornek-60sn.pcm`'i besle, bölütleri
WAV yaz. Tekrarlanabilir, hızlı, canlı yayına bağımlı değil.

### Doğrulama — üç katman

**1. Kulakla dinle.** Birkaç WAV aç. Bunun yerini hiçbir metrik tutmuyor.
Kelime başı veya sonu kesiliyorsa `SPEECH_PAD_MS` yetmiyor.

**2. Toplam süre.** Bölütlerin toplamı ≈ konuşma oranı × 60 sn olmalı.

**3. Bölüt sayısı makul mü.** 60 saniyede 40 bölüt çıkıyorsa histerezis
çalışmıyor demektir.

> Bu örnekte konuşma oranı **%97** — neredeyse tek uzun bölüt beklenmeli,
> muhtemelen `MAX_SEGMENT_MS` ile zorla kesilmiş 2-3 parça.

**Müzikli bir kanalda da dene.** Bu örnek VAD'ı zorlamıyor; asıl davranış
sessizlik/müzik geçişlerinde görülür.

---

## Adım 4 — `ChannelVadWorker`

Üçünü birleştir, canlı akışa bağla, yeniden bağlanmayı ekle (üstel geri
çekilme: 1s → 2s → 4s … en fazla 30s).

### Doğrulama

Çalışırken `docker restart mediamtx`. Beklenen:

- İşçi geri çekilmeyle yeniden bağlanır
- `state`, `ctx`, örnek sayacı **üçü birden** sıfırlanır
- `anchor` yeniden konur

Yeniden bağlandıktan sonra ilk bölütün zaman damgası saçmaysa `anchor`
yenilenmemiştir.

---

## Adım 5 — `VadService`

Yaşam döngüsü ve çok kanal.

### Doğrulama

- Kanalı pasife al → işçi kapanmalı; aktif et → açılmalı
- Uygulamayı kapat → her kanalın son bölütü `flush` ile gelmeli
  (atlanırsa son konuşma sessizce kaybolur)

Sonra **kademeli ölçekle**: 1 → 4 → 20 kanal, her basamakta `docker stats`.

| | Beklenen |
|---|---|
| 20 kanal CPU | ~%20 (1,6 çekirdek) |
| 20 kanal RAM | ~1 GB |

Belirgin sapma varsa bir yerde iş parçacığı ya da tensör sızıyor.

---

## Adım 6 — Ölçüm

`speechRatio()`'yu periyodik logla. İki işe yarıyor:

**Sağlık göstergesi.** %0 ya da %100 bozukluk demek — en olası sebep bağlamın
verilmemesi.

**GPU bütçesi.** Atılan oran, STT'de kazanılacak oran. Kanal türüne göre
ölçülmesi gereken şey bu: TRT Haber'de %97 çıkması, o kanalda VAD'ın neredeyse
hiç kazanç sağlamadığı anlamına geliyor. Planda varsayılan %60-70'ti — **bu
varsayım henüz doğrulanmadı.**

Kanal türü başına ölçüp tabloya dök: haber, müzik, belgesel, spor. Faz 5'in
GPU boyutlandırması bu tabloya dayanacak.

---

## Özet sıra

| Adım | İş | Bitti sayılır |
|---|---|---|
| 0 | Bağımlılık, model, config | `mvn compile` geçiyor |
| 1 | `SileroVad` | Altın referans `1e-4` toleransla tutuyor |
| 2 | `AudioStream` | 60 sn'de ~1875 kare, zaman kaymıyor |
| 3 | `SpeechSegmenter` | WAV'lar kulakla doğru, bölüt sayısı makul |
| 4 | `ChannelVadWorker` | mediamtx yeniden başlayınca toparlıyor |
| 5 | `VadService` | 20 kanal ~%20 CPU |
| 6 | Ölçüm | Kanal türü başına konuşma oranı tablosu |
