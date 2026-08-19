import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthContext'
import { videosApi } from '@/api/endpoints'
import { formatBytes, formatDuration } from '@/api/upload'
import type { VideoDto, VideoLinks } from '@/api/types'
import { subtitleLangs } from '@/player/SubtitleOverlay'
import { SubtitlePicker } from '@/player/SubtitlePicker'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  CalendarIcon,
  CaptionsIcon,
  ClockIcon,
  DownloadIcon,
  EyeIcon,
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

  /** Sekmeler gerçek video durumlarına eşleniyor — "Taslak"/"Arşiv" gibi
   * karşılığı olmayan bir kavram uydurmak yerine. */
  const [durumSekme, setDurumSekme] = useState<'HEPSI' | 'HAZIR' | 'ISLENIYOR' | 'HATA'>('HEPSI')

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

  const filtered = videos.filter((v) => {
    if (durumSekme === 'HEPSI') return true
    if (durumSekme === 'ISLENIYOR') return v.status === 'YUKLENIYOR' || v.status === 'ISLENIYOR'
    return v.status === durumSekme
  })

  function duzenlenebilir(video: VideoDto) {
    return yazabilir && (hasRole('Yönetici') || video.uploadedBy === session?.username)
  }

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
      <div data-tour="videolar-arama" className="relative max-w-xl">
        <SearchIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <input
          type="search"
          placeholder="Kütüphanede ara…"
          aria-label="Videolarda ara"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="h-11 w-full rounded-full border bg-card pl-11 pr-4 text-sm
                     placeholder:text-muted-foreground focus:outline-none
                     focus:ring-2 focus:ring-[var(--ring)]"
        />
      </div>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-3xl font-semibold tracking-tight">Video kütüphanesi</h1>
            <Badge variant="secondary">{videos.length} video</Badge>
            <TourTrigger onClick={tur.start} />
          </div>
          <p className="text-sm text-muted-foreground">Yüklediğiniz videoları yönetin.</p>
        </div>

        {/* İzleyici yükleyemez. Sunucu da aynı kuralı uyguluyor; buradaki
            gizleme kullanıcıyı reddedilecek bir düğmeyle karşılaştırmamak
            için. */}
        {yazabilir && (
          <Button data-tour="videolar-yukle" className="rounded-full" onClick={() => setUploadOpen(true)}>
            <UploadIcon />
            Video yükle
          </Button>
        )}
      </div>

      {/* Sekmeler gerçek durumlara eşleniyor — oynatıcı açıkken anlamsız,
          gizleniyor. */}
      {!playingCurrent && (
        <div className="flex items-center gap-5 border-b text-sm">
          {(
            [
              ['HEPSI', 'Tümü'],
              ['HAZIR', 'Hazır'],
              ['ISLENIYOR', 'İşleniyor'],
              ['HATA', 'Hata'],
            ] as const
          ).map(([deger, etiket]) => (
            <button
              key={deger}
              type="button"
              onClick={() => setDurumSekme(deger)}
              className={cn(
                'relative pb-3 font-medium transition-colors',
                durumSekme === deger
                  ? 'text-foreground after:absolute after:inset-x-0 after:-bottom-px after:h-0.5 after:rounded-full after:bg-primary'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {etiket}
            </button>
          ))}
        </div>
      )}

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
      ) : !playingCurrent && filtered.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-xl border border-dashed p-12 text-center">
          <FilmIcon className="size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">Bu durumda video yok.</p>
        </div>
      ) : playingCurrent ? (
        // YouTube tarzı: solda oynatıcı + başlık/açıklama, sağda liste.
        <div className="flex flex-col gap-6 lg:flex-row">
          <div className="min-w-0 flex-1">
            <div className="relative aspect-video overflow-hidden rounded-xl bg-black shadow-[0_0_0_1px_var(--border),0_20px_60px_-20px_rgba(0,0,0,0.6)]">
              {/* Videonun kendi üzerinde, sağ üstte -- tarayıcının native
                  "CC" menüsü gizli/keşfedilmesi zor kaldığı için (gerçek
                  geri bildirim). Denetim çubuğu ALTTA olduğu için çakışma
                  yok. */}
              {links && (
                <SubtitlePicker
                  tracks={links.subtitles.map((t) => ({
                    lang: t.lang,
                    label: subtitleLangs().find((l) => l.kod === t.lang)?.ad ?? t.lang,
                  }))}
                  value={aktifAltyazi}
                  onChange={setAktifAltyazi}
                  className="absolute right-2 top-2 z-10"
                />
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

            <h2 className="mt-4 text-2xl font-bold tracking-tight text-balance">
              {playingCurrent.title}
            </h2>

            {/* YouTube tarzi: basligin altinda ikon+deger cipleri -- "·"
                ile ayrilmis duz metinden daha taranabilir, ikonlar hangi
                sayinin ne oldugunu bir bakista soyluyor. */}
            <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-sm text-muted-foreground">
              <span className="inline-flex items-center gap-1.5">
                <EyeIcon className="size-3.5" />
                {formatViews(playingCurrent.viewCount)}
              </span>
              {playingCurrent.createdAt && (
                <span className="inline-flex items-center gap-1.5">
                  <CalendarIcon className="size-3.5" />
                  {new Date(playingCurrent.createdAt).toLocaleDateString('tr')}
                </span>
              )}
              <span className="inline-flex items-center gap-1.5 tabular-nums">
                <ClockIcon className="size-3.5" />
                {formatDuration(playingCurrent.durationSeconds)}
              </span>
              <StatusBadge video={playingCurrent} />
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              {playingCurrent.width ? (
                <span>{playingCurrent.width}×{playingCurrent.height}</span>
              ) : null}
              <span>{formatBytes(playingCurrent.sizeBytes)}</span>
              {playingCurrent.uploadedBy ? <span>{playingCurrent.uploadedBy}</span> : null}
            </div>

            <div className="mt-4 flex items-center gap-2">
              {links && (
                <Button variant="outline" asChild>
                  <a href={links.download} download={links.fileName}>
                    <DownloadIcon />
                    İndir
                  </a>
                </Button>
              )}
              {duzenlenebilir(playingCurrent) && (
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
              <p className="mt-4 max-h-40 overflow-y-auto whitespace-pre-wrap rounded-xl border bg-panel p-4 text-sm text-muted-foreground">
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
          className="grid gap-x-4 gap-y-6 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6"
        >
          {filtered.map((video) => (
            <VideoCard
              key={video.id}
              video={video}
              busy={pending.has(video.id)}
              onPlay={() => setPlaying(video)}
              yazabilir={duzenlenebilir(video)}
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
    <div className="group flex flex-col" onMouseEnter={startPreview} onMouseLeave={stopPreview}>
      <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
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
            <span className="grid size-11 place-items-center rounded-full bg-primary text-primary-foreground shadow-lg">
              <PlayIcon className="size-5 fill-current" />
            </span>
          </button>
        )}

        {video.durationSeconds != null && (
          <span className="absolute bottom-1.5 right-1.5 rounded bg-black/75 px-1.5 py-0.5 text-xs font-medium tabular-nums text-white">
            {formatDuration(video.durationSeconds)}
          </span>
        )}

        {/* Oynatmadan önce altyazı olup olmadığını görmek için — izgarada
            onlarca video arasında hangisinde altyazı hazır oldugunu ayirt
            etmenin tek yolu bu, oynaticiya girmeden bilinmiyordu. İkon +
            vurgu rengi: düz "CC" metninden daha tanınır, palettin tek
            doygun rengini (primary-light) kullanan tek rozet burası. */}
        {video.subtitleLangs.length > 0 && (
          <span
            data-tour="videolar-cc"
            className="absolute bottom-1.5 left-1.5 flex items-center gap-1 rounded bg-black/75 px-1.5 py-0.5 text-xs font-medium text-primary-light"
            title={`Altyazı: ${video.subtitleLangs.join(', ')}`}
          >
            <CaptionsIcon className="size-3.5" />
          </span>
        )}

        <div className="absolute left-1.5 top-1.5">
          <StatusBadge video={video} />
        </div>

        {/* Düzenle/sil izleyiciye kapalı. Yetkili rollerde liste zaten yalnızca
            kullanıcının kendi videolarını içeriyor, sunucu da sahiplik dışına
            çıkılmasına izin vermiyor. Yönetici başkasının videosunu görürse onu
            da yönetebilir. YouTube'daki "⋮" menüsü gibi küçük, hover'da beliren
            bir grup — metin bloğunu şişirmesin diye küçük resmin üzerinde. */}
        {yazabilir && (
          <div
            data-tour="videolar-eylemler"
            className="absolute right-1.5 top-1.5 flex gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100"
          >
            <button
              type="button"
              title="Düzenle"
              disabled={video.status === 'YUKLENIYOR'}
              onClick={onEdit}
              className="grid size-7 place-items-center rounded-full bg-black/70 text-white transition-colors hover:bg-black/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <PencilIcon className="size-3.5" />
            </button>
            <button
              type="button"
              title={video.status === 'ISLENIYOR' ? 'İşlenirken silinemez' : 'Sil'}
              disabled={busy || video.status === 'ISLENIYOR'}
              onClick={onDelete}
              className="grid size-7 place-items-center rounded-full bg-black/70 text-white transition-colors hover:bg-black/90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {busy ? <Loader2Icon className="size-3.5 animate-spin" /> : <Trash2Icon className="size-3.5" />}
            </button>
          </div>
        )}
      </div>

      <button
        type="button"
        onClick={onPlay}
        disabled={!playable}
        className="mt-2 flex flex-col items-start text-left disabled:cursor-not-allowed"
      >
        <span className="line-clamp-2 text-sm font-medium leading-snug" title={video.title}>
          {video.title}
        </span>
        <span className="mt-1 truncate text-xs text-muted-foreground">
          {video.uploadedBy && `${video.uploadedBy} · `}
          {formatViews(video.viewCount)}
        </span>
        {video.status === 'HATA' && video.error && (
          <span className="mt-1 line-clamp-1 text-xs text-status-error" title={video.error}>
            {video.error}
          </span>
        )}
      </button>
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
        'flex gap-2.5 rounded-lg border border-transparent p-1.5 text-left transition-colors',
        active ? 'border-primary/40 bg-primary/10' : 'hover:bg-accent/50',
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
          <span className="absolute bottom-0.5 right-0.5 rounded bg-black/75 px-1 text-[10px] font-medium tabular-nums text-white">
            {formatDuration(video.durationSeconds)}
          </span>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div
          className={cn('line-clamp-2 text-xs font-medium', active && 'text-primary')}
          title={video.title}
        >
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