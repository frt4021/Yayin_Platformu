import { useEffect } from 'react'
import { api } from '@/api/client'
import { readTokens } from '@/api/tokens'
import { sekmeKimligi } from '@/lib/sekmeKimligi'

/**
 * Bu sekmenin bir kanalı/radyoyu izlediğini/dinlediğini periyodik olarak
 * bildirir — {@code ViewerPresence}'ın (backend) SEKME bazlı sayacının veri
 * kaynağı. MediaMTX'in kendi reader sayısı yeniden bağlanmalarda ÇOK SAYIYOR
 * ("tek oturum açık ama üç izleyici görünüyor" belirtisi bundandı); bu hook
 * doğruluk kaynağını tarayıcının kendi "hâlâ buradayım" sinyaline taşıyor.
 */
const HEARTBEAT_MS = 15_000

export function usePresence(tur: 'channels' | 'radios', id: string | null) {
  useEffect(() => {
    if (!id) return
    const tabId = sekmeKimligi()
    const url = `/api/${tur}/${id}/izleyici/${tabId}`

    const nabizAt = () => void api.put<void>(url)

    // keepalive: sekme kapanırken/route değişirken normal fetch iptal
    // edilebiliyor -- bu istek sayfa gitse de tamamlanma şansı buluyor.
    const ayril = () => {
      const tokens = readTokens()
      void fetch(url, {
        method: 'DELETE',
        keepalive: true,
        headers: tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : undefined,
      })
    }

    nabizAt()
    const timer = setInterval(nabizAt, HEARTBEAT_MS)

    // KRITIK: React'in unmount temizligi (asagidaki return) sekme
    // DOGRUDAN kapatildiginda calismayabiliyor -- tarayici JS ortamini
    // yikarken React'e istegi gonderecek zaman kalmiyor. Bunun belirtisi
    // "sayi anlik dusmuyor, sanki kumulatif birikiyormus gibi" (yalnizca
    // 40 sn'lik TTL supurucusu duzeltene kadar). `pagehide`, tarayicinin
    // sekme/sayfa kapanirken GUVENILIR sekilde tetikledigi olay -- ayrilma
    // sinyalini oradan da gonderip bu bosluğu kapatiyoruz.
    window.addEventListener('pagehide', ayril)

    return () => {
      clearInterval(timer)
      window.removeEventListener('pagehide', ayril)
      ayril()
    }
  }, [tur, id])
}
