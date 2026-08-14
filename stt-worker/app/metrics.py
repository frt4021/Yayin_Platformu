"""
Prometheus metrikleri.

<p>Önce log satırlarıyla (BATCH boyutu=..., CEVIRI BATCH ...) ölçüldü —
tek seferlik, geriye dönük analiz için elle grep gerekiyordu. Buraya
taşınması, Grafana'da GPU VRAM paneliyle YAN YANA, sürekli izlenebilmesi
için — "batch büyüdükçe VRAM ne oluyor" sorusunun cevabı artık log
kazımak değil, tek bir grafik.
"""

from prometheus_client import Counter, Histogram

# Kesit/metin sayısı 1-32 arasında yoğunlaşıyor (ölçüldü: STT_BATCH_MAX_SIZE
# varsayılanı 8, gözlenen gerçek tavan 6) -- kovalar buna göre seçildi.
_BOYUT_KOVALARI = (1, 2, 3, 4, 5, 6, 8, 12, 16, 24, 32)

BATCH_BOYUTU = Histogram(
    "stt_batch_boyutu", "Whisper batch çağrısı başına kesit sayısı",
    buckets=_BOYUT_KOVALARI,
)

BATCH_TENSOR_MB = Histogram(
    "stt_batch_tensor_mb", "Whisper batch çağrısı başına özellik tensörü boyutu (MB)",
    buckets=(0.5, 1, 2, 4, 6, 8, 12, 16, 24, 32),
)

CEVIRI_BATCH_BOYUTU = Histogram(
    "stt_ceviri_batch_boyutu", "Çeviri batch çağrısı başına metin sayısı",
    labelnames=["dil"], buckets=_BOYUT_KOVALARI,
)

SEGMENTLER_TOPLAM = Counter("stt_segmentler_toplam", "İşlenen bölüt sayısı")
HATALAR_TOPLAM = Counter("stt_hatalar_toplam", "Başarısız çözümleme sayısı")
SES_SANIYE_TOPLAM = Counter("stt_ses_saniye_toplam", "İşlenen toplam ses süresi (sn)")
ISLEM_SANIYE_TOPLAM = Counter("stt_islem_saniye_toplam", "Toplam çözümleme süresi (sn)")
CEVIRI_SANIYE_TOPLAM = Counter("stt_ceviri_saniye_toplam", "Toplam çeviri süresi (sn)")
