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
  Loader2Icon,
  PencilIcon,
  PlusIcon,
  RefreshCwIcon,
  Trash2Icon,
} from 'lucide-react'
import { ChannelFormDialog } from './channels/ChannelFormDialog'

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
  const [pending, setPending] = useState<Set<string>>(new Set())

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

  async function remove(channel: ChannelDto) {
    if (
      !confirm(
        `"${channel.name}" silinecek ve MediaMTX'teki yayın durdurulacak. Emin misiniz?`,
      )
    ) {
      return
    }
    setPending((prev) => new Set(prev).add(channel.id))
    try {
      await channelsApi.remove(channel.id)
      setChannels((prev) => prev.filter((c) => c.id !== channel.id))
      toast.success(`${channel.name} silindi.`)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Kanal silinemedi.')
    } finally {
      setPending((prev) => {
        const next = new Set(prev)
        next.delete(channel.id)
        return next
      })
    }
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

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-semibold">Kanallar</h1>
            {capacity && (
              <Badge
                // Kapasite dolduğunda yeni aktif kanal reddedilecek; göstergeyi
                // önceden uyarı verecek şekilde renklendiriyoruz.
                variant={capacity.active >= capacity.max ? 'destructive' : 'secondary'}
                title="Aynı anda yayında olabilecek kanal sayısı"
              >
                {capacity.active} / {capacity.max} yayında
              </Badge>
            )}
          </div>
          <p className="text-sm text-muted-foreground">
            Durum MediaMTX'ten okunur, {REFRESH_MS / 1000} saniyede bir tazelenir.
          </p>
        </div>

        {canManage && (
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={() => void restore()}
              title="Aktif kanalları MediaMTX'e yeniden yaz — MediaMTX bağımsız yeniden başlatıldığında gerekir"
            >
              <RefreshCwIcon />
              MediaMTX'e yeniden yaz
            </Button>
            <Button onClick={openCreate}>
              <PlusIcon />
              Yeni kanal
            </Button>
          </div>
        )}
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div className="rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Kanal</TableHead>
                <TableHead>Kaynak</TableHead>
                <TableHead>Path</TableHead>
                <TableHead>Durum</TableHead>
                <TableHead>DVR</TableHead>
                <TableHead>Kalite</TableHead>
                <TableHead>İzleyici</TableHead>
                <TableHead>Ekleyen</TableHead>
                <TableHead className="text-right">İşlem</TableHead>
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

              {!loading &&
                channels.map((channel) => {
                  const busy = pending.has(channel.id)
                  return (
                    <TableRow key={channel.id}>
                      <TableCell className="font-medium">{channel.name}</TableCell>
                      <TableCell
                        className="max-w-[16rem] truncate text-muted-foreground"
                        title={channel.sourceUrl}
                      >
                        {channel.sourceUrl}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {channel.mediamtxPath}
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
                        {channel.viewers ?? '—'}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {channel.createdBy ?? '—'}
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="icon"
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
                                disabled={busy}
                                title="Düzenle"
                                onClick={() => openEdit(channel)}
                              >
                                <PencilIcon />
                              </Button>
                              <Button
                                variant="ghost"
                                size="icon"
                                disabled={busy}
                                title="Sil"
                                onClick={() => void remove(channel)}
                              >
                                <Trash2Icon className="text-destructive" />
                              </Button>
                            </>
                          )}
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
    </div>
  )
}
