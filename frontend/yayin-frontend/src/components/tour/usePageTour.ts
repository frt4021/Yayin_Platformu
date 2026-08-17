import { useEffect, useState } from 'react'

/**
 * Sayfa turlarının ortak açma/kapama/hatırlama mantığı.
 *
 * <p>Her sayfa kendi {@code seenKey}'iyle çağırıyor (bkz. {@code steps.ts}
 * içindeki {@code *_TOUR_SEEN_KEY} sabitleri) — bir sayfanın turunu görmüş
 * olmak diğerini etkilemiyor, her biri ayrı hatırlanıyor.
 *
 * <p>Gecikme ({@code autoDelayMs}), hedeflerin DOM'a girmesini bekliyor:
 * çoğu sayfa açılışta bir liste isteği atıyor ve tur ondan önce ölçüm
 * yaparsa hedefleri bulamayıp adımları eleyebilir.
 */
export function usePageTour(seenKey: string, autoDelayMs = 900) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (localStorage.getItem(seenKey)) return
    const timer = setTimeout(() => setOpen(true), autoDelayMs)
    return () => clearTimeout(timer)
  }, [seenKey, autoDelayMs])

  function close() {
    setOpen(false)
    localStorage.setItem(seenKey, '1')
  }

  function start() {
    setOpen(true)
  }

  return { open, start, close }
}
