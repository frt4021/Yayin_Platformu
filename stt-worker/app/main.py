"""
STT servisi — ayrı bir konteyner.

Neden ayrı: faster-whisper + CUDA kütüphaneleri + modeller ~5-6 GB eder ve
GPU yalnızca buraya gerekiyor. `video-worker`'ı bu boyuta çıkarmanın ve onu
GPU'ya bağlamanın anlamı yok — o konteyner GPU'suz makinelerde de çalışmalı.

Arayüz HTTP: bölüt ortalama 440 KB (13,7 sn × 32 KB/sn) ve gRPC'nin
karmaşıklığı bu boyutta kendini ödemiyor.

    POST /transcribe?channel=<uuid>&start=<iso>&end=<iso>
    Content-Type: application/octet-stream
    <ham PCM — 16 kHz, tek kanal, s16le>

Gövde ham bayt: base64 %33 şişirirdi, çok parçalı form ise gereksiz ayrıştırma.
"""

import logging
import threading
import time

import anyio
from fastapi import FastAPI, HTTPException, Query, Request

from .config import BYTES_PER_SAMPLE, SAMPLE_RATE, SETTINGS
from .schemas import HealthStatus, TranscriptionResult
from .stt import Transcriber
from .translate import Translator

logging.basicConfig(level=SETTINGS.log_level)
log = logging.getLogger(__name__)

app = FastAPI(title="Yayın Merkezi — STT")

transcriber = Transcriber()
translator = Translator()


class Metrics:
    """
    Kapasite ölçümünün toplamları.

    **Tek önemli sayı gerçek zaman katı.** 20 kanal kesintisiz altyazı için
    toplamda 20× gerekiyor (VAD sessizlikleri attıktan sonra daha az). Kart
    kararı bu sayıya dayanacak — tahmine değil.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.segments = 0
        self.failures = 0
        self.audio_ms = 0
        self.processing_ms = 0
        self.translation_ms = 0

    def record(self, audio_ms: int, processing_ms: int, translation_ms: int) -> None:
        with self._lock:
            self.segments += 1
            self.audio_ms += audio_ms
            self.processing_ms += processing_ms
            self.translation_ms += translation_ms

    def record_failure(self) -> None:
        with self._lock:
            self.failures += 1

    def summary(self) -> dict:
        with self._lock:
            factor = self.audio_ms / self.processing_ms if self.processing_ms else 0.0
            return {
                "segments": self.segments,
                "failures": self.failures,
                "audio_s": round(self.audio_ms / 1000, 1),
                "processing_s": round(self.processing_ms / 1000, 1),
                "translation_s": round(self.translation_ms / 1000, 1),
                "realtime_factor": round(factor, 2),
                "channels_supported": round(factor, 1),
            }


metrics = Metrics()


@app.on_event("startup")
def startup() -> None:
    """
    Modeller **açılışta** yükleniyor.

    İlk isteğe bırakılsaydı o istek 30+ saniye sürer ve zaman aşımına
    düşerdi. Yükleme başarısızsa servis ayağa kalkmamalı — yarım çalışan bir
    STT, sessizce boş altyazı üretmek demek.
    """
    log.info("Model yükleniyor: %s (%s, %s)",
             SETTINGS.model, SETTINGS.device, SETTINGS.compute_type)
    transcriber.load()
    translator.load()
    log.info("Hazır. Hedef diller: %s", SETTINGS.target_languages)


@app.get("/health", response_model=HealthStatus)
def health() -> HealthStatus:
    return HealthStatus(
        ready=transcriber.is_ready(),
        model=SETTINGS.model,
        device=SETTINGS.device,
        compute_type=SETTINGS.compute_type,
        target_languages=SETTINGS.target_languages,
        loaded_translation_models=translator.loaded_languages(),
    )


@app.post("/transcribe", response_model=TranscriptionResult)
async def transcribe(
    request: Request,
    channel: str = Query(description="Kanal kimliği — günlüklerde izlemek için"),
    start: str = Query(description="Bölütün mutlak başlangıcı (ISO-8601)"),
    end: str = Query(description="Bölütün mutlak bitişi (ISO-8601)"),
) -> TranscriptionResult:
    """
    Bir konuşma bölütünü çözümler ve çevirir.

    Zaman damgaları **kullanılmıyor, geri veriliyor**: eşleştirmeyi çağıran
    yapıyor. Burada tutulmalarının sebebi günlükte bir bölütü yayındaki anına
    bağlayabilmek — hangi anın yanlış çözümlendiğini bulmanın tek yolu.
    """
    pcm = await request.body()
    if not pcm:
        raise HTTPException(status_code=400, detail="Boş gövde")
    if len(pcm) % BYTES_PER_SAMPLE != 0:
        # s16le'de tek sayida bayt, kare hizalamasinin bozuldugunu gosterir.
        raise HTTPException(status_code=400, detail="PCM uzunluğu tek sayı — s16le bekleniyor")

    audio_ms = len(pcm) // BYTES_PER_SAMPLE * 1000 // SAMPLE_RATE
    started = time.perf_counter()

    try:
        # KRITIK: transcribe() senkron/blocking (model.transcribe CPU/GPU'da
        # calisirken Python thread'i tutuyor). Dogrudan cagrilirsa uvicorn'un
        # tek event loop'unu bloklar -- "async def" olmasina ragmen istekler
        # TEK TEK islenir (olculdu: Asama 0 testinde 6 paralel istek, art arda
        # ~1,5-2sn arayla sirayla bitti, aralarinda ORTUSME yoktu).
        # to_thread.run_sync bunu ayri bir thread'e atip event loop'u serbest
        # birakir -- STT_MAX_CONCURRENCY'nin (Semaphore) etkili olabilmesi
        # icin bu SART, semafor tek basina yetmiyor.
        text, language, confidence = await anyio.to_thread.run_sync(
            transcriber.transcribe, pcm)
    except Exception as e:
        metrics.record_failure()
        log.exception("Çözümleme başarısız: channel=%s %s", channel, start)
        raise HTTPException(status_code=500, detail=f"Çözümleme başarısız: {e}") from e

    translation_started = time.perf_counter()
    # Ceviri hatasi TUM sonucu dusurmemeli: Ingilizce metin zaten uretildi ve
    # tek basina degerli. Eksik diller sonucta yer almiyor.
    #
    # translate() kendi icinde artik async: 3 dili ayri thread'lere dagitip
    # asyncio ile bekliyor (bkz. translate.py) -- sirali cagrilsaydi 3 dilin
    # suresi toplanirdi.
    translations = await translator.translate(text)
    translation_ms = int((time.perf_counter() - translation_started) * 1000)

    processing_ms = int((time.perf_counter() - started) * 1000)
    metrics.record(audio_ms, processing_ms, translation_ms)

    log.info("channel=%s %s → %s | %d sn ses, %d ms işlem (%.1fx) | %d dil",
             channel, start, language, audio_ms // 1000, processing_ms,
             audio_ms / processing_ms if processing_ms else 0, len(translations))

    return TranscriptionResult(
        source_language=language,
        source_language_confidence=confidence,
        text=text,
        translations=translations,
        audio_ms=audio_ms,
        processing_ms=processing_ms,
    )


@app.get("/metrics")
def metrics_endpoint() -> dict:
    """
    Kapasite ölçümü için toplam sayaçlar.

    `realtime_factor` doğrudan "kaç kanal taşınabilir"e karşılık geliyor:
    20 kanal için 20× gerekiyor. Kart kararı bu sayıya dayanacak.
    """
    return metrics.summary() | {
        "model": SETTINGS.model,
        "device": SETTINGS.device,
        "compute_type": SETTINGS.compute_type,
    }
