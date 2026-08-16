import { createContext, use, useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { channelsApi, radiosApi } from '@/api/endpoints'
import type { Capacity, ChannelDto, RadioDto } from '@/api/types'

/** Aynı anda açılabilecek karo sayısı — backend'deki kanal kapasitesiyle aynı. */
export const MAX_TILES = 16

interface PlayerValue {
  /**
   * Kanal listesi — 30 saniyede bir tazeleniyor.
   *
   * <p><b>Neden burada:</b> hem karo ızgarası hem sağdaki yayın paneli aynı
   * listeye ihtiyaç duyuyor. İkisi ayrı ayrı çekseydi aynı uç saniyede iki kez
   * yoklanır ve ikisi kısa süreliğine farklı durum gösterebilirdi.
   */
  channels: ChannelDto[]
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

  /**
   * Radyo listesi — 15 saniyede bir tazeleniyor.
   *
   * <p><b>Neden burada (kanallarla aynı gerekçe):</b> Radyolar sayfası hem
   * listeyi hem oynatma durumunu (`radioId`) gösteriyor; ikisi ayrı yerde
   * yaşasaydı bir istasyona geçince ekrandaki dinleyici sayısı bir sonraki
   * yoklamaya kadar (15 sn) eskisi gibi görünürdü — tam olarak kanal
   * tarafında yaşanan "kapatıyorum ama sayı düşmüyor" belirtisinin radyo
   * karşılığı.
   */
  radios: RadioDto[]
  capacity: Capacity | null
  /** Radyo listesini hemen tazeler — CRUD işlemlerinden (ekle/sil/geri yükle) sonra çağrılır. */
  refreshRadios: () => Promise<void>

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
  const [channels, setChannels] = useState<ChannelDto[]>([])
  const [radios, setRadios] = useState<RadioDto[]>([])
  const [capacity, setCapacity] = useState<Capacity | null>(null)

  useEffect(() => {
    const yukle = async () => {
      try {
        setChannels(await channelsApi.list())
      } catch {
        // Liste alinamazsa acik oynaticilara dokunmuyoruz; yayin aksin.
      }
    }
    void yukle()
    const timer = setInterval(() => void yukle(), 30_000)
    return () => clearInterval(timer)
  }, [])

  const refreshRadios = useCallback(async () => {
    const [list, cap] = await Promise.all([radiosApi.list(), radiosApi.capacity()])
    setRadios(list)
    setCapacity(cap)
  }, [])

  useEffect(() => {
    void refreshRadios().catch(() => {
      // Liste alinamazsa mevcut duruma dokunmuyoruz; RadiosPage kendi
      // ilk-yukleme hatasini ayrica yakalayip gosteriyor.
    })
    const timer = setInterval(() => void refreshRadios().catch(() => {}), 15_000)
    return () => clearInterval(timer)
  }, [refreshRadios])

  const toggle = useCallback((channelId: string) => {
    // 'kapandi'/'acildi' yalnizca GERCEKTEN bir degisiklik olduysa true olur
    // (MAX_TILES dolulugunda "acma" no-op kalir -- o durumda sayaci artirmak
    // yanlis olurdu, karo hic acilmadi).
    let kapandi = false
    let acildi = false
    setOpenIds((prev) => {
      if (prev.includes(channelId)) {
        kapandi = true
        return prev.filter((id) => id !== channelId)
      }
      if (prev.length >= MAX_TILES) return prev
      acildi = true
      return [...prev, channelId]
    })
    // Kapatılan kanal ses odağı veya büyük ekrandaysa o durumları da bırakmalı,
    // yoksa var olmayan bir kanala işaret eden ölü referans kalır.
    setAudioId((prev) => (prev === channelId ? null : prev))
    setExpandedId((prev) => (prev === channelId ? null : prev))

    // İyimser (optimistic) sayaç güncellemesi: `channels` listesi 30 saniyede
    // bir tazeleniyor (bkz. yukarıdaki yorum) — bu olmadan kendi kapattığın
    // karonun izleyici sayısı bir sonraki yoklamaya kadar eskisi gibi
    // görünürdü ("kapatıyorum ama sayı düşmüyor" belirtisi). usePresence zaten
    // backend'e anında ayrılma bildiriyor; burası yalnızca EKRANDA gösterilen
    // sayıyı o gerçek durumla hemen eşitliyor, kendi gerçeği değil.
    if (kapandi || acildi) {
      setChannels((prev) =>
        prev.map((c) =>
          c.id === channelId && c.viewers != null
            ? { ...c, viewers: Math.max(0, c.viewers + (kapandi ? -1 : 1)) }
            : c,
        ),
      )
    }
  }, [])

  const openMany = useCallback((channelIds: string[]) => {
    setOpenIds(channelIds.slice(0, MAX_TILES))
  }, [])

  const closeAll = useCallback(() => {
    setOpenIds((prev) => {
      // toggle()'daki iyimser sayaç güncellemesiyle aynı gerekçe — hepsi
      // birden kapanınca da ekrandaki sayı 30 saniyelik yoklamayı beklemeden düşsün.
      if (prev.length > 0) {
        setChannels((cs) =>
          cs.map((c) =>
            prev.includes(c.id) && c.viewers != null
              ? { ...c, viewers: Math.max(0, c.viewers - 1) }
              : c,
          ),
        )
      }
      return []
    })
    setAudioId(null)
    setExpandedId(null)
    setQualityMap({})
  }, [])

  const setQuality = useCallback((channelId: string, suffix: string) => {
    setQualityMap((prev) => ({ ...prev, [channelId]: suffix }))
  }, [])

  /**
   * `radios` listesindeki bir istasyonun dinleyici sayısını iyimser olarak
   * düşürür — `radioId` bir kanal lehine ya da tamamen kapanınca (aşağıdaki
   * dört yerin hepsi) çağrılıyor. `refreshRadios`'un 15 saniyelik yoklaması
   * gerçek sayıyla eşitleyecek; burası yalnızca ekranın o ana kadar eski
   * sayıyı göstermesini önlüyor.
   */
  const dusurDinleyici = useCallback((id: string | null) => {
    if (id == null) return
    setRadios((prev) =>
      prev.map((r) => (r.id === id && r.listeners != null
        ? { ...r, listeners: Math.max(0, r.listeners - 1) }
        : r)),
    )
  }, [])

  /**
   * Sesi bir kanala verir. Radyo çalıyorsa susturur — aynı anda iki kaynağın
   * seslenmesi, kullanıcının hangisini dinlediğini anlayamaması demek.
   */
  const setAudio = useCallback((channelId: string | null) => {
    setAudioId(channelId)
    if (channelId) {
      dusurDinleyici(radioId)
      setRadioId(null)
    }
  }, [radioId, dusurDinleyici])

  const expand = useCallback((channelId: string | null) => {
    setExpandedId(channelId)
    // Büyüten kullanıcı o kanalı dinlemek istiyordur.
    if (channelId) {
      setAudioId(channelId)
      dusurDinleyici(radioId)
      setRadioId(null)
    }
  }, [radioId, dusurDinleyici])

  /** Radyoyu açar; ses odağını kanallardan alır (tek ses kuralı). */
  const playRadio = useCallback((id: string) => {
    if (id !== radioId) {
      dusurDinleyici(radioId)
      setRadios((prev) =>
        prev.map((r) => (r.id === id && r.listeners != null
          ? { ...r, listeners: r.listeners + 1 }
          : r)),
      )
    }
    setRadioId(id)
    setRadioPaused(false)
    setAudioId(null)
  }, [radioId, dusurDinleyici])

  const toggleRadioPause = useCallback(() => setRadioPaused((prev) => !prev), [])

  const stopRadio = useCallback(() => {
    dusurDinleyici(radioId)
    setRadioId(null)
    setRadioPaused(false)
  }, [radioId, dusurDinleyici])

  const value = useMemo<PlayerValue>(
    () => ({
      channels, openIds, audioId, expandedId, quality, radioId, radioPaused,
      radios, capacity, refreshRadios,
      toggle, openMany, closeAll, setAudio, expand, setQuality,
      playRadio, toggleRadioPause, stopRadio,
    }),
    [channels, openIds, audioId, expandedId, quality, radioId, radioPaused,
      radios, capacity, refreshRadios,
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
