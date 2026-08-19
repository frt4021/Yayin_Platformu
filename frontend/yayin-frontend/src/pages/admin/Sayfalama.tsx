import { Button } from '@/components/ui/button'
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react'

/**
 * Numaralı sayfalama — AdminEtkinliklerPage ve AdminUsersPage'de aynı
 * mantık tekrarlanmasın diye tek yerde. Uzun listede tüm sayfa numaralarını
 * değil, uçlar + mevcut sayfanın çevresini gösterir.
 */
export function Sayfalama({
  first,
  max,
  total,
  loading,
  onSayfaDegis,
}: {
  first: number
  max: number
  total: number
  loading: boolean
  onSayfaDegis: (yeniFirst: number) => void
}) {
  const suankiSayfa = Math.floor(first / max) + 1
  const toplamSayfa = Math.max(1, Math.ceil(total / max))

  function sayfaNumaralari(): (number | 'bosluk')[] {
    if (toplamSayfa <= 7) {
      return Array.from({ length: toplamSayfa }, (_, i) => i + 1)
    }
    const sonuc: (number | 'bosluk')[] = [1]
    if (suankiSayfa > 3) sonuc.push('bosluk')
    for (let n = Math.max(2, suankiSayfa - 1); n <= Math.min(toplamSayfa - 1, suankiSayfa + 1); n++) {
      sonuc.push(n)
    }
    if (suankiSayfa < toplamSayfa - 2) sonuc.push('bosluk')
    sonuc.push(toplamSayfa)
    return sonuc
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
      <span>
        {total === 0 ? '0' : `${first + 1}–${Math.min(first + max, total)}`} / {total}
      </span>
      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="icon"
          className="rounded-full"
          disabled={first === 0 || loading}
          onClick={() => onSayfaDegis(Math.max(0, first - max))}
          title="Önceki sayfa"
        >
          <ChevronLeftIcon />
        </Button>

        {sayfaNumaralari().map((n, i) =>
          n === 'bosluk' ? (
            <span key={`bosluk-${i}`} className="px-1.5">…</span>
          ) : (
            <Button
              key={n}
              variant={n === suankiSayfa ? 'default' : 'outline'}
              size="icon"
              className="rounded-full"
              disabled={loading}
              onClick={() => onSayfaDegis((n - 1) * max)}
            >
              {n}
            </Button>
          ),
        )}

        <Button
          variant="outline"
          size="icon"
          className="rounded-full"
          disabled={first + max >= total || loading}
          onClick={() => onSayfaDegis(first + max)}
          title="Sonraki sayfa"
        >
          <ChevronRightIcon />
        </Button>
      </div>
    </div>
  )
}
