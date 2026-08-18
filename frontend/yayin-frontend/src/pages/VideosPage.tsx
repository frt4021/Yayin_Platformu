import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthContext'
import { videosApi } from '@/api/endpoints'
import { formatBytes, formatDuration } from '@/api/upload'
import type { VideoDto, VideoLinks } from '@/api/types'
import { subtitleLangs } from '@/player/SubtitleOverlay'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import {
  DownloadIcon,
  FilmIcon,
  Loader2Icon,
  PencilIcon,
  PlayIcon,
  SearchIcon,
  Trash2Icon,
  UploadIcon,
} from 'lucide-react'
import { VideoEditDialog } from './videos/VideoEditDialog'
import { VideoUploadDialog } from './videos/VideoUploadDialog'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { VIDEOS_TOUR_SEEN_KEY, VIDEOS_TOUR_STEPS } from '@/components/tour/videosSteps'

/** İşlenen kayıt varken liste tazelenir; yoksa boşuna sorgulanmaz. */
const REFRESH_MS = 5000

/** 1.234 görüntülenme — YouTube tarzı binlik ayraç. */
function formatViews(count: number): string {
  return count.toLocaleString('tr') + ' görüntülenme'
}

export function VideosPage() {
  // Kütüphane PAYLAŞILAN bir arşiv: giriş yapmış herkes tüm videoları görür
  // ve izler. Yükleme yalnızca Yönetici/Moderatör'de; düzenleme ve silme
  // ayrıca sahibine özel (yönetici tümüne dokunabilir). Klip ve ekran
  // görüntüsünden ayrılıyor — onlar kişisel kayıt içeriği.

  const [videos, setVideos] = useState<VideoDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [uploadOpen, setUploadOpen] = useState(false)
  // Yukleme, duzenleme ve silme yalnizca bu rollerde. Izleyici salt okuma.
  const { session, hasRole } = useAuth()
  const yazabilir = hasRole('Yönetici', 'Moderatör')
  const [editing, setEditing] = useState<VideoDto | null>(null)
  const [pending, setPending] = useState<Set<string>>(new Set())

  // Oynatılan video (sayfa içi oynatıcı; dialog değil — YouTube gibi solda
  // oynatıcı, sağda liste).
  const [playing, setPlaying] = useState<VideoDto | null>(null)
  const [links, setLinks] = useState<VideoLinks | null>(null)
  const [linksError, setLinksError] = useState<string | null>(null)
  const playerVideoRef = useRef<HTMLVideoElement>(null)
  /** 'kapali' ya da links.subtitles içindeki bir srcLang. */
  const [aktifAltyazi, setAktifAltyazi] = useState<string>('kapali')

  const tur = usePageTour(VIDEOS_TOUR_SEEN_KEY)

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

  // Oynatılan video listeden kalktıysa (silindi) oynatıcıyı kapat.
  useEffect(() => {
    if (playing && !videos.some((v) => v.id === playing.id)) {
      setPlaying(null)
    }
  }, [videos, playing])

  // Oynatılan video değişince imzalı izleme adresi al. Listede değil burada:
  // imzalı adresin süresi üretildiği anda işlemeye başlıyor.
  useEffect(() => {
    if (!playing) {
      setLinks(null)
      setLinksError(null)
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const result = await videosApi.links(playing.id)
        if (!cancelled) {
          setLinks(result)
          setLinksError(null)
          // <track default> ile aynı fikir: ilk üretilen dil varsayılan gösterilsin.
          setAktifAltyazi(result.subtitles[0]?.lang ?? 'kapali')
        }
      } catch (e) {
        if (!cancelled) {
          setLinksError(e instanceof ApiError ? e.message : 'İzleme adresi alınamadı.')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [playing])

  /**
   * Tarayıcının kendi "CC" menüsü çoğu kullanıcı için gizli/keşfedilmesi
   * zor kaldığı için (gerçek geri bildirim, bkz. ClipsPage.tsx'teki aynı
   * çözüm) burada açık bir seçici sunuluyor. `<track>`'lar zaten DOM'da —
   * bu yalnızca hangisinin {@code mode}'unu "showing" yaptığını
   * değiştiriyor, ek bir istek atmıyor.
   */
  useEffect(() => {
    const el = playerVideoRef.current
    if (!el) return
    for (const t of Array.from(el.textTracks)) {
      t.mode = t.language === aktifAltyazi ? 'showing' : 'hidden'
    }
  }, [aktifAltyazi, links?.stream])

  // Oynatılan video listede değişmiş olabilir (durum güncellenmiş); en
  // taze halini tut.
  const playingCurrent = playing ? videos.find((v) => v.id === playing.id) ?? playing : null

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
        <h1 className="text-3xl font-semibold tracking-tight">Video kütüphanesi</h1>
        <Badge variant="secondary">{videos.length} video</Badge>
        <TourTrigger onClick={tur.start} />

        <div data-tour="videolar-arama" className="relative ml-auto">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
          <Input
            placeholder="Başlıkta ara"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-56 pl-8"
          />
        </div>

        {/* İzleyici yükleyemez. Sunucu da aynı kuralı uyguluyor; buradaki
            gizleme kullanıcıyı reddedilecek bir düğmeyle karşılaştırmamak
            için. */}
        {yazabilir && (
          <Button data-tour="videolar-yukle" onClick={() => setUploadOpen(true)}>
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
      ) : playingCurrent ? (
        // YouTube tarzı: solda oynatıcı + başlık/açıklama, sağda liste.
        <div className="flex flex-col gap-6 lg:flex-row">
          <div className="min-w-0 flex-1">
            <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
              {/* Videonun kendi üzerinde, sağ üstte -- tarayıcının native
                  "CC" menüsü gizli/keşfedilmesi zor kaldığı için (gerçek
                  geri bildirim). Denetim çubuğu ALTTA olduğu için çakışma
                  yok. */}
              {links && links.subtitles.length > 0 && (
                <label className="absolute right-2 top-2 z-10 flex items-center gap-2 rounded-md bg-black/70 px-2 py-1 text-sm text-white backdrop-blur-sm">
                  Altyazı
                  <select
                    aria-label="Altyazı dili"
                    className="h-7 rounded-md border border-white/30 bg-black/60 px-1.5 text-sm text-white"
                    value={aktifAltyazi}
                    onChange={(e) => setAktifAltyazi(e.target.value)}
                  >
                    <option value="kapali">Kapalı</option>
                    {links.subtitles.map((t) => (
                      <option key={t.lang} value={t.lang}>
                        {subtitleLangs().find((l) => l.kod === t.lang)?.ad ?? t.lang}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              {linksError ? (
                <div className="grid size-full place-items-center p-4 text-center text-sm text-status-error">
                  {linksError}
                </div>
              ) : links ? (
                // key: adres degisince oynatici yeniden kurulmali. crossOrigin
                // ŞART: MinIO CORS başlığı doğru dönse bile bu olmadan
                // tarayıcı <track> isteğini "no-cors" atıp yanıtı opak sayar,
                // altyazı sessizce hiç yüklenmez (bkz. ClipsPage.tsx'teki
                // aynı düzeltme).
                <video
                  key={links.stream}
                  ref={playerVideoRef}
                  src={links.stream}
                  crossOrigin="anonymous"
                  controls
                  autoPlay
                  className="size-full"
                >
                  {links.subtitles.map((t) => (
                    <track
                      key={t.lang}
                      kind="subtitles"
                      srcLang={t.lang}
                      label={subtitleLangs().find((l) => l.kod === t.lang)?.ad ?? t.lang}
                      src={t.url}
                    />
                  ))}
                </video>
              ) : (
                <div className="grid size-full place-items-center">
                  <Loader2Icon className="size-6 animate-spin text-muted-foreground" />
                </div>
              )}
            </div>

            <h2 className="mt-3 text-xl font-semibold tracking-tight">{playingCurrent.title}</h2>

            {/* YouTube tarzi: basligin altinda "X görüntülenme · tarih" —
                ayrinti (süre, çözünürlük, boyut) ikinci satirda, soluk. */}
            <div className="mt-0.5 text-sm text-muted-foreground">
              {formatViews(playingCurrent.viewCount)}
              {playingCurrent.createdAt && (
                <span> · {new Date(playingCurrent.createdAt).toLocaleDateString('tr')}</span>
              )}
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>{formatDuration(playingCurrent.durationSeconds)}</span>
              {playingCurrent.width ? (
                <span>· {playingCurrent.width}x{playingCurrent.height}</span>
              ) : null}
              <span>· {formatBytes(playingCurrent.sizeBytes)}</span>
              {playingCurrent.uploadedBy ? <span>· {playingCurrent.uploadedBy}</span> : null}
              <StatusBadge video={playingCurrent} />
            </div>

            <div className="mt-3 flex items-center gap-2">
              {links && (
                <Button variant="outline" asChild>
                  <a href={links.download} download={links.fileName}>
                    <DownloadIcon />
                    İndir
                  </a>
                </Button>
              )}
              {yazabilir &&
                (hasRole('Yönetici') || playingCurrent.uploadedBy === session?.username) && (
                  <>
                    <Button
                      variant="outline"
                      onClick={() => setEditing(playingCurrent)}
                      disabled={playingCurrent.status === 'YUKLENIYOR'}
                    >
                      <PencilIcon />
                      Düzenle
                    </Button>
                    <Button
                      variant="outline"
                      onClick={() => void remove(playingCurrent)}
                      disabled={
                        pending.has(playingCurrent.id) || playingCurrent.status === 'ISLENIYOR'
                      }
                    >
                      {pending.has(playingCurrent.id) ? (
                        <Loader2Icon className="animate-spin" />
                      ) : (
                        <Trash2Icon />
                      )}
                      Sil
                    </Button>
                  </>
                )}
              <Button variant="ghost" onClick={() => setPlaying(null)}>
                Listeye dön
              </Button>
            </div>

            {playingCurrent.description && (
              <p className="mt-3 max-h-40 overflow-y-auto whitespace-pre-wrap rounded-lg border bg-card p-3 text-sm text-muted-foreground">
                {playingCurrent.description}
              </p>
            )}

            {playingCurrent.status === 'HATA' && playingCurrent.error && (
              <p className="mt-2 text-sm text-status-error">{playingCurrent.error}</p>
            )}
          </div>

          {/* Sağdaki oynatma listesi — tarih sırasına göre (backend zaten
              createdAt desc döndürüyor). Tıklayınca oynatıcı değişir. */}
          <aside className="flex w-full shrink-0 flex-col gap-2 lg:w-80">
            <h3 className="text-sm font-medium text-muted-foreground">Oynatma listesi</h3>
            <div className="flex flex-col gap-2">
              {videos.map((video) => (
                <ListItem
                  key={video.id}
                  video={video}
                  active={video.id === playingCurrent.id}
                  onPlay={() => setPlaying(video)}
                />
              ))}
            </div>
          </aside>
        </div>
      ) : (
        // Boş durum: video seçilmemiş — ızgara.
        <div
          data-tour="videolar-izgara"
          className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
        >
          {videos.map((video) => (
            <VideoCard
              key={video.id}
              video={video}
              busy={pending.has(video.id)}
              onPlay={() => setPlaying(video)}
              yazabilir={
                yazabilir && (hasRole('Yönetici') || video.uploadedBy === session?.username)
              }
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
      <VideoEditDialog
        video={editing}
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        onSaved={() => void load(search)}
      />

      <GuidedTour open={tur.open} onClose={tur.close} steps={VIDEOS_TOUR_STEPS} />
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
  onPlay,
  yazabilir,
  onEdit,
  onDelete,
}: {
  video: VideoDto
  busy: boolean
  onPlay: () => void
  /** Yükleme/düzenleme/silme yetkisi var mı. */
  yazabilir: boolean
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

        {/* Oynatmadan önce altyazı olup olmadığını görmek için — izgarada
            onlarca video arasında hangisinde altyazı hazır oldugunu ayirt
            etmenin tek yolu bu, oynaticiya girmeden bilinmiyordu. */}
        {video.subtitleLangs.length > 0 && (
          <span
            data-tour="videolar-cc"
            className="absolute bottom-1.5 left-1.5 rounded bg-black/75 px-1.5 py-0.5 text-xs font-medium text-white"
            title={`Altyazı: ${video.subtitleLangs.join(', ')}`}
          >
            CC
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
          {formatViews(video.viewCount)}
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

        {/* Düzenle/sil izleyiciye kapalı. Yetkili rollerde liste zaten yalnızca
            kullanıcının kendi videolarını içeriyor, sunucu da sahiplik dışına
            çıkılmasına izin vermiyor. Yönetici başkasının videosunu görürse onu
            da yönetebilir. */}
        {yazabilir && (
        <div
          data-tour="videolar-eylemler"
          className="mt-2 flex gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100"
        >
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

/**
 * Sağdaki oynatma listesindeki tek satır — YouTube'un "Up next" listesi gibi.
 * Küçük resim sol, başlık ve meta sağda; oynatılan video vurgulanır.
 */
function ListItem({
  video,
  active,
  onPlay,
}: {
  video: VideoDto
  active: boolean
  onPlay: () => void
}) {
  const playable = video.status === 'HAZIR'
  return (
    <button
      type="button"
      onClick={onPlay}
      disabled={!playable}
      className={cn(
        'flex gap-2 rounded-lg p-1.5 text-left transition-colors',
        active ? 'bg-accent' : 'hover:bg-accent/50',
        !playable && 'cursor-default opacity-70',
      )}
    >
      <div className="relative aspect-video w-28 shrink-0 overflow-hidden rounded-md bg-black">
        {video.thumbnailUrl ? (
          <img src={video.thumbnailUrl} alt="" className="size-full object-cover" />
        ) : (
          <div className="grid size-full place-items-center">
            <FilmIcon className="size-4 text-muted-foreground" />
          </div>
        )}
        {video.durationSeconds != null && (
          <span className="absolute bottom-0.5 right-0.5 rounded bg-black/75 px-1 text-[10px] font-medium text-white">
            {formatDuration(video.durationSeconds)}
          </span>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="line-clamp-2 text-xs font-medium" title={video.title}>
          {video.title}
        </div>
        <div className="mt-0.5 truncate text-[11px] text-muted-foreground">
          {video.uploadedBy ?? '—'}
        </div>
        <div className="text-[11px] text-muted-foreground">
          {formatViews(video.viewCount)}
        </div>
      </div>
    </button>
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