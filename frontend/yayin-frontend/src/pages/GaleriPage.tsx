import { useCallback, useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi, screenshotsApi } from '@/api/endpoints'
import { formatBytes } from '@/api/upload'
import type { ChannelDto, ScreenshotDto } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  DownloadIcon,
  ImageIcon,
  Loader2Icon,
  Trash2Icon,
  XIcon,
} from 'lucide-react'

/** Radix Select boş dizge değeri kabul etmiyor — "tüm kanallar" için ayrı bir işaret değeri gerekiyor. */
const TUM_KANALLAR = 'hepsi'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { GALERI_TOUR_SEEN_KEY, GALERI_TOUR_STEPS } from '@/components/tour/galeriSteps'

function boyut(bayt: number): string {
  if (bayt >= 1024 ** 3) return `${(bayt / 1024 ** 3).toFixed(1)} GB`
  if (bayt >= 1024 ** 2) return `${Math.round(bayt / 1024 ** 2)} MB`
  return `${Math.round(bayt / 1024)} KB`
}

/**
 * Kareleri kanala göre gruplar — ClipsPage.tsx'teki {@code kanalaGoreGrupla}
 * ile aynı fikir: aynı kanalın kareleri listenin her yerine dağılmasın.
 * Kanalı silinmiş kareler en sona düşer.
 */
function kanalaGoreGrupla(shots: ScreenshotDto[]) {
  const harita = new Map<string, ScreenshotDto[]>()
  for (const s of shots) {
    const anahtar = s.channelId ?? `silinmis:${s.channelName}`
    const mevcut = harita.get(anahtar)
    if (mevcut) mevcut.push(s)
    else harita.set(anahtar, [s])
  }

  return [...harita.entries()]
    .map(([anahtar, liste]) => ({
      anahtar,
      ad: liste[0].channelName ?? 'Bilinmeyen kanal',
      silinmis: liste[0].channelId === null,
      liste: [...liste].sort((a, b) => Date.parse(b.capturedAt) - Date.parse(a.capturedAt)),
      toplamBayt: liste.reduce((t, s) => t + s.sizeBytes, 0),
    }))
    .sort((a, b) => {
      if (a.silinmis !== b.silinmis) return a.silinmis ? 1 : -1
      return a.ad.localeCompare(b.ad, 'tr')
    })
}

export function GaleriPage() {
  const [shots, setShots] = useState<ScreenshotDto[]>([])
  const [channels, setChannels] = useState<ChannelDto[]>([])
  const [channelId, setChannelId] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<Set<string>>(new Set())
  const [preview, setPreview] = useState<ScreenshotDto | null>(null)

  const tur = usePageTour(GALERI_TOUR_SEEN_KEY)

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

  // Kanal kanal gruplama — bkz. kanalaGoreGrupla.
  const gruplar = useMemo(() => kanalaGoreGrupla(shots), [shots])

  // Büyütme katmanında sağa/sola kaydırmak için: ızgarada görünen sırayla
  // düz bir liste. Kanal sınırını da aşarak dolaşabiliyor.
  const siraliListe = useMemo(() => gruplar.flatMap((g) => g.liste), [gruplar])

  function komsuyaGit(delta: number) {
    if (!preview) return
    const idx = siraliListe.findIndex((s) => s.id === preview.id)
    if (idx === -1) return
    const sonraki = siraliListe[(idx + delta + siraliListe.length) % siraliListe.length]
    setPreview(sonraki)
  }

  // Ok tuşlarıyla da kaydırılabilsin; Escape kapatsın.
  useEffect(() => {
    if (!preview) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'ArrowRight') komsuyaGit(1)
      else if (e.key === 'ArrowLeft') komsuyaGit(-1)
      else if (e.key === 'Escape') setPreview(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [preview, siraliListe])

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
        <h1 className="text-3xl font-semibold tracking-tight">Ekran görüntüleri</h1>
        <Badge variant="secondary">{shots.length} kare</Badge>
        <TourTrigger onClick={tur.start} />

        <Select
          value={channelId || TUM_KANALLAR}
          onValueChange={(v) => setChannelId(v === TUM_KANALLAR ? '' : v)}
        >
          <SelectTrigger
            data-tour="galeri-filtre"
            aria-label="Kanal"
            className="ml-auto w-auto min-w-40 rounded-full"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={TUM_KANALLAR}>Tüm kanallar</SelectItem>
            {channels.map((c) => (
              <SelectItem key={c.id} value={c.id}>
                {c.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
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
        <div data-tour="galeri-izgara" className="flex flex-col gap-8">
        {gruplar.map((grup) => (
          <section key={grup.anahtar} className="flex flex-col gap-3">
            <div className="flex flex-wrap items-baseline gap-2">
              <h2 className="text-lg font-semibold tracking-tight">{grup.ad}</h2>
              {grup.silinmis && (
                <Badge variant="outline" className="text-[11px]">silinmiş kanal</Badge>
              )}
              <span className="text-sm text-muted-foreground">
                {grup.liste.length} kare · {boyut(grup.toplamBayt)}
              </span>
            </div>
            <div className="grid gap-x-4 gap-y-6 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-6">
              {grup.liste.map((shot) => (
                <div key={shot.id} className="group flex flex-col">
                  <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
                    <button
                      type="button"
                      onClick={() => setPreview(shot)}
                      className="block size-full"
                      title="Büyüt"
                    >
                      <img
                        src={shot.viewUrl}
                        alt=""
                        loading="lazy"
                        className="size-full object-cover"
                      />
                    </button>

                    <div
                      data-tour="galeri-eylemler"
                      className="absolute right-1.5 top-1.5 flex gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100"
                    >
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-7 rounded-full bg-black/70 text-white hover:bg-black/90"
                        asChild
                        title="İndir"
                      >
                        <a href={shot.downloadUrl} download={shot.fileName}>
                          <DownloadIcon className="size-3.5" />
                        </a>
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-7 rounded-full bg-black/70 text-white hover:bg-black/90"
                        title="Sil"
                        disabled={busy.has(shot.id)}
                        onClick={() => void remove(shot)}
                      >
                        {busy.has(shot.id) ? (
                          <Loader2Icon className="size-3.5 animate-spin" />
                        ) : (
                          <Trash2Icon className="size-3.5" />
                        )}
                      </Button>
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => setPreview(shot)}
                    className="mt-2 flex flex-col items-start text-left"
                  >
                    <span className="line-clamp-2 text-sm font-medium leading-snug">
                      {shot.channelName}
                    </span>
                    <span className="mt-1 truncate text-xs text-muted-foreground">
                      {new Date(shot.capturedAt).toLocaleString('tr-TR')} · {formatBytes(shot.sizeBytes)}
                    </span>
                  </button>
                </div>
              ))}
            </div>
          </section>
        ))}
        </div>
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
          <button
            type="button"
            onClick={() => setPreview(null)}
            title="Kapat"
            className="absolute right-4 top-4 grid size-9 place-items-center rounded-full bg-black/60 text-white transition-colors hover:bg-black/80"
          >
            <XIcon className="size-4" />
          </button>

          {/* Sağa/sola kaydırma: aynı kanal sınırını da aşarak tüm ızgarada dolaşır. */}
          {siraliListe.length > 1 && (
            <>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation()
                  komsuyaGit(-1)
                }}
                title="Önceki"
                className="absolute left-4 top-1/2 grid size-10 -translate-y-1/2 place-items-center rounded-full bg-black/60 text-white transition-colors hover:bg-black/80"
              >
                <ChevronLeftIcon className="size-5" />
              </button>
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation()
                  komsuyaGit(1)
                }}
                title="Sonraki"
                className="absolute right-4 top-1/2 grid size-10 -translate-y-1/2 place-items-center rounded-full bg-black/60 text-white transition-colors hover:bg-black/80"
              >
                <ChevronRightIcon className="size-5" />
              </button>
            </>
          )}

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
        </div>
      )}

      <GuidedTour open={tur.open} onClose={tur.close} steps={GALERI_TOUR_STEPS} />
    </div>
  )
}
