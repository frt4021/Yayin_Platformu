import { useCallback, useMemo, useRef, useState } from 'react'
import type { TimelineSpan } from '@/api/types'
import { cn } from '@/lib/utils'

export interface Selection {
  start: Date
  end: Date
}

/**
 * Kayıt zaman çizelgesi.
 *
 * <p>Sürükleyerek aralık seçilir. Yalnızca kayıt bulunan bölgeler seçilebilir;
 * boş bölgeden klip üretmek kullanıcının beklediğinden kısa veya boşluklu bir
 * dosya çıkarırdı, backend de bunu reddediyor.
 *
 * <p>Ölçekleme piksel değil <b>oran</b> üzerinden yapılıyor: bileşen genişliği
 * değişse de (pencere boyutu, yan panel) aralıklar doğru yerde kalır.
 */
export function Timeline({
  from,
  to,
  spans,
  selection,
  onSelectionChange,
  onSeek,
}: {
  from: Date
  to: Date
  spans: TimelineSpan[]
  selection: Selection | null
  onSelectionChange: (selection: Selection | null) => void
  /** Kayıtlı bir noktaya tıklandığında oynatma isteği. */
  onSeek: (at: Date) => void
}) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [dragStart, setDragStart] = useState<Date | null>(null)
  const [hover, setHover] = useState<Date | null>(null)

  const windowMs = to.getTime() - from.getTime()

  const parsed = useMemo(
    () => spans.map((s) => ({ start: new Date(s.start), end: new Date(s.end) })),
    [spans],
  )

  const ratioOf = useCallback(
    (date: Date) => (date.getTime() - from.getTime()) / windowMs,
    [from, windowMs],
  )

  const dateAt = useCallback(
    (clientX: number) => {
      const track = trackRef.current
      if (!track) return null
      const rect = track.getBoundingClientRect()
      const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
      return new Date(from.getTime() + ratio * windowMs)
    },
    [from, windowMs],
  )

  const isRecorded = useCallback(
    (date: Date) => parsed.some((s) => date >= s.start && date <= s.end),
    [parsed],
  )

  function onPointerDown(event: React.PointerEvent) {
    const at = dateAt(event.clientX)
    if (!at || !isRecorded(at)) return
    event.currentTarget.setPointerCapture(event.pointerId)
    setDragStart(at)
    onSelectionChange(null)
  }

  function onPointerMove(event: React.PointerEvent) {
    const at = dateAt(event.clientX)
    setHover(at)
    if (!dragStart || !at) return
    onSelectionChange({
      start: at < dragStart ? at : dragStart,
      end: at < dragStart ? dragStart : at,
    })
  }

  function onPointerUp(event: React.PointerEvent) {
    const at = dateAt(event.clientX)
    if (dragStart && at) {
      const dragged = Math.abs(at.getTime() - dragStart.getTime())
      // Sürükleme değil tıklama ise (2 sn'den kısa) aralık seçmek yerine
      // o ana atla; kullanıcı çoğu zaman önce izlemek ister.
      if (dragged < 2000) {
        onSelectionChange(null)
        onSeek(dragStart)
      }
    }
    setDragStart(null)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  // Pencere uzunluğuna göre okunabilir bir ızgara: 7 günde günlük,
  // 1 günde 4 saatlik, daha kısa aralıkta saatlik.
  const ticks = useMemo(() => {
    const hours = windowMs / 3_600_000
    const stepHours = hours > 72 ? 24 : hours > 12 ? 4 : 1
    const step = stepHours * 3_600_000
    const first = Math.ceil(from.getTime() / step) * step
    const out: Date[] = []
    for (let t = first; t < to.getTime(); t += step) out.push(new Date(t))
    return out
  }, [from, to, windowMs])

  const fmt = (d: Date) =>
    windowMs > 48 * 3_600_000
      ? d.toLocaleDateString('tr-TR', { day: '2-digit', month: 'short' })
      : d.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' })

  return (
    <div className="select-none">
      <div
        ref={trackRef}
        className="relative h-16 cursor-crosshair rounded-lg border bg-muted/40"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerLeave={() => setHover(null)}
      >
        {/* Kayıt bulunan bölgeler */}
        {parsed.map((span, i) => {
          const left = ratioOf(span.start)
          const width = ratioOf(span.end) - left
          if (width <= 0) return null
          return (
            <div
              key={i}
              className="absolute inset-y-0 bg-primary/25"
              style={{ left: `${left * 100}%`, width: `${Math.max(width * 100, 0.15)}%` }}
              title={`${span.start.toLocaleString('tr-TR')} — ${span.end.toLocaleString('tr-TR')}`}
            />
          )
        })}

        {/* Saat/gün çizgileri */}
        {ticks.map((tick) => (
          <div
            key={tick.getTime()}
            className="absolute inset-y-0 w-px bg-border"
            style={{ left: `${ratioOf(tick) * 100}%` }}
          />
        ))}

        {/* Seçili aralık */}
        {selection && (
          <div
            className="absolute inset-y-0 border-x-2 border-primary bg-primary/35"
            style={{
              left: `${ratioOf(selection.start) * 100}%`,
              width: `${(ratioOf(selection.end) - ratioOf(selection.start)) * 100}%`,
            }}
          />
        )}

        {/* İmleç çizgisi */}
        {hover && (
          <div
            className={cn(
              'pointer-events-none absolute inset-y-0 w-px',
              isRecorded(hover) ? 'bg-foreground/60' : 'bg-foreground/20',
            )}
            style={{ left: `${ratioOf(hover) * 100}%` }}
          />
        )}
      </div>

      {/* Etiketler */}
      <div className="relative mt-1 h-4">
        {ticks.map((tick) => (
          <span
            key={tick.getTime()}
            className="absolute -translate-x-1/2 text-[11px] text-muted-foreground"
            style={{ left: `${ratioOf(tick) * 100}%` }}
          >
            {fmt(tick)}
          </span>
        ))}
      </div>

      <p className="mt-2 text-xs text-muted-foreground">
        {hover
          ? `${hover.toLocaleString('tr-TR')}${isRecorded(hover) ? '' : ' — kayıt yok'}`
          : 'Kayıtlı bir noktaya tıklayın veya sürükleyerek aralık seçin.'}
      </p>
    </div>
  )
}
