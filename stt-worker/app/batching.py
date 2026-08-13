"""
Kanallar arası istek birleştirme (batching) katmanı.

Farklı kanallardan gelen istekler (çözümleme VEYA çeviri) kısa bir pencerede
(``STT_BATCH_WINDOW_MS``) toplanıp TEK GPU çağrısında birlikte işleniyor.
Hangi isteğin hangi kanala ait olduğu bu katmanın umurunda değil -- her
istek kendi `asyncio.Future`'ıyla geldiği yerden (main.py'deki channel/
start/end) ayrılmadan, gönderdiği sırayla sonucunu geri alıyor. Kanal/segment
kimliği burada değil, çağıranın kendi eşleştirmesinde zaten tutuluyor.

<p>İki ayrı kullanım (çözümleme, çeviri) AYNI sınıfı paylaşıyor
(`BatchCoalescer`, bir `batch_fn` alıyor) -- pencere/zamanlayıcı mantığı
ince ve hataya açık (bkz. `_schedule_flush`); iki yerde ayrı ayrı yazmak
biri değişince diğerinin unutulması riski taşırdı.
"""

import asyncio
import logging
from typing import Awaitable, Callable, Generic, TypeVar

from .config import SETTINGS

log = logging.getLogger(__name__)

T = TypeVar("T")
R = TypeVar("R")


class BatchCoalescer(Generic[T, R]):
    """
    Bir isteği kabul eder, kısa bir pencere boyunca biriktirir, pencere
    dolunca (süre veya sayı) hepsini `batch_fn`'e birlikte verir.

    <p>İki tetikleyici: pencere ``STT_BATCH_WINDOW_MS`` sürede dolar (ilk
    isteğin geldiği andan itibaren), ya da biriken istek sayısı
    ``STT_BATCH_MAX_SIZE``'a ulaşır (o anda beklemeden hemen işlenir --
    sayı zaten dolmuşken süre dolmasını beklemenin anlamı yok).

    :param batch_fn: bir liste giriş alıp AYNI sırada bir liste sonuç
                      döndüren ``async`` fonksiyon (kendi içinde
                      ``anyio.to_thread.run_sync`` ile GPU çağrısını
                      thread'e atmaktan sorumlu -- bu sınıf onu bilmiyor).
    """

    def __init__(self, batch_fn: Callable[[list[T]], Awaitable[list[R]]]) -> None:
        self._batch_fn = batch_fn
        self._pending: list[tuple[T, "asyncio.Future[R]"]] = []
        self._lock = asyncio.Lock()
        self._flush_handle: asyncio.TimerHandle | None = None

    async def submit(self, item: T) -> R:
        loop = asyncio.get_event_loop()
        future: asyncio.Future = loop.create_future()
        async with self._lock:
            self._pending.append((item, future))
            if len(self._pending) >= SETTINGS.batch_max_size:
                self._schedule_flush(loop, delay=0)
            elif len(self._pending) == 1:
                # Pencereyi yalnızca İLK istek açıyor -- sonrakiler aynı
                # pencereye biniyor, her biri kendi zamanlayıcısını
                # kurmuyor (o zaman pencere hiç kapanmazdı).
                self._schedule_flush(loop, delay=SETTINGS.batch_window_ms / 1000)
        return await future

    def _schedule_flush(self, loop: asyncio.AbstractEventLoop, delay: float) -> None:
        if self._flush_handle is not None:
            self._flush_handle.cancel()
        self._flush_handle = loop.call_later(
            delay, lambda: asyncio.ensure_future(self._flush()))

    async def _flush(self) -> None:
        async with self._lock:
            batch = self._pending
            self._pending = []
            if self._flush_handle is not None:
                self._flush_handle.cancel()
                self._flush_handle = None
        if not batch:
            return

        items = [item for item, _ in batch]
        futures = [f for _, f in batch]
        try:
            results = await self._batch_fn(items)
        except Exception as e:
            log.exception("Batch işlemi başarısız (%d öğe)", len(batch))
            for f in futures:
                if not f.done():
                    f.set_exception(e)
            return

        for f, result in zip(futures, results):
            if not f.done():
                f.set_result(result)
