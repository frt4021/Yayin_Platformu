import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { videosApi } from '@/api/endpoints'
import { formatBytes, formatDuration } from '@/api/upload'
import type { VideoDto } from '@/api/types'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import {
  FilmIcon,
  Loader2Icon,
  PencilIcon,
  PlayIcon,
  SearchIcon,
  Trash2Icon,
  UploadIcon,
} from 'lucide-react'
import { VideoEditDialog } from './videos/VideoEditDialog'
import { VideoPlayerDialog } from './videos/VideoPlayerDialog'
import { VideoUploadDialog } from './videos/VideoUploadDialog'

/** İşlenen kayıt varken liste tazelenir; yoksa boşuna sorgulanmaz. */
const REFRESH_MS = 5000

export function VideosPage() {
  const { hasRole } = useAuth()
  const canManage = hasRole('Yönetici', 'Moderatör')

  const [videos, setVideos] = useState<VideoDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [uploadOpen, setUploadOpen] = useState(false)
  const [playing, setPlaying] = useState<VideoDto | null>(null)
  const [editing, setEditing] = useState<VideoDto | null>(null)
  const [pending, setPending] = useState<Set<string>>(new Set())

  const load = useCallback(async (query: string) => {
    try {
      setVideos(await videosApi.list(query))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Videolar yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  // Arama yazarken her tuşta istek atmamak için kısa bir gecikme.
  useEffect(() => {
    const timer = setTimeout(() => void load(search), 300)
    return () => clearTimeout(timer)
  }, [load, search])

  // Yalnızca işlenen/yüklenen kayıt varken tazele: hepsi HAZIR ise
  // sürekli sorgulamak sunucuyu boşuna yorar.
  const active = videos.some((v) => v.status === 'YUKLENIYOR' || v.status === 'ISLENIYOR')
  useEffect(() => {
    if (!active) return
    const timer = setInterval(() => void load(search), REFRESH_MS)
    return () => clearInterval(timer)
  }, [active, load, search])

  async function remove(video: VideoDto) {
    if (!confirm(`"${video.title}" ve dosyası kalıcı olarak silinecek. Emin misiniz?`)) return
    setPending((prev) => new Set(prev).add(video.id))
    try {
      await videosApi.remove(video.id)
      toast.success(`${video.title} silindi.`)
      await load(search)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Video silinemedi.')
    } finally {
      setPending((prev) => {
        const next = new Set(prev)
        next.delete(video.id)
        return next
      })
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">Video kütüphanesi</h1>
        <Badge variant="secondary">{videos.length} video</Badge>

        <div className="relative ml-auto">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
          <Input
            placeholder="Başlıkta ara"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-56 pl-8"
          />
        </div>

        {canManage && (
          <Button onClick={() => setUploadOpen(true)}>
            <UploadIcon />
            Video yükle
          </Button>
        )}
      </div>

      {error && <p className="text-sm text-status-error">{error}</p>}

      {loading ? (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" />
          Yükleniyor…
        </div>
      ) : videos.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-xl border border-dashed p-12 text-center">
          <FilmIcon className="size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            {search ? `"${search}" ile eşleşen video yok.` : 'Kütüphane boş.'}
          </p>
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {videos.map((video) => (
            <VideoCard
              key={video.id}
              video={video}
              busy={pending.has(video.id)}
              canManage={canManage}
              onPlay={() => setPlaying(video)}
              onEdit={() => setEditing(video)}
              onDelete={() => void remove(video)}
            />
          ))}
        </div>
      )}

      <VideoUploadDialog
        open={uploadOpen}
        onOpenChange={setUploadOpen}
        onUploaded={() => void load(search)}
      />
      <VideoPlayerDialog
        video={playing}
        open={playing !== null}
        onOpenChange={(open) => !open && setPlaying(null)}
      />
      <VideoEditDialog
        video={editing}
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        onSaved={() => void load(search)}
      />
    </div>
  )
}

/**
 * Fare kartın üzerinde bu kadar beklerse önizleme başlar.
 *
 * <p>Gecikme şart: ızgaranın üzerinden fare geçirmek onlarca videoyu birden
 * indirmeye başlatırdı. 400 ms, "bakıyorum" ile "geçiyorum" arasını ayırmaya
 * yetiyor.
 */
const PREVIEW_DELAY_MS = 400

function VideoCard({
  video,
  busy,
  canManage,
  onPlay,
  onEdit,
  onDelete,
}: {
  video: VideoDto
  busy: boolean
  canManage: boolean
  onPlay: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const playable = video.status === 'HAZIR'
  const [previewOn, setPreviewOn] = useState(false)
  const timerRef = useRef<number | null>(null)
  const videoRef = useRef<HTMLVideoElement>(null)

  /**
   * Önizleme, işçinin ürettiği kısa klip — asıl video değil.
   *
   * <p>Adres listede geldiği için hover'da ek istek yok. Klip zaten sessiz,
   * kısa ve düşük çözünürlüklü (~200-400 KB); asıl videoyu oynatmak 1080p
   * bir kaynakta birkaç saniye için birkaç megabayt indirmek olurdu.
   */
  function startPreview() {
    if (!playable || !video.previewUrl) return
    timerRef.current = window.setTimeout(() => {
      setPreviewOn(true)
      void videoRef.current?.play().catch(() => {})
    }, PREVIEW_DELAY_MS)
  }

  function stopPreview() {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
    setPreviewOn(false)
    const el = videoRef.current
    if (el) {
      el.pause()
      // Basa sariyoruz: fare tekrar geldiginde klip bastan oynasin.
      el.currentTime = 0
    }
  }

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }, [])

  return (
    <div
      className="group flex flex-col overflow-hidden rounded-xl border bg-card transition-colors hover:bg-accent/30"
      onMouseEnter={startPreview}
      onMouseLeave={stopPreview}
    >
      <div className="relative aspect-video bg-black">
        {video.thumbnailUrl ? (
          <img src={video.thumbnailUrl} alt="" className="size-full object-cover" />
        ) : (
          <div className="grid size-full place-items-center">
            <FilmIcon className="size-8 text-muted-foreground" />
          </div>
        )}

        {/* Önizleme küçük resmin ÜSTÜNE biniyor. Küçük resmi kaldırıp yerine
            video koymak, klip ilk kareyi çözene kadar siyah bir boşluk
            bırakırdı; bu şekilde geçiş görünmüyor.

            src yalnızca önizleme AÇIKKEN veriliyor: element her zaman
            dursaydı preload="none" olsa bile tarayıcılar bir kısmını
            önceden çekebiliyor ve ızgarada onlarca istek doğardı. */}
        {previewOn && video.previewUrl && (
          <video
            ref={videoRef}
            src={video.previewUrl}
            muted
            loop
            playsInline
            autoPlay
            preload="auto"
            className="absolute inset-0 size-full object-cover opacity-0 transition-opacity duration-300 group-hover:opacity-100"
          />
        )}

        {playable && (
          <button
            type="button"
            onClick={onPlay}
            title="Oynat"
            className="absolute inset-0 grid place-items-center bg-black/40 opacity-0 transition-opacity group-hover:opacity-100"
          >
            <PlayIcon className="size-10 text-white drop-shadow" />
          </button>
        )}

        {video.durationSeconds != null && (
          <span className="absolute bottom-1.5 right-1.5 rounded bg-black/75 px-1.5 py-0.5 text-xs font-medium text-white">
            {formatDuration(video.durationSeconds)}
          </span>
        )}

        <div className="absolute left-1.5 top-1.5">
          <StatusBadge video={video} />
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-1 p-3">
        <div className="truncate text-sm font-medium" title={video.title}>
          {video.title}
        </div>
        <div className="text-xs text-muted-foreground">
          {formatBytes(video.sizeBytes)}
          {video.width ? ` · ${video.width}x${video.height}` : ''}
          {video.uploadedBy ? ` · ${video.uploadedBy}` : ''}
        </div>

        {video.status === 'HATA' && video.error && (
          <p className="mt-1 line-clamp-2 text-xs text-status-error" title={video.error}>
            {video.error}
          </p>
        )}

        {canManage && (
          <div className={cn(
            'mt-2 flex gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100',
          )}>
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              title="Düzenle"
              disabled={video.status === 'YUKLENIYOR'}
              onClick={onEdit}
            >
              <PencilIcon />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="size-7"
              title={video.status === 'ISLENIYOR' ? 'İşlenirken silinemez' : 'Sil'}
              disabled={busy || video.status === 'ISLENIYOR'}
              onClick={onDelete}
            >
              {busy ? <Loader2Icon className="animate-spin" /> : <Trash2Icon />}
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

function StatusBadge({ video }: { video: VideoDto }) {
  switch (video.status) {
    case 'HAZIR':
      return null // Hazir olan normal durum; rozet gostermek gurultu olurdu.
    case 'YUKLENIYOR':
      return <Badge variant="warning">Yükleniyor</Badge>
    case 'ISLENIYOR':
      return (
        <Badge variant="default" className="gap-1">
          <Loader2Icon className="size-3 animate-spin" />
          İşleniyor
        </Badge>
      )
    case 'HATA':
      return <Badge variant="error">Hata</Badge>
  }
}
