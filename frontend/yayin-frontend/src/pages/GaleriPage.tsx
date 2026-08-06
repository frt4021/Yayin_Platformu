import { useCallback, useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi, screenshotsApi } from '@/api/endpoints'
import { formatBytes } from '@/api/upload'
import type { ChannelDto, ScreenshotDto } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  DownloadIcon,
  ImageIcon,
  Loader2Icon,
  Trash2Icon,
  XIcon,
} from 'lucide-react'

/** Kareler gün gün gruplanıyor; kronolojik bir arşivde tarih en doğal ayraç. */
function gunBasligi(iso: string): string {
  const d = new Date(iso)
  const bugun = new Date()
  const dun = new Date(bugun.getTime() - 86400000)
  const ayniGun = (a: Date, b: Date) => a.toDateString() === b.toDateString()
  if (ayniGun(d, bugun)) return 'Bugün'
  if (ayniGun(d, dun)) return 'Dün'
  return d.toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', year: 'numeric' })
}

function saat(iso: string): string {
  return new Date(iso).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' })
}

export function GaleriPage() {
  const [shots, setShots] = useState<ScreenshotDto[]>([])
  const [channels, setChannels] = useState<ChannelDto[]>([])
  const [channelId, setChannelId] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<Set<string>>(new Set())
  const [preview, setPreview] = useState<ScreenshotDto | null>(null)

  const load = useCallback(async (kanal: string) => {
    try {
      setShots(await screenshotsApi.gallery(kanal || undefined))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Galeri yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(channelId)
  }, [load, channelId])

  useEffect(() => {
    void channelsApi.list().then(setChannels).catch(() => {})
  }, [])

  // Kronolojik gruplama. Liste zaten yeniden eskiye sirali geliyor.
  const gruplar = useMemo(() => {
    const map = new Map<string, ScreenshotDto[]>()
    for (const shot of shots) {
      const key = gunBasligi(shot.capturedAt)
      const arr = map.get(key)
      if (arr) arr.push(shot)
      else map.set(key, [shot])
    }
    return [...map.entries()]
  }, [shots])

  async function remove(shot: ScreenshotDto) {
    if (!confirm('Bu kare kalıcı olarak silinecek. Emin misiniz?')) return
    setBusy((prev) => new Set(prev).add(shot.id))
    try {
      await screenshotsApi.remove(shot.id)
      toast.success('Kare silindi.')
      if (preview?.id === shot.id) setPreview(null)
      await load(channelId)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Kare silinemedi.')
    } finally {
      setBusy((prev) => {
        const next = new Set(prev)
        next.delete(shot.id)
        return next
      })
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">Ekran görüntüleri</h1>
        <Badge variant="secondary">{shots.length} kare</Badge>

        <select
          aria-label="Kanal"
          className="ml-auto h-9 rounded-md border bg-input-bg px-3 text-sm"
          value={channelId}
          onChange={(e) => setChannelId(e.target.value)}
        >
          <option value="">Tüm kanallar</option>
          {channels.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>

      {error && <p className="text-sm text-status-error">{error}</p>}

      {loading ? (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" />
          Yükleniyor…
        </div>
      ) : shots.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-xl border border-dashed p-12 text-center">
          <ImageIcon className="size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            Henüz kare yok. İzleme ekranında kamera düğmesiyle yakalayabilirsiniz.
          </p>
        </div>
      ) : (
        gruplar.map(([gun, liste]) => (
          <section key={gun} className="flex flex-col gap-2">
            <h2 className="text-sm font-medium text-muted-foreground">{gun}</h2>
            <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6">
              {liste.map((shot) => (
                <div
                  key={shot.id}
                  className="group relative overflow-hidden rounded-lg border bg-card"
                >
                  <button
                    type="button"
                    onClick={() => setPreview(shot)}
                    className="block aspect-video w-full bg-black"
                    title="Büyüt"
                  >
                    <img
                      src={shot.viewUrl}
                      alt=""
                      loading="lazy"
                      className="size-full object-cover"
                    />
                  </button>

                  <div className="flex items-center justify-between gap-1 px-2 py-1.5 text-xs">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{shot.channelName}</div>
                      <div className="text-muted-foreground">
                        {saat(shot.capturedAt)} · {formatBytes(shot.sizeBytes)}
                      </div>
                    </div>
                    <div className="flex shrink-0 gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
                      <Button variant="ghost" size="icon" className="size-6" asChild title="İndir">
                        <a href={shot.downloadUrl} download={shot.fileName}>
                          <DownloadIcon />
                        </a>
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-6"
                        title="Sil"
                        disabled={busy.has(shot.id)}
                        onClick={() => void remove(shot)}
                      >
                        {busy.has(shot.id) ? (
                          <Loader2Icon className="animate-spin" />
                        ) : (
                          <Trash2Icon />
                        )}
                      </Button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </section>
        ))
      )}

      {/* Büyütme katmanı. Dialog yerine düz katman: tek bir görsel için
          odak tuzağı ve başlık yapısı kurmak gereksiz ağırlık olurdu. */}
      {preview && (
        <div
          className={cn(
            'fixed inset-0 z-50 flex flex-col items-center justify-center gap-3 bg-black/90 p-6',
          )}
          onClick={() => setPreview(null)}
        >
          <img
            src={preview.viewUrl}
            alt=""
            className="max-h-[80vh] max-w-full rounded-lg object-contain"
            onClick={(e) => e.stopPropagation()}
          />
          <div className="text-center text-sm text-white/80">
            <div className="font-medium text-white">{preview.channelName}</div>
            <div>
              {new Date(preview.capturedAt).toLocaleString('tr-TR')}
              {preview.width ? ` · ${preview.width}x${preview.height}` : ''}
              {` · ${formatBytes(preview.sizeBytes)} · ${preview.capturedBy}`}
            </div>
          </div>
          <Button variant="secondary" onClick={() => setPreview(null)}>
            <XIcon />
            Kapat
          </Button>
        </div>
      )}
    </div>
  )
}
