import { createContext, use, useCallback, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

/** Aynı anda açılabilecek karo sayısı — backend'deki kanal kapasitesiyle aynı. */
export const MAX_TILES = 16

interface PlayerValue {
  /** Açık kanalların id'leri; oynatıcıları sayfa değişse de canlı kalır. */
  openIds: string[]
  /** Sesi açık olan tek kanal. Onlarca yayın aynı anda seslenirse hiçbiri duyulmaz. */
  audioId: string | null
  /** Büyük ekranda açılan kanal; null ise grid görünümü. */
  expandedId: string | null
  /**
   * Karo başına seçilen kalite (rendition son eki; '' = kaynak).
   *
   * <p>Karo bazında: 4x4 gridde küçük karolar için düşük çözünürlük yeterli,
   * büyütülen karo için kaynak istenebilir. Tek bir genel ayar ikisini birden
   * karşılamazdı.
   */
  quality: Record<string, string>

  toggle: (channelId: string) => void
  openMany: (channelIds: string[]) => void
  closeAll: () => void
  setAudio: (channelId: string | null) => void
  expand: (channelId: string | null) => void
  setQuality: (channelId: string, suffix: string) => void
}

const PlayerContext = createContext<PlayerValue | null>(null)

export function PlayerProvider({ children }: { children: ReactNode }) {
  const [openIds, setOpenIds] = useState<string[]>([])
  const [audioId, setAudioId] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [quality, setQualityMap] = useState<Record<string, string>>({})

  const toggle = useCallback((channelId: string) => {
    setOpenIds((prev) => {
      if (prev.includes(channelId)) return prev.filter((id) => id !== channelId)
      if (prev.length >= MAX_TILES) return prev
      return [...prev, channelId]
    })
    // Kapatılan kanal ses odağı veya büyük ekrandaysa o durumları da bırakmalı,
    // yoksa var olmayan bir kanala işaret eden ölü referans kalır.
    setAudioId((prev) => (prev === channelId ? null : prev))
    setExpandedId((prev) => (prev === channelId ? null : prev))
  }, [])

  const openMany = useCallback((channelIds: string[]) => {
    setOpenIds(channelIds.slice(0, MAX_TILES))
  }, [])

  const closeAll = useCallback(() => {
    setOpenIds([])
    setAudioId(null)
    setExpandedId(null)
    setQualityMap({})
  }, [])

  const setQuality = useCallback((channelId: string, suffix: string) => {
    setQualityMap((prev) => ({ ...prev, [channelId]: suffix }))
  }, [])

  const expand = useCallback((channelId: string | null) => {
    setExpandedId(channelId)
    // Büyüten kullanıcı o kanalı dinlemek istiyordur.
    if (channelId) setAudioId(channelId)
  }, [])

  const value = useMemo<PlayerValue>(
    () => ({
      openIds, audioId, expandedId, quality,
      toggle, openMany, closeAll, setAudio: setAudioId, expand, setQuality,
    }),
    [openIds, audioId, expandedId, quality, toggle, openMany, closeAll, expand, setQuality],
  )

  return <PlayerContext value={value}>{children}</PlayerContext>
}

export function usePlayers(): PlayerValue {
  const value = use(PlayerContext)
  if (!value) throw new Error('usePlayers, PlayerProvider içinde kullanılmalı.')
  return value
}
