import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { clipsApi } from '@/api/endpoints'
import type { ClipDto, ClipOrigin, ClipStatus, SubtitleTrackDto } from '@/api/types'
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
  Loader2Icon,
  PlayIcon,
  ScissorsIcon,
  Trash2Icon,
} from 'lucide-react'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { CLIPS_TOUR_SEEN_KEY, CLIPS_TOUR_STEPS } from '@/components/tour/clipsSteps'

/** İş devam ederken sık, bittiğinde seyrek tazeleme. */
const POLL_ACTIVE_MS = 3000
const POLL_IDLE_MS = 30000

/** VideoCard'daki (VideosPage.tsx) aynı gerekçe: ızgarada gezinmek onlarca önizlemeyi birden başlatmasın. */
const PREVIEW_DELAY_MS = 400

/** Klip durumları palet renkleriyle: bekleyen sarı, işlenen mavi, biten yeşil. */
function statusBadge(status: ClipStatus) {
  switch (status) {
    case 'HAZIR':
      return <Badge variant="success">Hazır</Badge>
    case 'ISLENIYOR':
      return (
        <Badge variant="default" className="gap-1">
          <Loader2Icon className="size-3 animate-spin" />
          İşleniyor
        </Badge>
      )
    case 'BEKLIYOR':
      return <Badge variant="warning">Kuyrukta</Badge>
    case 'HATA':
      return <Badge variant="error">Hata</Badge>
  }
}

function formatDuration(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return h > 0 ? `${h}sa ${m}dk` : m > 0 ? `${m}dk ${s}sn` : `${s}sn`
}

export function ClipsPage() {
  const [clips, setClips] = useState<ClipDto[]>([])
  const [origin, setOrigin] = useState<ClipOrigin | undefined>(undefined)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [watching, setWatching] = useState<{
    clip: ClipDto
    url: string | null
    subtitles: SubtitleTrackDto[]
  } | null>(null)
  const watchVideoRef = useRef<HTMLVideoElement>(null)
  /** 'kapali' ya da watching.subtitles içindeki bir srcLang. */
  const [aktifAltyazi, setAktifAltyazi] = useState<string>('kapali')

  /**
   * Tarayıcının kendi "CC" menüsü çoğu kullanıcı için gizli/keşfedilmesi
   * zor kaldığı için (gerçek geri bildirim) burada açık bir seçici
   * sunuluyor. `<track>`'lar zaten DOM'da — bu yalnızca hangisinin
   * {@code mode}'unu "showing" yaptığını değiştiriyor, ek bir istek atmıyor.
   */
  useEffect(() => {
    const video = watchVideoRef.current
    if (!video) return
    for (const t of Array.from(video.textTracks)) {
      t.mode = t.language === aktifAltyazi ? 'showing' : 'hidden'
    }
  }, [aktifAltyazi, watching?.url])

  const load = useCallback(async () => {
    try {
      setClips(await clipsApi.list(undefined, origin))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Klipler yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [origin])

  // Devam eden iş varsa hızlı tazele; yoksa boşuna istek atma.
  useEffect(() => {
    void load()
    const active = clips.some((c) => c.status === 'BEKLIYOR' || c.status === 'ISLENIYOR')
    const timer = setInterval(() => void load(), active ? POLL_ACTIVE_MS : POLL_IDLE_MS)
    return () => clearInterval(timer)
    // clips.length ve durum bileşimi değişince aralık yeniden hesaplanmalı.
  }, [load, clips.map((c) => c.status).join(',')])

  /**
   * İndirme. Adres backend'den JSON olarak alınıyor: uç token gerektiriyor,
   * <a href> ile doğrudan açılamaz çünkü tarayıcı Authorization başlığı
   * göndermez. Yönlendirme de işe yaramıyor — CORS Location başlığını gizliyor.
   */
  async function download(clip: ClipDto) {
    try {
      const { download: url } = await clipsApi.links(clip.id)
      // Gizli bir bağlantıya tıklamak, window.location'dan farklı olarak
      // sayfadan ayrılmadan indirmeyi başlatır.
      const a = document.createElement('a')
      a.href = url
      a.rel = 'noopener'
      document.body.appendChild(a)
      a.click()
      a.remove()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'İndirme adresi alınamadı.')
    }
  }

  async function watch(clip: ClipDto) {
    setWatching({ clip, url: null, subtitles: [] })
    setAktifAltyazi('kapali')
    try {
      const { stream, subtitles } = await clipsApi.links(clip.id)
      setWatching({ clip, url: stream, subtitles })
      // <track default> ile aynı fikir: ilk dil varsayılan gösterilsin.
      setAktifAltyazi(subtitles[0]?.lang ?? 'kapali')
    } catch (e) {
      setWatching(null)
      toast.error(e instanceof ApiError ? e.message : 'İzleme adresi alınamadı.')
    }
  }

  function subtitleLabel(lang: string): string {
    return subtitleLangs().find((l) => l.kod === lang)?.ad ?? lang
  }

  async function remove(clip: ClipDto) {
    if (!confirm(`"${clip.channelName}" klibi silinecek. Emin misiniz?`)) return
    try {
      await clipsApi.remove(clip.id)
      setClips((prev) => prev.filter((c) => c.id !== clip.id))
      toast.success('Klip silindi.')
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Klip silinemedi.')
    }
  }

  const tur = usePageTour(CLIPS_TOUR_SEEN_KEY)

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex items-start gap-2">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight">Klipler ve kayıtlar</h1>
            <p className="text-sm text-muted-foreground">
              Arka planda üretilir; hazır olunca burada izlenip indirilebilir.
            </p>
          </div>
          <TourTrigger onClick={tur.start} />
        </div>

        {/* İkisi de aynı tabloda: ürün ve yaşam döngüsü aynı, yalnızca nasıl
            istendikleri farklı. İzlerken anlamsız — oynatma görünümünde gizli. */}
        {!watching && (
          <div data-tour="klip-filtre" className="flex gap-1 rounded-full border p-1">
            {([
              [undefined, 'Tümü'],
              ['ARALIK', 'Aralık seçimi'],
              ['MANUEL_KAYIT', 'Kayıtlarım'],
            ] as [ClipOrigin | undefined, string][]).map(([value, label]) => (
              <Button
                key={label}
                size="sm"
                className="rounded-full"
                variant={origin === value ? 'secondary' : 'ghost'}
                onClick={() => setOrigin(value)}
              >
                {label}
              </Button>
            ))}
          </div>
        )}
      </div>

      {watching ? (
        // YouTube tarzı: solda oynatıcı, sağda kanal kanal ayrılmış klip
        // listesi — VideosPage.tsx'teki aynı yerleşim.
        <div className="flex flex-col gap-6 lg:flex-row">
          <div className="min-w-0 flex-1">
            <div className="relative aspect-video overflow-hidden rounded-xl bg-black shadow-[0_0_0_1px_var(--border),0_20px_60px_-20px_rgba(0,0,0,0.6)]">
              {watching.url && (
                <SubtitlePicker
                  tracks={watching.subtitles.map((t) => ({ lang: t.lang, label: subtitleLabel(t.lang) }))}
                  value={aktifAltyazi}
                  onChange={setAktifAltyazi}
                  className="absolute right-2 top-2 z-10"
                />
              )}
              {watching.url ? (
                // key: adres degisince <video> yeniden kurulsun, onceki klip
                // acik kalmasin. crossOrigin ŞART: MinIO'nun altyazı dosyası
                // için doğru CORS başlığı dönmesi YETMEZ -- <video>'da bu
                // özellik yoksa tarayıcı track isteğini "no-cors" modunda atar
                // ve yanıtı opak sayıp cue'ları hiç ayrıştırmaz.
                <video
                  key={watching.url}
                  ref={watchVideoRef}
                  src={watching.url}
                  poster={watching.clip.thumbnailUrl ?? undefined}
                  crossOrigin="anonymous"
                  controls
                  autoPlay
                  className="size-full"
                >
                  {watching.subtitles.map((t) => (
                    <track key={t.lang} kind="subtitles" srcLang={t.lang} label={subtitleLabel(t.lang)} src={t.url} />
                  ))}
                </video>
              ) : (
                <div className="grid size-full place-items-center">
                  <Loader2Icon className="size-6 animate-spin text-muted-foreground" />
                </div>
              )}
            </div>

            <h2 className="mt-4 text-2xl font-bold tracking-tight text-balance">
              {watching.clip.channelName}
            </h2>
            <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-sm text-muted-foreground">
              <span className="inline-flex items-center gap-1.5">
                <CalendarIcon className="size-3.5" />
                {new Date(watching.clip.start).toLocaleString('tr-TR')}
              </span>
              <span className="inline-flex items-center gap-1.5 tabular-nums">
                <ClockIcon className="size-3.5" />
                {formatDuration(watching.clip.durationSeconds)}
              </span>
              {statusBadge(watching.clip.status)}
            </div>

            <div className="mt-4 flex items-center gap-2">
              {watching.url && (
                <Button variant="outline" onClick={() => void download(watching.clip)}>
                  <DownloadIcon />
                  İndir
                </Button>
              )}
              <Button variant="ghost" onClick={() => setWatching(null)}>
                Listeye dön
              </Button>
            </div>
          </div>

          {/* Sağdaki liste de kanal kanal ayrılıyor — ızgaradakiyle aynı
              gruplama, yalnızca kompakt satırlar halinde. */}
          <aside className="flex w-full shrink-0 flex-col gap-4 lg:w-80">
            {kanalaGoreGrupla(clips).map((grup) => (
              <div key={grup.anahtar} className="flex flex-col gap-2">
                <h3 className="text-sm font-medium text-muted-foreground">{grup.ad}</h3>
                <div className="flex flex-col gap-1">
                  {grup.klipler.map((clip) => (
                    <ClipListItem
                      key={clip.id}
                      clip={clip}
                      active={clip.id === watching.clip.id}
                      onPlay={() => void watch(clip)}
                    />
                  ))}
                </div>
              </div>
            ))}
          </aside>
        </div>
      ) : error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : loading ? (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" />
          Yükleniyor…
        </div>
      ) : clips.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-xl border border-dashed p-12 text-center">
          <ScissorsIcon className="size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            Henüz klip yok. Geriye sarma sayfasından aralık seçip oluşturun.
          </p>
        </div>
      ) : (
        // Klipler kanal kanal ayrılıyor: aynı kanalın klipleri listenin her
        // yerine dağılmış olsaydı "bu kanalda ne var" ancak göz taramasıyla
        // cevaplanırdı. Kanalı silinmiş kliplerin grubu en sona düşer.
        <div data-tour="klip-tablo" className="flex flex-col gap-8">
          {kanalaGoreGrupla(clips).map((grup) => (
            <section key={grup.anahtar} className="flex flex-col gap-3">
              <div className="flex flex-wrap items-baseline gap-2">
                <h2 className="text-lg font-semibold tracking-tight">{grup.ad}</h2>
                {grup.silinmis && (
                  <Badge variant="outline" className="text-[11px]">silinmiş kanal</Badge>
                )}
                <span className="text-sm text-muted-foreground">
                  {grup.klipler.length} klip
                  {grup.toplamBayt > 0 && ` · ${boyut(grup.toplamBayt)}`}
                </span>
              </div>

              <div className="grid gap-x-4 gap-y-6 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 2xl:grid-cols-6">
                {grup.klipler.map((clip) => (
                  <ClipCard
                    key={clip.id}
                    clip={clip}
                    onWatch={() => void watch(clip)}
                    onDownload={() => void download(clip)}
                    onDelete={() => void remove(clip)}
                  />
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      <GuidedTour open={tur.open} onClose={tur.close} steps={CLIPS_TOUR_STEPS} />
    </div>
  )
}

/**
 * Video kütüphanesindeki kartla aynı dil (bkz. VideosPage.tsx) — fare
 * kartın üzerine geldiğinde {@code clip.previewUrl} (ClipWorker'ın ürettiği
 * kısa, sessiz klip) oynuyor; henüz üretilmediyse ikon yer tutucu kalıyor.
 */
function ClipCard({
  clip,
  onWatch,
  onDownload,
  onDelete,
}: {
  clip: ClipDto
  onWatch: () => void
  onDownload: () => void
  onDelete: () => void
}) {
  const playable = clip.status === 'HAZIR'
  const [previewOn, setPreviewOn] = useState(false)
  const timerRef = useRef<number | null>(null)
  const videoRef = useRef<HTMLVideoElement>(null)

  function startPreview() {
    if (!playable || !clip.previewUrl) return
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
      el.currentTime = 0
    }
  }

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current)
  }, [])

  return (
    <div className="group flex flex-col" onMouseEnter={startPreview} onMouseLeave={stopPreview}>
      <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
        {clip.thumbnailUrl ? (
          <img src={clip.thumbnailUrl} alt="" className="size-full object-cover" />
        ) : (
          <div className="grid size-full place-items-center bg-gradient-to-br from-panel to-black">
            <ScissorsIcon className="size-8 text-muted-foreground/40" />
          </div>
        )}

        {/* src yalnızca önizleme AÇIKKEN veriliyor — VideoCard'daki aynı
            gerekçe: ızgarada onlarca kart aynı anda önceden çekmesin. */}
        {previewOn && clip.previewUrl && (
          <video
            ref={videoRef}
            src={clip.previewUrl}
            poster={clip.thumbnailUrl ?? undefined}
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
            onClick={onWatch}
            title="İzle"
            className="absolute inset-0 grid place-items-center bg-black/40 opacity-0 transition-opacity group-hover:opacity-100"
          >
            <span className="grid size-11 place-items-center rounded-full bg-primary text-primary-foreground shadow-lg">
              <PlayIcon className="size-5 fill-current" />
            </span>
          </button>
        )}

        <span className="absolute bottom-1.5 right-1.5 rounded bg-black/75 px-1.5 py-0.5 text-xs font-medium tabular-nums text-white">
          {formatDuration(clip.durationSeconds)}
        </span>

        {/* Klip listesinde onlarca kart arasinda hangisinde altyazi hazir
            oldugunu izlemeye girmeden gormek icin. */}
        {clip.subtitleLangs.length > 0 && (
          <span
            className="absolute bottom-1.5 left-1.5 flex items-center gap-1 rounded bg-black/75 px-1.5 py-0.5 text-xs font-medium text-primary-light"
            title={`Altyazı: ${clip.subtitleLangs.join(', ')}`}
          >
            <CaptionsIcon className="size-3.5" />
          </span>
        )}

        <div data-tour="klip-durum" className="absolute left-1.5 top-1.5">
          {statusBadge(clip.status)}
        </div>

        <div
          data-tour="klip-islem"
          className="absolute right-1.5 top-1.5 flex gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100"
        >
          <button
            type="button"
            title={playable ? 'İndir' : 'Klip henüz hazır değil'}
            disabled={!playable}
            onClick={onDownload}
            className="grid size-7 place-items-center rounded-full bg-black/70 text-white transition-colors hover:bg-black/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <DownloadIcon className="size-3.5" />
          </button>
          <button
            type="button"
            title={clip.status === 'ISLENIYOR' ? 'İşlenmekte olan klip silinemez' : 'Sil'}
            disabled={clip.status === 'ISLENIYOR'}
            onClick={onDelete}
            className="grid size-7 place-items-center rounded-full bg-black/70 text-white transition-colors hover:bg-black/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <Trash2Icon className="size-3.5" />
          </button>
        </div>
      </div>

      <button
        type="button"
        onClick={onWatch}
        disabled={!playable}
        className="mt-2 flex flex-col items-start text-left disabled:cursor-not-allowed"
      >
        <span className="line-clamp-2 text-sm font-medium leading-snug">
          {new Date(clip.start).toLocaleString('tr-TR')}
        </span>
        <span data-tour="klip-nasil" className="mt-1 truncate text-xs text-muted-foreground">
          {clip.origin === 'MANUEL_KAYIT' ? 'Kayıt' : 'Aralık'} · {clip.requestedBy}
        </span>
        {clip.error && (
          <span className="mt-1 line-clamp-1 text-xs text-status-error" title={clip.error}>
            {clip.error}
          </span>
        )}
      </button>
    </div>
  )
}

/**
 * Oynatma görünümündeki sağ listede tek satır — VideosPage.tsx'teki
 * {@code ListItem} ile aynı fikir, gerçek küçük resim yerine ikon.
 */
function ClipListItem({
  clip,
  active,
  onPlay,
}: {
  clip: ClipDto
  active: boolean
  onPlay: () => void
}) {
  const playable = clip.status === 'HAZIR'
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
      <div className="relative aspect-video w-24 shrink-0 overflow-hidden rounded-md bg-black">
        {clip.thumbnailUrl ? (
          <img src={clip.thumbnailUrl} alt="" className="size-full object-cover" />
        ) : (
          <div className="grid size-full place-items-center">
            <ScissorsIcon className="size-3.5 text-muted-foreground/40" />
          </div>
        )}
        <span className="absolute bottom-0.5 right-0.5 rounded bg-black/75 px-1 text-[10px] font-medium tabular-nums text-white">
          {formatDuration(clip.durationSeconds)}
        </span>
      </div>
      <div className="min-w-0 flex-1">
        <div className={cn('line-clamp-2 text-xs font-medium', active && 'text-primary')}>
          {new Date(clip.start).toLocaleString('tr-TR')}
        </div>
        <div className="mt-0.5 truncate text-[11px] text-muted-foreground">
          {clip.origin === 'MANUEL_KAYIT' ? 'Kayıt' : 'Aralık'} · {clip.requestedBy}
        </div>
      </div>
    </button>
  )
}

/**
 * Klipleri kanala göre gruplar.
 *
 * <p>Sıralama <b>kanal adına göre</b>, grup içinde <b>en yeni önce</b>.
 * Düz listede aynı kanalın klipleri araya karışıyordu ve "bu kanalda ne
 * var" sorusu ancak göz taramasıyla cevaplanıyordu.
 *
 * <p>Kanalı silinmiş klipler {@code channelId} null taşıyor (V21) ve
 * <b>en sona</b> konuyor: onlar arşiv, aktif iş değil.
 */
function kanalaGoreGrupla(clips: ClipDto[]) {
  const harita = new Map<string, ClipDto[]>()
  for (const c of clips) {
    // Anahtar kimlikten: iki kanalın adı aynı olabilir ve birleşmeleri
    // yanlış olurdu. Kanal silinmişse ada düşülüyor.
    const anahtar = c.channelId ?? `silinmis:${c.channelName}`
    const mevcut = harita.get(anahtar)
    if (mevcut) mevcut.push(c)
    else harita.set(anahtar, [c])
  }

  return [...harita.entries()]
    .map(([anahtar, klipler]) => ({
      anahtar,
      ad: klipler[0].channelName ?? 'Bilinmeyen kanal',
      silinmis: klipler[0].channelId === null,
      klipler: [...klipler].sort(
        (a, b) => Date.parse(b.start) - Date.parse(a.start),
      ),
      toplamBayt: klipler.reduce((t, c) => t + (c.sizeBytes ?? 0), 0),
    }))
    .sort((a, b) => {
      if (a.silinmis !== b.silinmis) return a.silinmis ? 1 : -1
      return a.ad.localeCompare(b.ad, 'tr')
    })
}

function boyut(bayt: number): string {
  if (bayt >= 1024 ** 3) return `${(bayt / 1024 ** 3).toFixed(1)} GB`
  if (bayt >= 1024 ** 2) return `${Math.round(bayt / 1024 ** 2)} MB`
  return `${Math.round(bayt / 1024)} KB`
}
