import { useEffect } from 'react'
import { readTokens } from '@/api/tokens'
import { sekmeKimligi } from '@/lib/sekmeKimligi'

/**
 * Nabız aralığı. ViewerPresence.java'daki TTL (40sn) ile birlikte okunmalı
 * -- 15sn, ~2,5 kaçırılan nabza tolerans bırakıyor.
 */
const NABIZ_MS = 15_000

/**
 * Bir yayının (kanal/radyo) bu sekme tarafından izlendiğini backend'e
 * bildirir.
 *
 * <p>Neden gerekli: izleyici sayısı artık MediaMTX'in ham bağlantı
 * sayısından değil, bu nabızdan geliyor (bkz. ViewerPresence.java) --
 * MediaMTX her yeniden bağlanmayı (hata kurtarma, kanal değişimi) ayrı
 * bir "izleyici" sayıyordu, aynı sekme birden çok kez sayılabiliyordu.
 *
 * <p>{@code id} değiştiğinde ya da bileşen kaldırıldığında ÖNCEKİ yayın
 * için anında bir "ayrıl" isteği gidiyor -- sayı beklemeden düşsün diye.
 */
export function usePresence(tur: 'channels' | 'radios', id: string | null) {
  useEffect(() => {
    if (!id) return
    const tokens = readTokens()
    if (!tokens) return

    const tabId = sekmeKimligi()
    const headers = { Authorization: `Bearer ${tokens.accessToken}` }
    const url = `/api/${tur}/${id}/izleyici/${tabId}`

    const nabizAt = () => void fetch(url, { method: 'PUT', headers }).catch(() => {})
    nabizAt()
    const timer = setInterval(nabizAt, NABIZ_MS)

    return () => {
      clearInterval(timer)
      // keepalive: sekme/route degisirken tarayici bu istegi normalde
      // iptal edebilir; keepalive sayfa gitse bile tamamlanmasini saglar.
      void fetch(url, { method: 'DELETE', headers, keepalive: true }).catch(() => {})
    }
  }, [tur, id])
}
