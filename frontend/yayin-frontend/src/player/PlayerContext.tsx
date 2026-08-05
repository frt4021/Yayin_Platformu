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

  /**
   * Çalan radyonun id'si; null ise radyo kapalı.
   *
   * <p>Radyo ayrı bir provider'a değil BURAYA konuldu: ses sahipliği tek
   * yerden yönetilmezse radyo ile bir kanalın sesi üst üste çalardı.
   */
  radioId: string | null
  /** Kullanıcı radyoyu duraklattı mı. Kapatmak değil: istasyon seçili kalır. */
  radioPaused: boolean

  toggle: (channelId: string) => void
  openMany: (channelIds: string[]) => void
  closeAll: () => void
  setAudio: (channelId: string | null) => void
  expand: (channelId: string | null) => void
  setQuality: (channelId: string, suffix: string) => void
  playRadio: (radioId: string) => void
  toggleRadioPause: () => void
  stopRadio: () => void
}

const PlayerContext = createContext<PlayerValue | null>(null)

export function PlayerProvider({ children }: { children: ReactNode }) {
  const [openIds, setOpenIds] = useState<string[]>([])
  const [audioId, setAudioId] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [quality, setQualityMap] = useState<Record<string, string>>({})
  const [radioId, setRadioId] = useState<string | null>(null)
  const [radioPaused, setRadioPaused] = useState(false)

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

  /**
   * Sesi bir kanala verir. Radyo çalıyorsa susturur — aynı anda iki kaynağın
   * seslenmesi, kullanıcının hangisini dinlediğini anlayamaması demek.
   */
  const setAudio = useCallback((channelId: string | null) => {
    setAudioId(channelId)
    if (channelId) setRadioId(null)
  }, [])

  const expand = useCallback((channelId: string | null) => {
    setExpandedId(channelId)
    // Büyüten kullanıcı o kanalı dinlemek istiyordur.
    if (channelId) {
      setAudioId(channelId)
      setRadioId(null)
    }
  }, [])

  /** Radyoyu açar; ses odağını kanallardan alır (tek ses kuralı). */
  const playRadio = useCallback((id: string) => {
    setRadioId(id)
    setRadioPaused(false)
    setAudioId(null)
  }, [])

  const toggleRadioPause = useCallback(() => setRadioPaused((prev) => !prev), [])

  const stopRadio = useCallback(() => {
    setRadioId(null)
    setRadioPaused(false)
  }, [])

  const value = useMemo<PlayerValue>(
    () => ({
      openIds, audioId, expandedId, quality, radioId, radioPaused,
      toggle, openMany, closeAll, setAudio, expand, setQuality,
      playRadio, toggleRadioPause, stopRadio,
    }),
    [openIds, audioId, expandedId, quality, radioId, radioPaused,
      toggle, openMany, closeAll, setAudio, expand, setQuality,
      playRadio, toggleRadioPause, stopRadio],
  )

  return <PlayerContext value={value}>{children}</PlayerContext>
}

export function usePlayers(): PlayerValue {
  const value = use(PlayerContext)
  if (!value) throw new Error('usePlayers, PlayerProvider içinde kullanılmalı.')
  return value
}
