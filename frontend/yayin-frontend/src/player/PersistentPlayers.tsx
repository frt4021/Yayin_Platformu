import { useCallback, useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { channelsApi } from '@/api/endpoints'
import type { ChannelDto } from '@/api/types'
import { HlsPlayer } from '@/components/HlsPlayer'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { MAX_TILES, usePlayers } from './PlayerContext'
import { MinimizeIcon, Volume2Icon, VolumeXIcon, XIcon } from 'lucide-react'

/** İzleme sayfasının yolu; katman bu yolda içerik alanını kaplar, diğerlerinde mini olur. */
export const WATCH_PATH = '/izle'

/** Karo sayısına göre sütun sayısı — 16 kanal 4x4'e oturur. */
function columnsFor(count: number) {
  if (count <= 1) return 1
  if (count <= 4) return 2
  if (count <= 9) return 3
  return 4
}

/**
 * Tüm HLS oynatıcılarını barındıran kalıcı katman.
 *
 * <p><b>Neden burada:</b> oynatıcılar route'un içinde yaşasaydı, kullanıcı
 * başka bir sayfaya geçtiğinde React onları unmount eder, video elementleri
 * yok olur ve yayın kesilirdi. Bu katman {@code AppLayout} içinde
 * {@code <Outlet/>}'in dışında duruyor; sayfa değişse de mount kalıyor.
 *
 * <p><b>Tek ağaç kuralı:</b> görünüm (izleme sayfası / mini / büyük ekran)
 * yalnızca CSS sınıflarıyla değişiyor, bileşen ağacı hiç değişmiyor. Farklı
 * durumlar için farklı JSX dalları döndürseydik React ağacı söker ve her
 * geçişte yayın baştan bağlanırdı. Aynı sebeple kanal başına <b>tek</b>
 * {@link HlsPlayer} var — büyük ekran ayrı bir oynatıcı kurmuyor, sadece
 * diğer karolar gizleniyor.
 */
export function PersistentPlayers() {
  const location = useLocation()
  const onWatchPage = location.pathname === WATCH_PATH

  const { openIds, audioId, expandedId, toggle, openMany, closeAll, setAudio, expand } =
    usePlayers()

  const [channels, setChannels] = useState<ChannelDto[]>([])

  const load = useCallback(async () => {
    try {
      setChannels(await channelsApi.list())
    } catch {
      // Liste alınamazsa açık oynatıcılara dokunmuyoruz; yayın akmaya devam etsin.
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), 30000)
    return () => clearInterval(timer)
  }, [load])

  // openIds sırası korunuyor; kanal listesi tazelenince karolar yer değiştirmesin.
  const open = openIds
    .map((id) => channels.find((c) => c.id === id))
    .filter((c): c is ChannelDto => Boolean(c))

  const playable = channels.filter((c) => c.active)
  const expanded = open.find((c) => c.id === expandedId) ?? null
  // Mini görünümde tek karo gösterilir: sesi olan, yoksa ilk açık kanal.
  const miniChannel = expanded ?? open.find((c) => c.id === audioId) ?? open[0] ?? null

  // Hiç açık kanal yoksa ve izleme sayfasında değilsek gösterilecek bir şey yok.
  // Bu durumda korunacak bir oynatıcı da olmadığı için unmount sakıncasız.
  if (open.length === 0 && !onWatchPage) return null

  const singleTile = !onWatchPage || expanded !== null

  function tileVisible(channel: ChannelDto) {
    if (!onWatchPage) return channel.id === miniChannel?.id
    if (expanded) return channel.id === expanded.id
    return true
  }

  return (
    <div
      className={
        onWatchPage
          ? 'fixed inset-x-0 bottom-0 top-14 z-10 flex flex-col gap-3 bg-background p-4'
          : 'fixed bottom-4 right-4 z-50 w-80 overflow-hidden rounded-xl border bg-card shadow-lg'
      }
    >
      {/* Kanal seçim şeridi — mini görünümde gizli, ama DOM'da kalıyor ki
          kardeş sıralaması değişmesin ve karolar remount olmasın. */}
      <div className={cn('flex flex-wrap items-center gap-2', !onWatchPage && 'hidden')}>
        <span className="text-sm font-medium">İzleme</span>
        <Badge variant="secondary">
          {open.length} / {MAX_TILES}
        </Badge>

        <div className="mx-2 h-5 w-px bg-border" />

        <div className="flex flex-wrap gap-1.5">
          {playable.length === 0 && (
            <span className="text-sm text-muted-foreground">Yayında kanal yok.</span>
          )}
          {playable.map((channel) => {
            const isOpen = openIds.includes(channel.id)
            return (
              <Button
                key={channel.id}
                size="sm"
                variant={isOpen ? 'default' : 'outline'}
                disabled={!isOpen && openIds.length >= MAX_TILES}
                onClick={() => toggle(channel.id)}
                title={
                  channel.streaming === false ? 'Kanal aktif ama yayın akmıyor' : undefined
                }
              >
                {channel.name}
              </Button>
            )
          })}
        </div>

        <div className="ml-auto flex gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() => openMany(playable.map((c) => c.id))}
            disabled={playable.length === 0}
          >
            Tümünü aç
          </Button>
          <Button size="sm" variant="outline" onClick={closeAll} disabled={open.length === 0}>
            Tümünü kapat
          </Button>
        </div>
      </div>

      {/* Mini başlık — izleme sayfasında gizli. */}
      <div
        className={cn(
          'flex items-center justify-between gap-2 px-3 py-2 text-xs',
          onWatchPage && 'hidden',
        )}
      >
        <span className="truncate font-medium">{miniChannel?.name}</span>
        <div className="flex items-center gap-1">
          {open.length > 1 && <Badge variant="secondary">+{open.length - 1}</Badge>}
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            title="Tümünü kapat"
            onClick={closeAll}
          >
            <XIcon />
          </Button>
        </div>
      </div>

      {/* Karolar. Kapsayıcı hep aynı; yalnızca sütun sayısı ve karo görünürlüğü değişiyor. */}
      <div className={onWatchPage ? 'min-h-0 flex-1' : 'aspect-video bg-black'}>
        <div
          className="grid size-full gap-2"
          style={{
            gridTemplateColumns: `repeat(${singleTile ? 1 : columnsFor(open.length)}, minmax(0, 1fr))`,
          }}
        >
          {open.map((channel) => (
            <Tile
              key={channel.id}
              channel={channel}
              visible={tileVisible(channel)}
              expanded={expanded?.id === channel.id}
              compact={!onWatchPage}
              hasAudio={channel.id === audioId}
              onToggleExpand={() => expand(expanded?.id === channel.id ? null : channel.id)}
              onAudio={() => setAudio(channel.id === audioId ? null : channel.id)}
              onClose={() => toggle(channel.id)}
            />
          ))}
        </div>
      </div>

      {open.length === 0 && onWatchPage && (
        <div className="pointer-events-none absolute inset-x-4 bottom-4 top-20 grid place-items-center rounded-xl border border-dashed text-sm text-muted-foreground">
          Yukarıdan kanal seçin.
        </div>
      )}
    </div>
  )
}

function Tile({
  channel,
  visible,
  expanded,
  compact,
  hasAudio,
  onToggleExpand,
  onAudio,
  onClose,
}: {
  channel: ChannelDto
  visible: boolean
  expanded: boolean
  /** Mini görünüm: üst çubuk ve kenarlık gösterilmez. */
  compact: boolean
  hasAudio: boolean
  onToggleExpand: () => void
  onAudio: () => void
  onClose: () => void
}) {
  return (
    <div
      className={cn(
        'group relative min-h-0 overflow-hidden bg-black',
        !compact && 'rounded-lg border',
        // Gizli karonun oynatıcısı DOM'da kalıyor ve çalmaya devam ediyor;
        // kaldırılsaydı geri dönüldüğünde yayın baştan bağlanırdı.
        !visible && 'hidden',
      )}
    >
      <HlsPlayer src={channel.hlsUrl} muted={!hasAudio} className="size-full" />

      {/* Karoya tıklamak büyütür/küçültür. */}
      <button
        type="button"
        className="absolute inset-0 cursor-pointer"
        onClick={onToggleExpand}
        aria-label={expanded ? 'Küçült' : 'Büyük ekranda aç'}
      />

      <div
        className={cn(
          'pointer-events-none absolute inset-x-0 top-0 flex items-start justify-between gap-2 bg-gradient-to-b from-black/70 to-transparent p-2',
          compact && 'hidden',
        )}
      >
        <span className="truncate text-xs font-medium text-white">{channel.name}</span>
        <div className="pointer-events-auto flex gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
          <Button
            variant="secondary"
            size="icon"
            className="size-7"
            title={hasAudio ? 'Sesi kapat' : 'Sesi bu kanala ver'}
            onClick={onAudio}
          >
            {hasAudio ? <Volume2Icon /> : <VolumeXIcon />}
          </Button>
          {expanded && (
            <Button
              variant="secondary"
              size="icon"
              className="size-7"
              title="Küçült"
              onClick={onToggleExpand}
            >
              <MinimizeIcon />
            </Button>
          )}
          <Button
            variant="secondary"
            size="icon"
            className="size-7"
            title="Kapat"
            onClick={onClose}
          >
            <XIcon />
          </Button>
        </div>
      </div>

      {hasAudio && !compact && (
        <div className="pointer-events-none absolute bottom-2 left-2">
          <Badge variant="secondary" className="gap-1">
            <Volume2Icon className="size-3" />
            ses
          </Badge>
        </div>
      )}
    </div>
  )
}
