import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi } from '@/api/endpoints'
import type { Capacity, ChannelDto } from '@/api/types'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  CopyIcon,
  EyeIcon,
  Loader2Icon,
  PencilIcon,
  PlusIcon,
  RadioTowerIcon,
  RefreshCwIcon,
  SearchIcon,
  Trash2Icon,
} from 'lucide-react'
import { ChannelFormDialog } from './channels/ChannelFormDialog'
import { DeleteChannelDialog } from './channels/DeleteChannelDialog'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { CHANNELS_TOUR_STEPS, CHANNELS_TOUR_SEEN_KEY } from '@/components/tour/channelsSteps'

/** Durum bilgisi MediaMTX'ten anlık okunuyor; tazelenmezse gösterge gerçeklikle ilgisini kaybeder. */
const REFRESH_MS = 15000

function statusBadge(channel: ChannelDto) {
  if (!channel.active) return <Badge variant="outline">Pasif</Badge>
  // streaming null ise MediaMTX'e ulaşılamamış demektir — "akmıyor" demek
  // yanıltıcı olurdu, kanal pekâlâ yayında olabilir. Bilinmezlik arızadan
  // farklı bir durum, o yüzden uyarı rengi.
  if (channel.streaming === null) return <Badge variant="warning">Bilinmiyor</Badge>
  if (!channel.streaming) return <Badge variant="error">Akmıyor</Badge>
  return (
    <Badge variant="success" className="gap-1.5">
      <span className="size-1.5 animate-pulse rounded-full bg-status-success" />
      Yayında
    </Badge>
  )
}

export function ChannelsPage() {
  const { hasRole } = useAuth()
  const canManage = hasRole('Yönetici', 'Moderatör')

  const [channels, setChannels] = useState<ChannelDto[]>([])
  const [capacity, setCapacity] = useState<Capacity | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<ChannelDto | null>(null)
  const [pending] = useState<Set<string>>(new Set())
  /** Silme onayı bekleyen kanal; null ise iletişim kutusu kapalı. */
  const [silinecek, setSilinecek] = useState<ChannelDto | null>(null)
  const tur = usePageTour(CHANNELS_TOUR_SEEN_KEY)

  /** Yalnızca istemcide süzülüyor — liste zaten tamamen belleğe alınmış durumda. */
  const [arama, setArama] = useState('')

  const load = useCallback(async () => {
    try {
      const [list, cap] = await Promise.all([channelsApi.list(), channelsApi.capacity()])
      setChannels(list)
      setCapacity(cap)
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Kanallar yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), REFRESH_MS)
    return () => clearInterval(timer)
  }, [load])

  function openCreate() {
    setEditing(null)
    setFormOpen(true)
  }

  function openEdit(channel: ChannelDto) {
    setEditing(channel)
    setFormOpen(true)
  }

  /**
   * Silme artık iletişim kutusundan geçiyor.
   *
   * <p>Tarayıcının {@code confirm()}'i neyin gideceğini gösteremiyordu ve
   * ne içerik seçimi ne şifre alanı taşıyabiliyordu.
   */
  function remove(channel: ChannelDto) {
    setSilinecek(channel)
  }

  function silindi(channelId: string) {
    setChannels((prev) => prev.filter((c) => c.id !== channelId))
  }

  async function restore() {
    try {
      const result = await channelsApi.restore()
      toast.success(`${result.restored} kanal MediaMTX'e yeniden yazıldı.`)
      await load()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Yeniden yükleme başarısız.')
    }
  }

  async function copyHls(channel: ChannelDto) {
    try {
      await navigator.clipboard.writeText(channel.hlsUrl)
      toast.success('HLS adresi kopyalandı.')
    } catch {
      // Güvenli olmayan bağlamda (http + localhost dışı) clipboard API kapalıdır.
      toast.error(channel.hlsUrl, { description: 'Kopyalanamadı, adresi elle alın.' })
    }
  }

  const q = arama.trim().toLocaleLowerCase('tr')
  const gorunenler = channels.filter(
    (c) =>
      !q ||
      c.name.toLocaleLowerCase('tr').includes(q) ||
      c.mediamtxPath.toLowerCase().includes(q) ||
      c.sourceUrl.toLowerCase().includes(q),
  )

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-3xl font-semibold tracking-tight">Kanallar</h1>
            <TourTrigger onClick={tur.start} />
            {capacity && (
              <Badge
                data-tour="kanal-kapasite"
                // Kapasite dolduğunda yeni aktif kanal reddedilecek; göstergeyi
                // önceden uyarı verecek şekilde renklendiriyoruz.
                variant={capacity.active >= capacity.max ? 'destructive' : 'secondary'}
                title="Aynı anda yayında olabilecek kanal sayısı"
              >
                {capacity.active} / {capacity.max} yayında
              </Badge>
            )}
          </div>
          <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <RefreshCwIcon className="size-3.5" />
            Durum MediaMTX'ten okunur, {REFRESH_MS / 1000} saniyede bir tazelenir.
          </p>
        </div>

        {canManage && (
          <div className="flex gap-2">
            <Button
              data-tour="kanal-yeniden-yaz"
              variant="outline"
              onClick={() => void restore()}
              title="Aktif kanalları MediaMTX'e yeniden yaz — MediaMTX bağımsız yeniden başlatıldığında gerekir"
            >
              <RefreshCwIcon />
              MediaMTX'e yeniden yaz
            </Button>
            <Button data-tour="kanal-ekle" onClick={openCreate}>
              <PlusIcon />
              Yeni kanal
            </Button>
          </div>
        )}
      </div>

      <div className="relative max-w-sm">
        <SearchIcon className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <input
          type="search"
          value={arama}
          onChange={(e) => setArama(e.target.value)}
          placeholder="Kanal, path ya da kaynak ara…"
          aria-label="Kanal ara"
          className="h-10 w-full rounded-full border bg-card pl-10 pr-4 text-sm
                     placeholder:text-muted-foreground focus:outline-none
                     focus:ring-2 focus:ring-[var(--ring)]"
        />
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div data-tour="kanal-tablo" className="rounded-2xl border bg-panel shadow-sm">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="uppercase tracking-wide text-[11px]">Kanal</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Kaynak</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Path</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Durum</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">DVR</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Kalite</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">İzleyici</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Ekleyen</TableHead>
                <TableHead className="text-right uppercase tracking-wide text-[11px]">İşlem</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={9} className="py-8 text-center text-muted-foreground">
                    <Loader2Icon className="mx-auto animate-spin" />
                  </TableCell>
                </TableRow>
              )}

              {!loading && channels.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} className="py-8 text-center text-muted-foreground">
                    {canManage ? 'Henüz kanal yok — “Yeni kanal” ile ekleyin.' : 'Henüz kanal yok.'}
                  </TableCell>
                </TableRow>
              )}

              {!loading && channels.length > 0 && gorunenler.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} className="py-8 text-center text-muted-foreground">
                    Arama sonucu bulunamadı.
                  </TableCell>
                </TableRow>
              )}

              {!loading &&
                gorunenler.map((channel) => {
                  const busy = pending.has(channel.id)
                  return (
                    <TableRow key={channel.id}>
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2.5">
                          <span className="grid size-8 shrink-0 place-items-center rounded-full bg-secondary text-muted-foreground">
                            <RadioTowerIcon className="size-4" />
                          </span>
                          {channel.name}
                        </div>
                      </TableCell>
                      <TableCell
                        className="max-w-[16rem] truncate font-mono text-xs text-muted-foreground"
                        title={channel.sourceUrl}
                      >
                        {channel.sourceUrl}
                      </TableCell>
                      <TableCell>
                        <code className="rounded bg-secondary/60 px-1.5 py-0.5 font-mono text-xs text-muted-foreground">
                          {channel.mediamtxPath}
                        </code>
                      </TableCell>
                      <TableCell>{statusBadge(channel)}</TableCell>
                      <TableCell>
                        {channel.dvrEnabled ? (
                          <Badge variant="secondary">Kayıtta</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </TableCell>
                      <TableCell>
                        {channel.renditions ? (
                          <div className="flex flex-wrap gap-1">
                            {channel.renditions.split(',').map((r) => (
                              <Badge key={r} variant="outline">
                                {r.split('|')[0]}
                              </Badge>
                            ))}
                          </div>
                        ) : (
                          <span
                            className="text-muted-foreground"
                            title="Transcode yok — kaynağın çözünürlüğü olduğu gibi dağıtılıyor"
                          >
                            kaynak
                          </span>
                        )}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {channel.viewers != null ? (
                          <span className="inline-flex items-center gap-1">
                            <EyeIcon className="size-3.5" />
                            {channel.viewers}
                          </span>
                        ) : (
                          '—'
                        )}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {channel.createdBy ?? '—'}
                      </TableCell>
                      <TableCell>
                        <div data-tour="kanal-islemler" className="flex justify-end">
                          <div className="inline-flex items-center gap-0.5 rounded-full bg-secondary/40 p-0.5">
                            <Button
                              variant="ghost"
                              size="icon"
                              className="rounded-full"
                              title="HLS adresini kopyala"
                              onClick={() => void copyHls(channel)}
                            >
                              <CopyIcon />
                            </Button>
                            {canManage && (
                              <>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  className="rounded-full"
                                  disabled={busy}
                                  title="Düzenle"
                                  onClick={() => openEdit(channel)}
                                >
                                  <PencilIcon />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  className="rounded-full"
                                  disabled={busy}
                                  title="Sil"
                                  onClick={() => void remove(channel)}
                                >
                                  <Trash2Icon className="text-destructive" />
                                </Button>
                              </>
                            )}
                          </div>
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
            </TableBody>
          </Table>
        </div>
      )}

      <ChannelFormDialog
        channel={editing}
        open={formOpen}
        onOpenChange={setFormOpen}
        onSaved={() => void load()}
      />

      <DeleteChannelDialog
        channel={silinecek}
        onClose={() => setSilinecek(null)}
        onDeleted={silindi}
      />

      <GuidedTour open={tur.open} onClose={tur.close} steps={CHANNELS_TOUR_STEPS} />
    </div>
  )
}
