import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi, clipsApi, dvrApi } from '@/api/endpoints'
import { readTokens } from '@/api/tokens'
import type { ChannelDto, TimelineSpan } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Loader2Icon, ScissorsIcon } from 'lucide-react'
import { Timeline, type Selection } from './Timeline'

/** Zaman çizelgesi pencereleri. 7 gün DVR saklama süresiyle aynı. */
const WINDOWS = [
  { label: 'Son 1 saat', hours: 1 },
  { label: 'Son 6 saat', hours: 6 },
  { label: 'Son 24 saat', hours: 24 },
  { label: 'Son 7 gün', hours: 24 * 7 },
] as const

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

  /**
   * Geçmişten oynatma. Uç token gerektirdiği için <video src> ile doğrudan
   * kullanılamıyor; parça fetch ile alınıp blob olarak veriliyor.
   */
  async function seek(at: Date) {
    if (!channelId) return
    const tokens = readTokens()
    if (!tokens) return

    const preview = 60
    try {
      const response = await fetch(dvrApi.streamUrl(channelId, at, preview), {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current)
      const url = URL.createObjectURL(await response.blob())
      objectUrlRef.current = url
      if (videoRef.current) {
        videoRef.current.src = url
        void videoRef.current.play()
      }
    } catch {
      toast.error('Bu andan oynatılamadı.', {
        description: 'Kayıt silinmiş veya o sırada yayın olmamış olabilir.',
      })
    }
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
                onSeek={(at) => void seek(at)}
              />
            </CardContent>
          </Card>

          <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Önizleme</CardTitle>
              </CardHeader>
              <CardContent>
                <video
                  ref={videoRef}
                  controls
                  playsInline
                  className="aspect-video w-full rounded-lg bg-black"
                />
                <p className="mt-2 text-xs text-muted-foreground">
                  Tıklanan andan itibaren 1 dakikalık bölüm yüklenir.
                </p>
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

                    {selectionSeconds > 2 * 3600 && (
                      <Badge variant="destructive">
                        En fazla 2 saatlik klip alınabilir
                      </Badge>
                    )}

                    <Button
                      onClick={() => void createClip()}
                      disabled={creating || selectionSeconds > 2 * 3600 || selectionSeconds < 1}
                    >
                      {creating ? <Loader2Icon className="animate-spin" /> : <ScissorsIcon />}
                      Klip oluştur
                    </Button>
                  </>
                )}
              </CardContent>
            </Card>
          </div>
        </>
      )}
    </div>
  )
}
