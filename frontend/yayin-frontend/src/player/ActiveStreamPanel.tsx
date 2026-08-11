import { MonitorPlayIcon, TvIcon } from 'lucide-react'
import type { ChannelDto } from '@/api/types'
import { cn } from '@/lib/utils'
import { MAX_TILES, usePlayers } from './PlayerContext'

/**
 * Sağdaki yayın paneli.
 *
 * <h2>Kanal çipleriyle farkı</h2>
 * Ortadaki çipler <b>hızlı aç/kapa</b> için: tek tıkla karo eklenip
 * çıkarılıyor. Bu panel <b>durum</b> gösteriyor — hangi kanal yayında, hangisi
 * seste, çözünürlüğü ne. Çiplerde bunlara yer yok; her birine ikinci bir satır
 * eklemek şeridi kullanılamaz hale getirirdi.
 *
 * <h2>İkinci satırda ne yazıyor</h2>
 * Tasarımda "Live Broadcast" / "Sports Channel" gibi açıklamalar vardı ama
 * <b>kanalın açıklama alanı yok</b> — uydurulmuş metin yazmak yerine gerçek
 * bilgi konuldu: kaynak çözünürlüğü ve yayın durumu.
 */
export function ActiveStreamPanel() {
  const { channels, openIds, audioId, toggle, setAudio } = usePlayers()

  const yayindakiler = channels.filter((c) => c.active)
  const sesli = channels.find((c) => c.id === audioId) ?? null

  return (
    <aside className="fixed inset-y-0 right-0 z-20 flex w-80 flex-col border-l bg-panel">
      <div className="border-b px-5 py-4 text-center">
        <p className="text-sm font-medium text-muted-foreground">Etkin yayın</p>
        <p className="mt-0.5 truncate text-base font-semibold">
          {sesli?.name ?? (openIds.length > 0 ? `${openIds.length} kanal açık` : 'Yok')}
        </p>
      </div>

      <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-3">
        {yayindakiler.length === 0 && (
          <p className="px-2 py-6 text-center text-sm text-muted-foreground">
            Yayında kanal yok.
          </p>
        )}
        {yayindakiler.map((channel) => (
          <KanalSatiri
            key={channel.id}
            channel={channel}
            acik={openIds.includes(channel.id)}
            sesli={channel.id === audioId}
            doluysaKapali={!openIds.includes(channel.id) && openIds.length >= MAX_TILES}
            onToggle={() => toggle(channel.id)}
            onSes={() => setAudio(channel.id === audioId ? null : channel.id)}
          />
        ))}
      </div>
    </aside>
  )
}

function KanalSatiri({
  channel,
  acik,
  sesli,
  doluysaKapali,
  onToggle,
  onSes,
}: {
  channel: ChannelDto
  acik: boolean
  sesli: boolean
  doluysaKapali: boolean
  onToggle: () => void
  onSes: () => void
}) {
  return (
    <div
      className={cn(
        'flex items-center gap-3 rounded-xl border p-2.5 transition-colors',
        acik ? 'border-primary/40 bg-accent' : 'border-transparent hover:bg-accent/60',
      )}
    >
      <button
        type="button"
        onClick={onToggle}
        disabled={doluysaKapali}
        title={acik ? 'Karoyu kapat' : 'Karoyu aç'}
        className="flex min-w-0 flex-1 items-center gap-3 text-left disabled:opacity-40"
      >
        <span
          className={cn(
            'grid size-9 shrink-0 place-items-center rounded-lg',
            acik ? 'bg-primary text-primary-foreground' : 'bg-secondary text-muted-foreground',
          )}
        >
          {acik ? <MonitorPlayIcon className="size-4" /> : <TvIcon className="size-4" />}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-medium">{channel.name}</span>
          <span className="block truncate text-xs text-muted-foreground">
            {ikinciSatir(channel)}
          </span>
        </span>
      </button>

      {/* CANLI rozeti yalnizca gercekten akan yayinda. Kanal "aktif" olabilir
          ama kaynak dusmus olabilir; ikisini ayirmayan bir rozet yaniltici. */}
      {channel.streaming === false ? (
        <span className="shrink-0 rounded-full bg-status-warning-bg px-2 py-0.5 text-[10px] font-semibold uppercase text-status-warning">
          bekliyor
        </span>
      ) : (
        <button
          type="button"
          onClick={onSes}
          disabled={!acik}
          title={sesli ? 'Sesi kapat' : 'Sesi bu kanala ver'}
          className={cn(
            'flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase transition-colors',
            sesli
              ? 'bg-primary text-primary-foreground'
              : 'bg-status-live-bg text-status-live disabled:opacity-50',
          )}
        >
          <span className="size-1.5 rounded-full bg-current" />
          {sesli ? 'ses' : 'canlı'}
        </button>
      )}
    </div>
  )
}

/**
 * Kanal adının altındaki satır.
 *
 * <p>Kaynak çözünürlüğü biliniyorsa o gösteriliyor — kullanıcının kanalları
 * ayırt etmesine gerçekten yarayan tek ek bilgi bu. Bilinmiyorsa izleyici
 * sayısına, o da yoksa yol adına düşülüyor.
 */
function ikinciSatir(channel: ChannelDto): string {
  if (channel.sourceWidth && channel.sourceHeight) {
    return `${channel.sourceWidth}×${channel.sourceHeight}`
  }
  if (channel.viewers != null) {
    return `${channel.viewers} izleyici`
  }
  return channel.mediamtxPath
}
