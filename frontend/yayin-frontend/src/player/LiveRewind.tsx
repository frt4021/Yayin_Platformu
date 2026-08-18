import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
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

/**
 * Sürükleyerek seçilen aralığın belleğe (blob) indirilebilecek en uzun
 * hâli. Whole-blob indirme yaklaşımı aynı `DvrPage.tsx`'teki gibi: çok uzun
 * bir seçim tarayıcıyı düşürebilir. 30 dakika ~1,35 GB (6 Mbps) eder --
 * güvenli ve pratikte yeterli bir başlangıç, gerekirse ayarlanabilir.
 */
const MAX_RANGE_SECONDS = 30 * 60

/**
 * Çubuğa TIKLANDIĞINDA (sürükleme değil) ne kadarı getirilecek — tıklanan
 * andan ŞİMDİYE kadarki her şey, {@link MAX_RANGE_SECONDS} ile sınırlı
 * (sürüklemedeki aynı bellek/bant genişliği kaygısı). Önceden sabit 2
 * dakikaydı: o süre bitince kayıt daha fazla olsa bile otomatik canlıya
 * dönülüyordu (gerçek istek, kaldırıldı).
 */
function tiklamaSuresi(time: Date): number {
  const simdiyeKadarSn = Math.round((Date.now() - time.getTime()) / 1000)
  return Math.max(5, Math.min(simdiyeKadarSn, MAX_RANGE_SECONDS))
}

/** {@link LiveRewind}'in dışarı açtığı komut — canlı oynatıcının kendi
 *  "geri git" düğmesi, canlı HLS tamponu (~14 sn) tükenince DEVRALMASI için.
 *  {@code durationSeconds} verilmezse {@link tiklamaSuresi} kullanılır. */
export type LiveRewindHandle = {
  seekTo: (time: Date, durationSeconds?: number) => Promise<void>
}

/**
 * Canlı yayında geri sarma.
 *
 * <p>Canlı HLS'te gerçek geri sarma yok — playlist yalnızca son birkaç
 * segmenti taşıyor (bizde 7 × 1.96 sn ≈ 14 sn). Daha geriye gitmek DVR
 * kaydından okumayı gerektirir.
 *
 * <p><b>Üç yol var:</b> sabit 30sn/3dk/5dk düğmeleri "az önce ne oldu"yu tek
 * tıkla karşılıyor (o kadarlık sürenin TAMAMI getirilir). Yanındaki DVR
 * çubuğuna <b>tıklamak</b> o andan ŞİMDİYE kadar oynatır ({@link tiklamaSuresi},
 * {@link MAX_RANGE_SECONDS} ile sınırlı); <b>sürüklemek</b> ise seçilen
 * ARALIĞIN TAMAMINI getirir — ikisi de bittiğinde otomatik canlıya dönülür.
 * Üçü de aynı {@code seekTo}'ya çıkıyor: istenen an kayıtlıysa DVR'den
 * bölüm çekilir ve oynatıcıya verilir, değilse uyarı verilir.
 *
 * <p>Kanalda DVR kapalıysa hiç gösterilmiyor: kayıt yoksa geri sarılacak
 * bir şey de yok.
 */
export const LiveRewind = forwardRef<LiveRewindHandle, {
  channel: ChannelDto
  /**
   * Geri sarılan bölümün oynatılabilir adresi (blob) ve o bölümün
   * BAŞLADIĞI mutlak an. İkincisi olmadan, bu parçanın da başına
   * gelindiğinde ("daha da geriye git") bir sonraki parçayı nereden
   * isteyeceğimizi bilemezdik — bkz. PersistentPlayers'taki zincirleme.
   */
  onRewind: (objectUrl: string, start: Date) => void
  onLive: () => void
  rewound: boolean
}>(function LiveRewind({ channel, onRewind, onLive, rewound }, ref) {
  const [spans, setSpans] = useState<TimelineSpan[]>([])
  const [busy, setBusy] = useState(false)
  const [hoverRatio, setHoverRatio] = useState<number | null>(null)
  /** Sürüklenerek seçilen aralığın uçları (oran, 0-1) — sürükleme sırasında dolu. */
  const [dragStart, setDragStart] = useState<number | null>(null)
  const [dragNow, setDragNow] = useState<number | null>(null)
  const barRef = useRef<HTMLDivElement>(null)

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

  async function seekTo(time: Date, durationSeconds: number = tiklamaSuresi(time)) {
    if (!channel.dvrEnabled) return

    let recorded = isRecorded(time)
    if (!recorded) {
      // spans en fazla 60 sn'de bir tazeleniyor (yukarıdaki useEffect).
      // "Az önce" bir ana gitmeyi isteyen kısa adımlar (30 sn) tam bu
      // bayatlık penceresine denk düşebiliyor -- sunucuda kayıt artık
      // hazır olabilir ama istemcideki spans henüz haberdar değildir.
      // Vazgeçmeden önce dar bir aralıkla TAZE bir sorgu at.
      try {
        const taze = await dvrApi.timeline(
          channel.id,
          new Date(time.getTime() - 5_000),
          new Date(time.getTime() + 5_000),
        )
        recorded = taze.some(
          (s) => new Date(s.start).getTime() <= time.getTime() && time.getTime() < new Date(s.end).getTime(),
        )
      } catch {
        // Tazeleme başarısız olursa asagidaki hata zaten gösterilecek.
      }
    }
    if (!recorded) {
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
      const response = await fetch(dvrApi.streamUrl(channel.id, start, durationSeconds), {
        headers: { Authorization: `Bearer ${tokens.accessToken}` },
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      onRewind(URL.createObjectURL(await response.blob()), start)
    } catch {
      toast.error('Geri sarılamadı.', {
        description: 'O aralıkta kayıt bulunmuyor olabilir.',
      })
    } finally {
      setBusy(false)
    }
  }

  /** Çubuktaki bir X koordinatını 0-1 orana çevirir. */
  function ratioAt(clientX: number): number {
    const rect = barRef.current!.getBoundingClientRect()
    return Math.min(Math.max((clientX - rect.left) / rect.width, 0), 1)
  }

  /**
   * Sürüklemeyi başlatır. {@link handlePointerUp}, sürüklenen mesafe küçükse
   * bunu düz bir tıklama sayıp tek noktadan normal süreyle oynatıyor;
   * büyükse SEÇİLEN ARALIĞIN TAMAMINI DVR'dan çekip oynatıyor.
   */
  function handlePointerDown(e: React.PointerEvent) {
    if (busy || !barRef.current) return
    const ratio = ratioAt(e.clientX)
    setDragStart(ratio)
    setDragNow(ratio)
    barRef.current.setPointerCapture(e.pointerId)
  }

  function handlePointerMove(e: React.PointerEvent) {
    if (!barRef.current) return
    const ratio = ratioAt(e.clientX)
    setHoverRatio(ratio)
    if (dragStart != null) setDragNow(ratio)
  }

  /** MIN_DRAG_MS'den kısa sürüklemeler fiili bir tıklama sayılır. */
  const MIN_DRAG_MS = 2000

  function handlePointerUp() {
    if (dragStart == null) return
    const bas = Math.min(dragStart, dragNow ?? dragStart)
    const son = Math.max(dragStart, dragNow ?? dragStart)
    setDragStart(null)
    setDragNow(null)

    const basAn = ratioToTime(bas)
    const sonAn = ratioToTime(son)
    const surenMs = sonAn.getTime() - basAn.getTime()

    if (surenMs < MIN_DRAG_MS) {
      // Fiili bir tiklama -- tek noktadan varsayilan sureyle oynat.
      void seekTo(basAn)
      return
    }

    const istenenSn = Math.round(surenMs / 1000)
    if (istenenSn > MAX_RANGE_SECONDS) {
      toast.error(`Seçim ${Math.round(istenenSn / 60)} dk; en fazla ${MAX_RANGE_SECONDS / 60} dk oynatılabiliyor.`, {
        description: 'Başından itibaren üst sınıra kadar oynatılıyor.',
      })
    }
    // Secilen araligin TAMAMINI getir (30 dk'ya kadar) -- yalnizca
    // baslangicindan degil.
    void seekTo(basAn, Math.max(5, Math.min(istenenSn, MAX_RANGE_SECONDS)))
  }

  function handlePointerLeave() {
    setHoverRatio(null)
    // Sürüklerken çubuktan çıkılırsa (imleç hızlı hareket etti) seçim
    // tamamen kaybolmasın -- pointer capture zaten hareketi izlemeye
    // devam ediyor, yalnızca hover göstergesini gizliyoruz.
  }

  // Canlı oynatıcının kendi "-10 sn" düğmesi, HLS'in ~14 sn'lik tamponu
  // tükenince BUNU çağırıyor (bkz. PlayerControls onBufferExceeded) --
  // böylece kullanıcı ne kadar art arda tıklarsa tıklasın DVR'a sorunsuzca
  // devrediliyor, tamponun kenarında takılıp canlıya sıçramıyor.
  useImperativeHandle(ref, () => ({ seekTo }))

  if (!channel.dvrEnabled) return null

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
              // durationSeconds ACIKCA veriliyor (tiklamaSuresi'nin
              // varsayilanina birakilmiyor): "5 dk" tıklayınca gerçekten
              // 5 dakikanın tamamı getirilsin, o an "şimdi"ye ne kadar
              // yakın olduğuna bakılmasın.
              onClick={() => void seekTo(new Date(Date.now() - s.seconds * 1000), s.seconds + 10)}
              title={`${s.label} geri sar`}
            >
              {s.label}
            </Button>
          ))}

          {/* Tıkla: o andan varsayılan süreyle oynat. Sürükle: seçilen
              ARALIĞIN TAMAMINI oynat, bitince canlıya döner. */}
          <span className="text-xs font-medium text-white/70">DVR</span>
          <div
            ref={barRef}
            className={cn(
              'relative h-7 w-56 cursor-pointer touch-none select-none rounded-lg border bg-secondary/50',
              busy && 'pointer-events-none opacity-60',
            )}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerLeave={handlePointerLeave}
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

            {/* Sürüklenerek seçilen aralık. */}
            {dragStart != null && dragNow != null && (
              <div
                className="absolute inset-y-0.5 rounded bg-white/30"
                style={{
                  left: `${Math.min(dragStart, dragNow) * 100}%`,
                  width: `${Math.abs(dragNow - dragStart) * 100}%`,
                }}
              />
            )}

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
})