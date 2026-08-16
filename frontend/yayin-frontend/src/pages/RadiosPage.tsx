import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { radiosApi } from '@/api/endpoints'
import type { RadioDto } from '@/api/types'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { usePlayers } from '@/player/PlayerContext'
import { Logo } from '@/player/PersistentRadio'
import {
  AudioLinesIcon,
  Loader2Icon,
  PauseIcon,
  PencilIcon,
  PlayIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchIcon,
  Trash2Icon,
} from 'lucide-react'
import { RadioFormDialog } from './radios/RadioFormDialog'

export function RadiosPage() {
  const { hasRole } = useAuth()
  const canManage = hasRole('Yönetici', 'Moderatör')
  const { radioId, radioPaused, playRadio, toggleRadioPause, radios, capacity, refreshRadios } =
    usePlayers()

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<RadioDto | null>(null)
  const [pending, setPending] = useState<Set<string>>(new Set())

  // Liste ve periyodik tazeleme artık PlayerContext'te (radyo çalarken bir
  // istasyondan diğerine geçince dinleyici sayısının anında güncellenmesi
  // için paylaşılan tek state gerekiyor — bkz. PlayerContext.tsx). Burada
  // yalnızca bu sayfaya özel ilk-yükleme hatasını yakalamak için ayrı bir
  // çağrı yapılıyor.
  useEffect(() => {
    refreshRadios()
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Radyolar yüklenemedi.'))
      .finally(() => setLoading(false))
  }, [refreshRadios])

  const visible = useMemo(() => {
    const q = search.trim().toLocaleLowerCase('tr')
    if (!q) return radios
    return radios.filter((r) => r.name.toLocaleLowerCase('tr').includes(q))
  }, [radios, search])

  function baslat(radio: RadioDto) {
    if (radio.id === radioId) {
      toggleRadioPause()
      return
    }
    playRadio(radio.id)
  }

  async function remove(radio: RadioDto) {
    if (!confirm(`"${radio.name}" silinecek ve yayını durdurulacak. Emin misiniz?`)) return
    setPending((prev) => new Set(prev).add(radio.id))
    try {
      await radiosApi.remove(radio.id)
      toast.success(`${radio.name} silindi.`)
      await refreshRadios()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Radyo silinemedi.')
    } finally {
      setPending((prev) => {
        const next = new Set(prev)
        next.delete(radio.id)
        return next
      })
    }
  }

  async function restore() {
    try {
      const result = await radiosApi.restore()
      toast.success(`${result.restored} radyo MediaMTX'e yeniden yazıldı.`)
      await refreshRadios()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Radyolar geri yüklenemedi.')
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-3xl font-semibold tracking-tight">Radyolar</h1>
        {capacity && (
          <Badge variant="secondary">
            {capacity.active} / {capacity.max} yayında
          </Badge>
        )}

        <div className="relative ml-auto">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
          <Input
            placeholder="İstasyon ara"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-52 pl-8"
          />
        </div>

        {canManage && (
          <>
            <Button variant="outline" onClick={() => void restore()} title="Aktif radyoları MediaMTX'e yeniden yaz">
              <RefreshCwIcon />
              Geri yükle
            </Button>
            <Button
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <PlusIcon />
              Yeni radyo
            </Button>
          </>
        )}
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      {loading ? (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" />
          Yükleniyor…
        </div>
      ) : visible.length === 0 ? (
        <div className="grid place-items-center gap-2 rounded-xl border border-dashed p-12 text-center">
          <AudioLinesIcon className="size-8 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            {radios.length === 0
              ? 'Henüz radyo eklenmemiş.'
              : `"${search}" ile eşleşen istasyon yok.`}
          </p>
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {visible.map((radio) => (
            <StationCard
              key={radio.id}
              radio={radio}
              playing={radio.id === radioId && !radioPaused}
              selected={radio.id === radioId}
              busy={pending.has(radio.id)}
              canManage={canManage}
              onPlay={() => baslat(radio)}
              onEdit={() => {
                setEditing(radio)
                setFormOpen(true)
              }}
              onDelete={() => void remove(radio)}
            />
          ))}
        </div>
      )}

      <RadioFormDialog
        radio={editing}
        open={formOpen}
        onOpenChange={setFormOpen}
        onSaved={() => void refreshRadios()}
      />
    </div>
  )
}

function StationCard({
  radio,
  playing,
  selected,
  busy,
  canManage,
  onPlay,
  onEdit,
  onDelete,
}: {
  radio: RadioDto
  playing: boolean
  /** Çubukta seçili istasyon; duraklatılmış olsa da vurgulanır. */
  selected: boolean
  busy: boolean
  canManage: boolean
  onPlay: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  // Pasif radyonun MediaMTX'te path'i yok; oynatmayı denemek boşuna.
  const playable = radio.active

  return (
    <div
      className={cn(
        'group relative flex items-center gap-3 rounded-xl border bg-card p-3 transition-colors',
        selected ? 'border-primary bg-accent/40' : 'hover:bg-accent/30',
      )}
    >
      <div className="relative shrink-0">
        <Logo radio={radio} className="size-14 rounded-lg text-base" />
        <button
          type="button"
          disabled={!playable}
          onClick={onPlay}
          title={!playable ? 'Radyo pasif' : playing ? 'Duraklat' : 'Dinle'}
          className={cn(
            'absolute inset-0 grid place-items-center rounded-lg bg-black/55 text-white transition-opacity',
            'disabled:cursor-not-allowed',
            playing || selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100',
            !playable && 'opacity-0',
          )}
        >
          {playing ? <PauseIcon className="size-6" /> : <PlayIcon className="size-6" />}
        </button>
      </div>

      <div className="min-w-0 flex-1">
        <div className="truncate font-medium">{radio.name}</div>

        <div className="mt-1 flex flex-wrap items-center gap-1.5">
          <StatusBadge radio={radio} />
          {radio.sourceKind === 'KOPRU' && (
            <Badge
              variant="outline"
              title="MediaMTX içinde bir ffmpeg süreci kaynağı AAC'ye kodluyor"
            >
              köprü {radio.bitrate}
            </Badge>
          )}
          {radio.listeners != null && radio.listeners > 0 && (
            <span className="text-xs text-muted-foreground">
              {radio.listeners} dinleyici
            </span>
          )}
        </div>
      </div>

      {canManage && (
        <div className="flex shrink-0 flex-col gap-1 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
          <Button variant="ghost" size="icon" className="size-7" title="Düzenle" onClick={onEdit}>
            <PencilIcon />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            title="Sil"
            disabled={busy}
            onClick={onDelete}
          >
            {busy ? <Loader2Icon className="animate-spin" /> : <Trash2Icon />}
          </Button>
        </div>
      )}
    </div>
  )
}

function StatusBadge({ radio }: { radio: RadioDto }) {
  if (!radio.active) return <Badge variant="outline">Pasif</Badge>
  // streaming null ise MediaMTX'e ulaşılamamış demektir — "akmıyor" demek
  // yanıltıcı olurdu, radyo pekâlâ yayında olabilir. Bu yüzden hata değil
  // uyarı rengi: bilinmezlik, arızadan farklı bir durum.
  if (radio.streaming === null) return <Badge variant="warning">Bilinmiyor</Badge>
  if (!radio.streaming) return <Badge variant="error">Akmıyor</Badge>
  return (
    <Badge variant="success" className="gap-1.5">
      <span className="size-1.5 animate-pulse rounded-full bg-status-success" />
      Yayında
    </Badge>
  )
}
