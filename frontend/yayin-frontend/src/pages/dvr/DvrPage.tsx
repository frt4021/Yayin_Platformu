import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi, clipsApi, dvrApi } from '@/api/endpoints'
import { readTokens } from '@/api/tokens'
import type { ChannelDto, TimelineSpan } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { ScheduledRecordingCard } from './ScheduledRecordingCard'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Loader2Icon, PlayIcon, ScissorsIcon, XIcon } from 'lucide-react'
import { Timeline, type Selection } from './Timeline'

/** Zaman çizelgesi pencereleri. 7 gün DVR saklama süresiyle aynı. */
const WINDOWS = [
  { label: 'Son 1 saat', hours: 1 },
  { label: 'Son 6 saat', hours: 6 },
  { label: 'Son 24 saat', hours: 24 },
  { label: 'Son 7 gün', hours: 24 * 7 },
] as const

/**
 * Önizleme bölümü belleğe indirildiği için üst sınır şart: 2 saatlik bir
 * seçim 6 Mbps'te ~5 GB eder ve tarayıcıyı düşürür.
 */
const PREVIEW_MAX_SECONDS = 180

/** Çizelgeye tıklandığında yüklenen bölüm. */
const PREVIEW_DEFAULT_SECONDS = 60

/** Sınır karesi için indirilen bölüm. Tek kare yeterli olduğundan çok kısa. */
const FRAME_SECONDS = 2

/**
 * Şeritteki kare sayısı. Seçim boyunca eşit aralıklı dağıtılıyor; amaç
 * kullanıcının <b>ne kaydettiğini</b> görmesi, yalnızca sınırları değil.
 *
 * <p>Altı kare ~2,5 MB indiriyor. Daha fazlası hem sunucuya hem kullanıcının
 * beklemesine yansır; daha azı uzun seçimlerde içeriği anlatmaz.
 */
const FILMSTRIP_COUNT = 6

/** Sürükleme sırasında her harekette indirme başlatmamak için. */
const FRAME_DEBOUNCE_MS = 500

function formatDuration(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  return h > 0 ? `${h}sa ${m}dk` : m > 0 ? `${m}dk ${s}sn` : `${s}sn`
}

/** 6 Mbps varsayımıyla kaba boyut tahmini — kullanıcı ne indireceğini bilsin. */
function estimateSize(seconds: number) {
  const mb = (6 / 8) * seconds
  return mb > 1024 ? `~${(mb / 1024).toFixed(1)} GB` : `~${Math.round(mb)} MB`
}

export function DvrPage() {
  // Klip alma artik giris yapmis herkese acik: izleyici geriye sarmayla
  // ayni icerigi zaten izleyebiliyordu. Uretilen klip sahibine ozel kalir.

  const [channels, setChannels] = useState<ChannelDto[]>([])
  const [channelId, setChannelId] = useState<string | null>(null)
  const [windowHours, setWindowHours] = useState<number>(24)
  const [spans, setSpans] = useState<TimelineSpan[]>([])
  const [selection, setSelection] = useState<Selection | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const videoRef = useRef<HTMLVideoElement>(null)
  const objectUrlRef = useRef<string | null>(null)

  /** Oynatıcıda o an yüklü olan aralık; kullanıcı neye baktığını bilsin diye. */
  const [previewRange, setPreviewRange] = useState<{ start: Date; seconds: number } | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)

  /**
   * Seçim boyunca eşit aralıklı kareler — "neyi klip alıyorum" sorusunun
   * cevabı. Yalnızca sınırları göstermek, aradaki içeriği anlatmıyordu.
   */
  const [frames, setFrames] = useState<{ at: Date; image: string | null }[]>([])
  const [framesLoading, setFramesLoading] = useState(false)

  // Pencere sabit tutuluyor: her render'da new Date() çağrılsaydı zaman
  // çizelgesi sürekli kayar ve seçim yerinden oynardı.
  const [now, setNow] = useState(() => new Date())
  const from = useMemo(() => new Date(now.getTime() - windowHours * 3600_000), [now, windowHours])

  const dvrChannels = channels.filter((c) => c.dvrEnabled)

  useEffect(() => {
    channelsApi
      .list()
      .then((list) => {
        setChannels(list)
        const first = list.find((c) => c.dvrEnabled)
        if (first) setChannelId((prev) => prev ?? first.id)
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Kanallar yüklenemedi.'))
  }, [])

  const loadTimeline = useCallback(async () => {
    if (!channelId) return
    setLoading(true)
    setError(null)
    try {
      setSpans(await dvrApi.timeline(channelId, from, now))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Zaman çizelgesi alınamadı.')
      setSpans([])
    } finally {
      setLoading(false)
    }
  }, [channelId, from, now])

  useEffect(() => {
    void loadTimeline()
  }, [loadTimeline])

  // Oynatma bittiğinde blob'u serbest bırak; aksi halde her atlamada
  // bellekte bir kopya birikir.
  useEffect(() => {
    return () => {
      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current)
    }
  }, [])

  // Seçim değişince şeridi tazele. Gecikme şart: çizelgede sürüklerken her
  // piksel hareketinde altı indirme başlatmak sunucuyu gereksizce yorardı.
  useEffect(() => {
    if (!selection || !channelId) {
      setFrames([])
      return
    }
    const toplamMs = selection.end.getTime() - selection.start.getTime()
    if (toplamMs < 1000) {
      setFrames([])
      return
    }

    // Kısa seçimde altı kare anlamsız: 3 saniyelik aralıkta hepsi aynı anı
    // gösterirdi. Saniye başına en fazla bir kare.
    const adet = Math.max(2, Math.min(FILMSTRIP_COUNT, Math.floor(toplamMs / 1000)))
    // Son kare tam bitişte olursa aralığın DIŞINA düşebiliyor; bir saniye geri.
    const anlar = Array.from({ length: adet }, (_, i) =>
      new Date(selection.start.getTime() + (toplamMs - 1000) * (i / (adet - 1))),
    )

    let iptal = false
    setFramesLoading(true)
    setFrames(anlar.map((at) => ({ at, image: null })))

    const timer = setTimeout(() => {
      void (async () => {
        const gorseller = await Promise.all(anlar.map((at) => grabFrame(at)))
        if (!iptal) {
          setFrames(anlar.map((at, i) => ({ at, image: gorseller[i] })))
          setFramesLoading(false)
        }
      })()
    }, FRAME_DEBOUNCE_MS)

    return () => {
      iptal = true
      clearTimeout(timer)
    }
    // grabFrame kimlik olarak degisiyor ama davranisi sabit; bagimlilik
    // listesine alinirsa her render'da yeniden tetiklenir.
  }, [selection, channelId])

  /**
   * Geçmişten oynatma. Uç token gerektirdiği için {@code <video src>} ile
   * doğrudan kullanılamıyor; parça fetch ile alınıp blob olarak veriliyor.
   *
   * <p>Bölüm <b>tamamen indiriliyor</b>, akış halinde değil. Bu yüzden süre
   * {@link PREVIEW_MAX_SECONDS} ile sınırlı: 2 saatlik bir seçim 6 Mbps'te
   * ~5 GB eder ve tarayıcıyı düşürürdü.
   */
  async function playFrom(at: Date, seconds: number) {
    if (!channelId) return
    const tokens = readTokens()
    if (!tokens) return

    const sure = Math.min(Math.max(5, Math.round(seconds)), PREVIEW_MAX_SECONDS)
    setPreviewLoading(true)
    try {
      const response = await fetch(dvrApi.streamUrl(channelId, at, sure), {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current)
      const url = URL.createObjectURL(await response.blob())
      objectUrlRef.current = url
      setPreviewRange({ start: at, seconds: sure })
      if (videoRef.current) {
        videoRef.current.src = url
        void videoRef.current.play()
      }
    } catch {
      toast.error('Bu andan oynatılamadı.', {
        description: 'Kayıt silinmiş veya o sırada yayın olmamış olabilir.',
      })
    } finally {
      setPreviewLoading(false)
    }
  }

  /**
   * Verilen andan tek kare çıkarır.
   *
   * <p>Seçimin doğruluğunu anlamak için oynatmaya gerek yok — başlangıç ve
   * bitiş kareleri yeterli. Yalnızca {@link FRAME_SECONDS} saniyelik bir
   * bölüm indirilip ilk karesi canvas'a çiziliyor; 3 dakikalık bir önizleme
   * indirmeye kıyasla yüzde biri kadar veri.
   */
  async function grabFrame(at: Date): Promise<string | null> {
    if (!channelId) return null
    const tokens = readTokens()
    if (!tokens) return null

    let url: string | null = null
    try {
      const response = await fetch(dvrApi.streamUrl(channelId, at, FRAME_SECONDS), {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      })
      if (!response.ok) return null
      url = URL.createObjectURL(await response.blob())

      const video = document.createElement('video')
      video.muted = true
      video.src = url
      // loadeddata: ilk kare cozuldu. Once beklenmezse canvas bos cikar.
      await new Promise<void>((resolve, reject) => {
        video.onloadeddata = () => resolve()
        video.onerror = () => reject(new Error('çözülemedi'))
        setTimeout(() => reject(new Error('zaman aşımı')), 15000)
      })

      const canvas = document.createElement('canvas')
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      canvas.getContext('2d')?.drawImage(video, 0, 0)
      return canvas.toDataURL('image/jpeg', 0.8)
    } catch {
      return null
    } finally {
      if (url) URL.revokeObjectURL(url)
    }
  }

  /** Seçimin başını/sonunu saniye saniye kaydırır — çizelgede sürüklemek kaba kalıyor. */
  function nudge(which: 'start' | 'end', deltaSeconds: number) {
    if (!selection) return
    const next = { ...selection }
    next[which] = new Date(selection[which].getTime() + deltaSeconds * 1000)
    // Bas sonu gecmesin; ters aralik klip ucunda zaten reddedilir.
    if (next.end.getTime() - next.start.getTime() < 1000) return
    setSelection(next)
  }

  async function createClip() {
    if (!channelId || !selection) return
    setCreating(true)
    try {
      await clipsApi.create(channelId, {
        start: selection.start.toISOString(),
        end: selection.end.toISOString(),
      })
      toast.success('Klip kuyruğa alındı.', {
        description: 'Hazır olunca Klipler sayfasından indirebilirsiniz.',
      })
      setSelection(null)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Klip oluşturulamadı.')
    } finally {
      setCreating(false)
    }
  }

  const selectionSeconds = selection
    ? Math.round((selection.end.getTime() - selection.start.getTime()) / 1000)
    : 0

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">Geriye sarma</h1>
        <p className="text-sm text-muted-foreground">
          Kayıtlı bir noktaya tıklayıp izleyin, sürükleyerek aralık seçip klip çıkarın.
        </p>
      </div>

      {dvrChannels.length === 0 ? (
        <Card>
          <CardContent className="p-6 text-sm text-muted-foreground">
            Geriye sarma açık kanal yok. Kanallar sayfasından bir kanalı düzenleyip
            “Geriye sarma kaydı” seçeneğini açın.
          </CardContent>
        </Card>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <Select value={channelId ?? undefined} onValueChange={setChannelId}>
              <SelectTrigger className="w-56">
                <SelectValue placeholder="Kanal seçin" />
              </SelectTrigger>
              <SelectContent>
                {dvrChannels.map((channel) => (
                  <SelectItem key={channel.id} value={channel.id}>
                    {channel.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            <div className="flex gap-1">
              {WINDOWS.map((w) => (
                <Button
                  key={w.hours}
                  size="sm"
                  variant={windowHours === w.hours ? 'default' : 'outline'}
                  onClick={() => {
                    setWindowHours(w.hours)
                    setSelection(null)
                  }}
                >
                  {w.label}
                </Button>
              ))}
            </div>

            <Button
              size="sm"
              variant="ghost"
              onClick={() => setNow(new Date())}
              title="Zaman çizelgesini şu ana getir"
            >
              Şimdiye getir
            </Button>

            {loading && <Loader2Icon className="size-4 animate-spin text-muted-foreground" />}
          </div>

          {error && (
            <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
              {error}
            </div>
          )}

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Zaman çizelgesi</CardTitle>
            </CardHeader>
            <CardContent>
              <Timeline
                from={from}
                to={now}
                spans={spans}
                selection={selection}
                onSelectionChange={setSelection}
                onSeek={(at) => void playFrom(at, PREVIEW_DEFAULT_SECONDS)}
              />
            </CardContent>
          </Card>

          <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
            <Card>
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-base">Önizleme</CardTitle>
                  {previewRange && (
                    <span className="text-xs text-muted-foreground">
                      {previewRange.start.toLocaleTimeString('tr-TR')} +
                      {formatDuration(previewRange.seconds)}
                    </span>
                  )}
                </div>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                <div className="relative">
                  <video
                    ref={videoRef}
                    controls
                    playsInline
                    className="aspect-video w-full rounded-lg bg-black"
                  />
                  {previewLoading && (
                    <div className="absolute inset-0 grid place-items-center rounded-lg bg-black/60">
                      <Loader2Icon className="size-6 animate-spin text-white" />
                    </div>
                  )}
                </div>

                {/* Seçimin doğruluğunu anlamak için oynatmaya gerek yok.
                    Şerit, seçim boyunca eşit aralıklı kareler gösteriyor:
                    kullanıcı yalnızca sınırları değil NE kaydettiğini görüyor.
                    Kareler seçim değiştikçe kendiliğinden tazeleniyor. */}
                {selection ? (
                  <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
                    {frames.map(({ at, image }, i) => (
                      <button
                        key={i}
                        type="button"
                        // Kareye tiklamak o andan oynatiyor: serit hem ozet
                        // hem de secimin ICINE giris noktasi. Uzun bir secimi
                        // bastan sona indirmek mumkun olmadigi icin
                        // "hepsini gorme" pratikte boyle saglaniyor.
                        title={`${at.toLocaleTimeString('tr-TR')} anından oynat`}
                        onClick={() => void playFrom(at, PREVIEW_DEFAULT_SECONDS)}
                        className="flex flex-col gap-1 text-left"
                      >
                        <div className="relative aspect-video overflow-hidden rounded-md border bg-black transition hover:border-primary-light">
                          {image ? (
                            <img src={image} alt="" className="size-full object-cover" />
                          ) : (
                            <div className="grid size-full place-items-center text-[10px] text-muted-foreground">
                              {framesLoading ? (
                                <Loader2Icon className="size-3 animate-spin" />
                              ) : (
                                'yok'
                              )}
                            </div>
                          )}
                          {/* Ilk ve son kare isaretli: seride bakan kisi
                              siniri ortadaki karelerden ayirt edebilsin. */}
                          {(i === 0 || i === frames.length - 1) && (
                            <span className="absolute left-1 top-1 rounded bg-primary-light/90 px-1 text-[9px] font-medium text-black">
                              {i === 0 ? 'baş' : 'son'}
                            </span>
                          )}
                        </div>
                        <span className="text-center text-[10px] tabular-nums text-muted-foreground">
                          {at.toLocaleTimeString('tr-TR')}
                        </span>
                      </button>
                    ))}
                  </div>
                ) : (
                  <p className="text-xs text-muted-foreground">
                    Çizelgeye tıklayarak o andan {formatDuration(PREVIEW_DEFAULT_SECONDS)} izleyin,
                    sürükleyerek aralık seçin.
                  </p>
                )}

                {selection && (
                  <p className="text-xs text-muted-foreground">
                    Şerit seçimin başından sonuna eşit aralıklarla alınır — klipte
                    göreceğiniz içerik budur. Bir kareye tıklayarak o andan
                    izleyebilirsiniz.
                  </p>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Seçilen aralık</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
                {!selection ? (
                  <p className="text-sm text-muted-foreground">
                    Zaman çizelgesinde sürükleyerek aralık seçin.
                  </p>
                ) : (
                  <>
                    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
                      <dt className="text-muted-foreground">Başlangıç</dt>
                      <dd>{selection.start.toLocaleString('tr-TR')}</dd>
                      <dt className="text-muted-foreground">Bitiş</dt>
                      <dd>{selection.end.toLocaleString('tr-TR')}</dd>
                      <dt className="text-muted-foreground">Süre</dt>
                      <dd>{formatDuration(selectionSeconds)}</dd>
                      <dt className="text-muted-foreground">Tahmini boyut</dt>
                      <dd>{estimateSize(selectionSeconds)}</dd>
                    </dl>

                    {/* Çizelgede sürüklemek saniye hassasiyeti vermiyor;
                        24 saatlik pencerede bir piksel ~30 saniye. */}
                    <div className="flex flex-col gap-1.5 rounded-lg border p-2">
                      <span className="text-xs text-muted-foreground">İnce ayar</span>
                      {(['start', 'end'] as const).map((uc) => (
                        <div key={uc} className="flex items-center gap-1">
                          <span className="w-14 text-xs text-muted-foreground">
                            {uc === 'start' ? 'Başlangıç' : 'Bitiş'}
                          </span>
                          {[-60, -10, 10, 60].map((d) => (
                            <Button
                              key={d}
                              size="sm"
                              variant="outline"
                              className="h-7 flex-1 px-1 text-xs"
                              onClick={() => nudge(uc, d)}
                            >
                              {d > 0 ? `+${d}` : d}
                            </Button>
                          ))}
                        </div>
                      ))}
                    </div>

                    {selectionSeconds > 2 * 3600 && (
                      <Badge variant="error">En fazla 2 saatlik klip alınabilir</Badge>
                    )}

                    {/* Klip almadan once secimi izlemek. Uc, bolumu belleğe
                        indirdiği icin tamami oynatilamiyor; PREVIEW_MAX_SECONDS'i
                        asan secimlerde bastan o kadari geliyor ve durum
                        dugmenin altinda ACIKCA yaziliyor -- sessizce kirpmak
                        kullaniciyi "hepsini gordum" sanisina dusururdu. */}
                    <Button
                      variant="outline"
                      onClick={() => void playFrom(selection.start, selectionSeconds)}
                      disabled={previewLoading || selectionSeconds < 1}
                    >
                      {previewLoading ? (
                        <Loader2Icon className="animate-spin" />
                      ) : (
                        <PlayIcon />
                      )}
                      Seçimi oynat
                    </Button>
                    {selectionSeconds > PREVIEW_MAX_SECONDS && (
                      <p className="-mt-1 text-xs text-muted-foreground">
                        Seçim {formatDuration(selectionSeconds)}; oynatıcı baştan{' '}
                        {formatDuration(PREVIEW_MAX_SECONDS)} gösterir. Sonrasını görmek
                        için şeritteki karelere tıklayın.
                      </p>
                    )}

                    <div className="flex gap-2">
                      <Button
                        className="flex-1"
                        onClick={() => void createClip()}
                        disabled={creating || selectionSeconds > 2 * 3600 || selectionSeconds < 1}
                      >
                        {creating ? <Loader2Icon className="animate-spin" /> : <ScissorsIcon />}
                        Klip oluştur
                      </Button>
                      <Button variant="outline" onClick={() => setSelection(null)} title="Seçimi temizle">
                        <XIcon />
                      </Button>
                    </div>
                  </>
                )}
              </CardContent>
            </Card>

            {/* Çizelge yalnızca GEÇMİŞİ gösterebiliyor; gelecekteki bir aralık
                orada seçilemez. Planlı kayıt formu o boşluğu kapatıyor ve
                seçim varsa ondan doldurulabiliyor. */}
            <ScheduledRecordingCard channelId={channelId} selection={selection} />
          </div>
        </>
      )}
    </div>
  )
}
