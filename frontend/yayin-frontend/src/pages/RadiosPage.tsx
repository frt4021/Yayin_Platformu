import { useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { radiosApi } from '@/api/endpoints'
import type { RadioDto } from '@/api/types'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
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
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { RADIOS_TOUR_STEPS, RADIOS_TOUR_SEEN_KEY } from '@/components/tour/radiosSteps'

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
  const tur = usePageTour(RADIOS_TOUR_SEEN_KEY)

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
      <div data-tour="radyo-arama" className="relative max-w-xl">
        <SearchIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <input
          type="search"
          placeholder="İstasyon ara…"
          aria-label="İstasyon ara"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="h-11 w-full rounded-full border bg-card pl-11 pr-4 text-sm
                     placeholder:text-muted-foreground focus:outline-none
                     focus:ring-2 focus:ring-[var(--ring)]"
        />
      </div>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-3xl font-semibold tracking-tight">Radyolar</h1>
            <TourTrigger onClick={tur.start} />
            {capacity && (
              <Badge variant="secondary">
                {capacity.active} / {capacity.max} yayında
              </Badge>
            )}
          </div>
          <p className="text-sm text-muted-foreground">Canlı yayınlar ve küratörlü istasyonlar.</p>
        </div>

        {canManage && (
          <div className="flex gap-2">
            <Button
              data-tour="radyo-geri-yukle"
              variant="outline"
              size="icon"
              className="rounded-full"
              onClick={() => void restore()}
              title="Aktif radyoları MediaMTX'e yeniden yaz"
            >
              <RefreshCwIcon />
            </Button>
            <Button
              data-tour="radyo-ekle"
              className="rounded-full"
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <PlusIcon />
              Yeni radyo
            </Button>
          </div>
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
        <div data-tour="radyo-liste" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
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

      <GuidedTour open={tur.open} onClose={tur.close} steps={RADIOS_TOUR_STEPS} />
    </div>
  )
}

/** Yalnızca köprü modunda anlamlı olan bit hızını okunur biçime çevirir. */
function ikinciSatir(radio: RadioDto): string {
  if (radio.sourceKind === 'KOPRU' && radio.bitrate) {
    return radio.bitrate.replace(/k$/i, ' kbps')
  }
  return radio.mediamtxPath
}

/** "Çalıyor" göstergesi — gerçek bir ses seviyesi yok, sabit bir animasyon. */
function PlayingBars({ className }: { className?: string }) {
  return (
    <span className={cn('eq-bars flex items-end gap-0.5', className)} aria-hidden>
      <span className="h-2 w-0.5 rounded-full bg-white" />
      <span className="h-3.5 w-0.5 rounded-full bg-white" />
      <span className="h-2.5 w-0.5 rounded-full bg-white" />
    </span>
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
        'group flex flex-col overflow-hidden rounded-2xl border bg-card p-4 transition-[border-color,box-shadow,transform] duration-200',
        selected
          ? 'border-primary shadow-[0_0_0_1px_var(--primary)]'
          : 'hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-[0_8px_30px_-12px_var(--primary)] motion-reduce:transition-none motion-reduce:hover:translate-y-0',
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="relative shrink-0">
          <Logo radio={radio} className="size-14 rounded-2xl text-base" />
          {playing && (
            <div className="absolute inset-0 grid place-items-center rounded-2xl bg-black/55">
              <PlayingBars />
            </div>
          )}
        </div>
        <StatusBadge radio={radio} />
      </div>

      <div className="mt-3 min-w-0">
        <div className="truncate font-semibold">{radio.name}</div>
        <div data-tour="radyo-durum" className="mt-0.5 truncate text-xs text-muted-foreground">
          {ikinciSatir(radio)}
        </div>
      </div>

      <div className="mt-3 flex items-center justify-between gap-2 border-t pt-3">
        <span className="flex min-w-0 items-center gap-1 truncate text-xs text-muted-foreground">
          <AudioLinesIcon className="size-3.5 shrink-0" />
          {radio.listeners != null && radio.listeners > 0
            ? `${radio.listeners} dinleyici`
            : 'dinleyici yok'}
        </span>

        <div className="flex shrink-0 items-center gap-1">
          {canManage && (
            <>
              <Button
                variant="ghost"
                size="icon"
                className="size-7 rounded-full opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100"
                title="Düzenle"
                onClick={onEdit}
              >
                <PencilIcon className="size-3.5" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="size-7 rounded-full opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100"
                title="Sil"
                disabled={busy}
                onClick={onDelete}
              >
                {busy ? <Loader2Icon className="size-3.5 animate-spin" /> : <Trash2Icon className="size-3.5" />}
              </Button>
            </>
          )}
          <button
            type="button"
            disabled={!playable}
            onClick={onPlay}
            title={!playable ? 'Radyo pasif' : playing ? 'Duraklat' : 'Dinle'}
            className={cn(
              'grid size-9 shrink-0 place-items-center rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-40',
              playing
                ? 'bg-primary text-primary-foreground shadow-[0_4px_14px_-4px_var(--primary)]'
                : 'bg-secondary text-foreground hover:bg-accent',
            )}
          >
            {playing ? <PauseIcon className="size-4" /> : <PlayIcon className="size-4 fill-current" />}
          </button>
        </div>
      </div>
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
