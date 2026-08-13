import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { dvrApi } from '@/api/endpoints'
import { readTokens } from '@/api/tokens'
import type { ChannelDto, TimelineSpan } from '@/api/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { Loader2Icon, RadioIcon, RotateCcwIcon } from 'lucide-react'

/** Hızlı geri sarma adımları. Çubuktan bağımsız — "az önce ne oldu"yu tek
 *  tıkla karşılıyor; çubuk daha uzak/hassas bir ana gitmek için duruyor. */
const STEPS = [
  { label: '30 sn', seconds: 30 },
  { label: '3 dk', seconds: 180 },
  { label: '5 dk', seconds: 300 },
] as const

/**
 * DVR penceresi: şu andan geriye kaç saatlik kayıt çubukta gösterilecek.
 *
 * <p>İki saat, "az önce kaçan şeyi izleyeyim" ihtiyacını karşılıyor. Daha
 * gerisi Geriye sarma sayfasında — orada çizelge tam ve klip çıkarılabiliyor.
 * Yedi saatlik çizelgeyi çubuğa sığdırmak saatleri piksellere mapler ve
 * dakika isabet ettirmeyi imkânsızlaştırırdı.
 */
const DVR_WINDOW_HOURS = 2

/** Tek seferde DVR'dan çekilecek bölüm. Bittiğinde canlıya dönülüyor. */
const CHUNK_SECONDS = 120

/**
 * Canlı yayında geri sarma.
 *
 * <p>Canlı HLS'te gerçek geri sarma yok — playlist yalnızca son birkaç
 * segmenti taşıyor (bizde 7 × 1.96 sn ≈ 14 sn). Daha geriye gitmek DVR
 * kaydından okumayı gerektirir.
 *
 * <p><b>İki yol var:</b> sabit 30sn/3dk/5dk düğmeleri "az önce ne oldu"yu
 * tek tıkla karşılıyor. Yanındaki DVR çubuğu ise kayıtlı aralıkları gösteren
 * bir zaman çizelgesi — kullanıcı çubuğun herhangi bir yerine tıklayıp daha
 * hassas/uzak bir ana gidebilir. İkisi de aynı {@code seekTo}'ya çıkıyor:
 * tıklanan/seçilen an kayıtlıysa DVR'den bölüm çekilir ve oynatıcıya
 * verilir, değilse uyarı verilir.
 *
 * <p>Kanalda DVR kapalıysa hiç gösterilmiyor: kayıt yoksa geri sarılacak
 * bir şey de yok.
 */
export function LiveRewind({
  channel,
  onRewind,
  onLive,
  rewound,
}: {
  channel: ChannelDto
  /** Geri sarılan bölümün oynatılabilir adresi (blob). */
  onRewind: (objectUrl: string) => void
  onLive: () => void
  rewound: boolean
}) {
  const [spans, setSpans] = useState<TimelineSpan[]>([])
  const [busy, setBusy] = useState(false)
  const [hoverRatio, setHoverRatio] = useState<number | null>(null)
  const barRef = useRef<HTMLDivElement>(null)

  if (!channel.dvrEnabled) return null

  // Kayıtlı aralıkları yükle ve periyodik tazele — segmentler sürekli yazılıyor.
  useEffect(() => {
    if (!channel.dvrEnabled) return
    let cancelled = false
    const load = async () => {
      try {
        const from = new Date(Date.now() - DVR_WINDOW_HOURS * 3600 * 1000)
        const to = new Date()
        const result = await dvrApi.timeline(channel.id, from, to)
        if (!cancelled) setSpans(result)
      } catch {
        // Çizelge alınamazsa çubuk boş kalır.
      }
    }
    void load()
    const timer = setInterval(load, 60_000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [channel.id, channel.dvrEnabled])

  const now = Date.now()
  const windowStart = now - DVR_WINDOW_HOURS * 3600 * 1000
  const windowMs = now - windowStart

  function ratioToTime(ratio: number): Date {
    return new Date(windowStart + ratio * windowMs)
  }

  function isRecorded(time: Date): boolean {
    const t = time.getTime()
    return spans.some(
      (s) => new Date(s.start).getTime() <= t && t < new Date(s.end).getTime(),
    )
  }

  async function seekTo(time: Date) {
    if (!isRecorded(time)) {
      toast.error('Bu aralıkta kayıt yok.', {
        description: 'Çubukta dolu bir bölgeye tıklayın.',
      })
      return
    }
    const tokens = readTokens()
    if (!tokens) return
    setBusy(true)
    try {
      // İstenen andan biraz önce başla — paketleme gecikmesi için pay.
      const start = new Date(time.getTime() - 2000)
      const response = await fetch(dvrApi.streamUrl(channel.id, start, CHUNK_SECONDS), {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      onRewind(URL.createObjectURL(await response.blob()))
    } catch {
      toast.error('Geri sarılamadı.', {
        description: 'O aralıkta kayıt bulunmuyor olabilir.',
      })
    } finally {
      setBusy(false)
    }
  }

  function handleClick(e: React.MouseEvent) {
    if (busy || !barRef.current) return
    const rect = barRef.current.getBoundingClientRect()
    const ratio = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1)
    void seekTo(ratioToTime(ratio))
  }

  function handleHover(e: React.MouseEvent) {
    if (!barRef.current) return
    const rect = barRef.current.getBoundingClientRect()
    setHoverRatio(Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1))
  }

  const hoverTime = hoverRatio != null ? ratioToTime(hoverRatio) : null

  return (
    <div className="pointer-events-auto flex items-center gap-2">
      {rewound ? (
        <Button size="sm" variant="destructive" onClick={onLive} title="Canlı yayına dön">
          <RadioIcon />
          Canlı
        </Button>
      ) : (
        <>
          <RotateCcwIcon className="size-3.5 text-white/70" />
          {STEPS.map((s) => (
            <Button
              key={s.seconds}
              size="sm"
              variant="secondary"
              className="h-7 px-2 text-xs"
              disabled={busy}
              onClick={() => void seekTo(new Date(Date.now() - s.seconds * 1000))}
              title={`${s.label} geri sar`}
            >
              {s.label}
            </Button>
          ))}

          <span className="text-xs font-medium text-white/70">DVR</span>
          <div
            ref={barRef}
            className={cn(
              'relative h-7 w-56 cursor-pointer rounded-lg border bg-secondary/50',
              busy && 'pointer-events-none opacity-60',
            )}
            onClick={handleClick}
            onMouseMove={handleHover}
            onMouseLeave={() => setHoverRatio(null)}
          >
            {/* Kayıtlı aralıklar — dolu bölgeleri gösterir. */}
            {spans.map((span, i) => {
              const s = new Date(span.start).getTime()
              const e = new Date(span.end).getTime()
              const leftPct = ((s - windowStart) / windowMs) * 100
              const widthPct = Math.max(((e - s) / windowMs) * 100, 0.5)
              return (
                <div
                  key={i}
                  className="absolute inset-y-0.5 rounded bg-primary/50"
                  style={{ left: `${leftPct}%`, width: `${widthPct}%` }}
                />
              )
            })}

            {/* Şimdi işareti — çubuğun sağ ucu. */}
            <div className="absolute inset-y-0 right-0 w-0.5 bg-status-live" />

            {/* Hover göstergesi — fare altındaki anı ve kayıt durumunu gösterir. */}
            {hoverTime && hoverRatio != null && (
              <div
                className="absolute inset-y-0 w-0.5 bg-white/80"
                style={{ left: `${hoverRatio * 100}%` }}
              >
                <div className="absolute -top-6 left-1/2 -translate-x-1/2 whitespace-nowrap rounded bg-black/80 px-1.5 py-0.5 text-[10px] text-white">
                  {hoverTime.toLocaleTimeString('tr', {
                    hour: '2-digit',
                    minute: '2-digit',
                    second: '2-digit',
                  })}
                  {!isRecorded(hoverTime) && ' · kayıt yok'}
                </div>
              </div>
            )}

            {busy && (
              <div className="absolute inset-0 grid place-items-center rounded-lg bg-black/50">
                <Loader2Icon className="size-4 animate-spin text-white" />
              </div>
            )}
          </div>

          {/* Saat etiketleri — çubuğun başlangıcı "2 saat önce", sonu "şimdi". */}
          <span className="text-[10px] tabular-nums text-white/50">
            {new Date(windowStart).toLocaleTimeString('tr', {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </span>
        </>
      )}
    </div>
  )
}