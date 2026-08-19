import { CaptionsIcon } from 'lucide-react'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'

/**
 * Altyazı dili seçici — tek, paylaşılan bileşen.
 *
 * <p>Önceden dört ayrı yerde (ClipsPage, VideosPage, DvrPage, PersistentPlayers)
 * elle stillendirilmiş, birbirinden az farklı ham {@code <select>}'ler vardı.
 * Bu, projenin kendi tasarım sistemindeki (Radix tabanlı) {@code Select}'i
 * kullanan tek, tutarlı bir bileşene indiriyor.
 */
export function SubtitlePicker({
  tracks,
  value,
  onChange,
  className,
}: {
  /** Video/klip için üretilmiş diller — "kapalı" burada YOK, bileşen kendisi ekliyor. */
  tracks: { lang: string; label: string }[]
  value: string
  onChange: (lang: string) => void
  className?: string
}) {
  if (tracks.length === 0) return null
  return (
    <Select value={value} onValueChange={onChange}>
      {/* Varsayılan renk video-üstü bindirme kullanımı için (koyu, yarı
          saydam) -- üç kullanımdan üçü bu. Diyalog içi (açık zeminli)
          kullanım className ile geçersiz kılıyor; cn() twMerge kullandığı
          için çakışan bg/border/text sınıfları doğru şekilde SONUNCUYU
          kazandırıyor. */}
      <SelectTrigger
        aria-label="Altyazı dili"
        className={cn(
          'h-8 w-auto gap-1.5 border-white/30 bg-black/70 px-2 text-sm text-white backdrop-blur-sm hover:bg-black/80',
          className,
        )}
      >
        <CaptionsIcon className="size-4" />
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="kapali">Kapalı</SelectItem>
        {tracks.map((t) => (
          <SelectItem key={t.lang} value={t.lang}>
            {t.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
