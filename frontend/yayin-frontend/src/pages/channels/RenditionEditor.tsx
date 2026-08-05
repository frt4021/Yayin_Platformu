import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PlusIcon, XIcon } from 'lucide-react'

export interface Rendition {
  suffix: string
  width: string
  height: string
  bitrate: string
}

/** Hazır seçenekler; elle de düzenlenebilir. */
const PRESETS: Rendition[] = [
  { suffix: '720p', width: '1280', height: '720', bitrate: '1500k' },
  { suffix: '480p', width: '854', height: '480', bitrate: '800k' },
  { suffix: '360p', width: '640', height: '360', bitrate: '500k' },
  { suffix: '240p', width: '426', height: '240', bitrate: '300k' },
]

/** Backend biçimi: {@code 720p|1280x720|1500k,480p|854x480|800k} */
export function toSpec(rows: Rendition[]): string {
  return rows
    .filter((r) => r.suffix && r.width && r.height && r.bitrate)
    .map((r) => `${r.suffix}|${r.width}x${r.height}|${r.bitrate}`)
    .join(',')
}

export function fromSpec(spec: string): Rendition[] {
  if (!spec) return []
  return spec
    .split(',')
    .map((entry) => entry.split('|'))
    .filter((parts) => parts.length === 3)
    .map(([suffix, size, bitrate]) => {
      const [width, height] = size.toLowerCase().split('x')
      return { suffix, width: width ?? '', height: height ?? '', bitrate }
    })
}

/**
 * Çözünürlük merdiveni editörü.
 *
 * <p>Merdiven her zaman aşağı doğrudur — kaynağın verdiğinden yüksek
 * çözünürlük üretilemez, büyütmek yalnızca dosya boyutunu artırır.
 */
export function RenditionEditor({
  rows,
  onChange,
  sourceWidth,
  sourceHeight,
}: {
  rows: Rendition[]
  onChange: (rows: Rendition[]) => void
  /** Kaynağın tespit edilen çözünürlüğü; bilinmiyorsa null. */
  sourceWidth?: number | null
  sourceHeight?: number | null
}) {
  function update(index: number, patch: Partial<Rendition>) {
    onChange(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  }

  function addPreset() {
    // Kaynaktan yuksek preset'ler hic onerilmiyor: backend zaten reddediyor,
    // once eklettirip sonra hata gostermek gereksiz bir tur olurdu.
    const usable = PRESETS.filter(
      (p) => !sourceHeight || Number(p.height) <= sourceHeight,
    )
    const missing = usable.find((p) => !rows.some((r) => r.suffix === p.suffix))
    onChange([...rows, missing ?? { suffix: '', width: '', height: '', bitrate: '' }])
  }

  /** Kaynaktan yüksek satırlar işaretleniyor; backend bunları reddedecek. */
  function exceedsSource(row: Rendition) {
    return Boolean(sourceHeight) && Number(row.height) > Number(sourceHeight)
  }

  return (
    <div className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Label>Çözünürlük merdiveni</Label>
          {sourceHeight ? (
            <span className="text-xs text-muted-foreground">
              kaynak: {sourceWidth}x{sourceHeight}
            </span>
          ) : (
            <span className="text-xs text-muted-foreground">kaynak çözünürlüğü bilinmiyor</span>
          )}
        </div>
        <Button type="button" size="sm" variant="outline" onClick={addPreset}>
          <PlusIcon />
          Ekle
        </Button>
      </div>

      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground">
          Boş — kaynağın çözünürlüğü olduğu gibi dağıtılır, transcode yapılmaz.
        </p>
      ) : (
        <div className="flex flex-col gap-2">
          {rows.map((r, i) => (
            <div key={i} className="flex flex-col gap-1">
            <div className="grid grid-cols-[5rem_4rem_4rem_5rem_auto] items-center gap-1.5">
              <Input
                aria-label="Ad"
                placeholder="720p"
                value={r.suffix}
                onChange={(e) => update(i, { suffix: e.target.value })}
              />
              <Input
                aria-label="Genişlik"
                placeholder="1280"
                inputMode="numeric"
                value={r.width}
                onChange={(e) => update(i, { width: e.target.value })}
              />
              <Input
                aria-label="Yükseklik"
                placeholder="720"
                inputMode="numeric"
                value={r.height}
                onChange={(e) => update(i, { height: e.target.value })}
              />
              <Input
                aria-label="Bit hızı"
                placeholder="1500k"
                value={r.bitrate}
                onChange={(e) => update(i, { bitrate: e.target.value })}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                title="Kaldır"
                onClick={() => onChange(rows.filter((_, j) => j !== i))}
              >
                <XIcon />
              </Button>
            </div>
            {exceedsSource(r) && (
              <p className="text-xs text-destructive">
                Kaynaktan yüksek ({sourceWidth}x{sourceHeight}) — kaydedilemez.
                Büyütme ayrıntı kazandırmaz, yalnızca bant genişliği ve GPU harcar.
              </p>
            )}
            </div>
          ))}
        </div>
      )}

      <p className="text-xs text-muted-foreground">
        Bit hızları <strong>kaynağınkinin altında</strong> olmalı. Üstünde bir değer
        çözünürlüğü düşürür ama bant genişliğini artırır — ölçümde 2.29 Mbps'lik bir
        kaynağa 2500k'lık 720p uygulandığında çıktı 2.51 Mbps oldu.
      </p>
      <p className="text-xs text-muted-foreground">
        Her rendition GPU'da ayrı kodlanır: ölçülen maliyet ~%14 CPU. Kanal ve
        rendition sayısıyla çarpılır.
      </p>
    </div>
  )
}
