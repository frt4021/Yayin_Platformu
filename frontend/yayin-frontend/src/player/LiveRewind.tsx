import { useState } from 'react'
import { toast } from 'sonner'
import { dvrApi } from '@/api/endpoints'
import { readTokens } from '@/api/tokens'
import type { ChannelDto } from '@/api/types'
import { Button } from '@/components/ui/button'
import { RadioIcon, RotateCcwIcon } from 'lucide-react'

/** Geri sarma adımları. Daha uzun aralıklar için Geriye sarma sayfası var. */
const STEPS = [
  { label: '30 sn', seconds: 30 },
  { label: '1 dk', seconds: 60 },
  { label: '5 dk', seconds: 300 },
] as const

/** Geri sarınca kaç saniyelik bölüm yüklenecek. */
const CHUNK_SECONDS = 120

/**
 * Canlı yayında geri sarma.
 *
 * <p>Canlı HLS'te gerçek geri sarma yok — playlist yalnızca son birkaç
 * segmenti taşıyor (bizde 7 × 1.96 sn ≈ 14 sn). Daha geriye gitmek DVR
 * kaydından okumayı gerektiriyor, o da ayrı bir uç ve ayrı bir dosya.
 * Bu yüzden geri sarma, oynatıcıyı canlı akıştan DVR bölümüne
 * <b>değiştiriyor</b>; "Canlıya dön" ile geri alınıyor.
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
  const [busy, setBusy] = useState(false)

  if (!channel.dvrEnabled) return null

  async function rewind(seconds: number) {
    const tokens = readTokens()
    if (!tokens) return
    setBusy(true)
    try {
      // Canlı kenar ile kayıt arasında paketleme gecikmesi var (~4 sn);
      // istenen ana tam oturmak için biraz daha geriden başlıyoruz.
      const start = new Date(Date.now() - (seconds + 5) * 1000)
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

  return (
    <div className="pointer-events-auto flex items-center gap-1">
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
              onClick={() => void rewind(s.seconds)}
              title={`${s.label} geri sar`}
            >
              {s.label}
            </Button>
          ))}
        </>
      )}
    </div>
  )
}
