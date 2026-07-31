import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { clipsApi } from '@/api/endpoints'
import type { ClipDto, ClipStatus } from '@/api/types'
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
import { DownloadIcon, Loader2Icon, PlayIcon, Trash2Icon } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

/** İş devam ederken sık, bittiğinde seyrek tazeleme. */
const POLL_ACTIVE_MS = 3000
const POLL_IDLE_MS = 30000

function statusBadge(status: ClipStatus) {
  switch (status) {
    case 'HAZIR':
      return <Badge>Hazır</Badge>
    case 'ISLENIYOR':
      return (
        <Badge variant="secondary" className="gap-1">
          <Loader2Icon className="size-3 animate-spin" />
          İşleniyor
        </Badge>
      )
    case 'BEKLIYOR':
      return <Badge variant="outline">Kuyrukta</Badge>
    case 'HATA':
      return <Badge variant="destructive">Hata</Badge>
  }
}

function formatSize(bytes: number | null) {
  if (bytes === null) return '—'
  const mb = bytes / 1024 / 1024
  return mb > 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(1)} MB`
}

function formatDuration(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return h > 0 ? `${h}sa ${m}dk` : m > 0 ? `${m}dk ${s}sn` : `${s}sn`
}

export function ClipsPage() {
  const [clips, setClips] = useState<ClipDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [watching, setWatching] = useState<{ clip: ClipDto; url: string | null } | null>(null)

  const load = useCallback(async () => {
    try {
      setClips(await clipsApi.list())
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Klipler yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  // Devam eden iş varsa hızlı tazele; yoksa boşuna istek atma.
  useEffect(() => {
    void load()
    const active = clips.some((c) => c.status === 'BEKLIYOR' || c.status === 'ISLENIYOR')
    const timer = setInterval(() => void load(), active ? POLL_ACTIVE_MS : POLL_IDLE_MS)
    return () => clearInterval(timer)
    // clips.length ve durum bileşimi değişince aralık yeniden hesaplanmalı.
  }, [load, clips.map((c) => c.status).join(',')])

  /**
   * İndirme. Adres backend'den JSON olarak alınıyor: uç token gerektiriyor,
   * <a href> ile doğrudan açılamaz çünkü tarayıcı Authorization başlığı
   * göndermez. Yönlendirme de işe yaramıyor — CORS Location başlığını gizliyor.
   */
  async function download(clip: ClipDto) {
    try {
      const { download: url } = await clipsApi.links(clip.id)
      // Gizli bir bağlantıya tıklamak, window.location'dan farklı olarak
      // sayfadan ayrılmadan indirmeyi başlatır.
      const a = document.createElement('a')
      a.href = url
      a.rel = 'noopener'
      document.body.appendChild(a)
      a.click()
      a.remove()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'İndirme adresi alınamadı.')
    }
  }

  async function watch(clip: ClipDto) {
    setWatching({ clip, url: null })
    try {
      const { stream } = await clipsApi.links(clip.id)
      setWatching({ clip, url: stream })
    } catch (e) {
      setWatching(null)
      toast.error(e instanceof ApiError ? e.message : 'İzleme adresi alınamadı.')
    }
  }

  async function remove(clip: ClipDto) {
    if (!confirm(`"${clip.channelName}" klibi silinecek. Emin misiniz?`)) return
    try {
      await clipsApi.remove(clip.id)
      setClips((prev) => prev.filter((c) => c.id !== clip.id))
      toast.success('Klip silindi.')
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Klip silinemedi.')
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-xl font-semibold">Klipler</h1>
        <p className="text-sm text-muted-foreground">
          Klipler arka planda üretilir; hazır olunca burada izlenip indirilebilir.
        </p>
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
                <TableHead>Aralık</TableHead>
                <TableHead>Süre</TableHead>
                <TableHead>Durum</TableHead>
                <TableHead>Boyut</TableHead>
                <TableHead>İsteyen</TableHead>
                <TableHead className="text-right">İşlem</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                    <Loader2Icon className="mx-auto animate-spin" />
                  </TableCell>
                </TableRow>
              )}

              {!loading && clips.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                    Henüz klip yok. Geriye sarma sayfasından aralık seçip oluşturun.
                  </TableCell>
                </TableRow>
              )}

              {!loading &&
                clips.map((clip) => (
                  <TableRow key={clip.id}>
                    <TableCell className="font-medium">{clip.channelName}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {new Date(clip.start).toLocaleString('tr-TR')}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatDuration(clip.durationSeconds)}
                    </TableCell>
                    <TableCell>
                      {statusBadge(clip.status)}
                      {clip.error && (
                        <p className="mt-1 max-w-xs text-xs text-destructive">{clip.error}</p>
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {formatSize(clip.sizeBytes)}
                    </TableCell>
                    <TableCell className="text-muted-foreground">{clip.requestedBy}</TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          disabled={clip.status !== 'HAZIR'}
                          title={clip.status === 'HAZIR' ? 'İzle' : 'Klip henüz hazır değil'}
                          onClick={() => void watch(clip)}
                        >
                          <PlayIcon />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          disabled={clip.status !== 'HAZIR'}
                          title={clip.status === 'HAZIR' ? 'İndir' : 'Klip henüz hazır değil'}
                          onClick={() => void download(clip)}
                        >
                          <DownloadIcon />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          disabled={clip.status === 'ISLENIYOR'}
                          title={
                            clip.status === 'ISLENIYOR'
                              ? 'İşlenmekte olan klip silinemez'
                              : 'Sil'
                          }
                          onClick={() => void remove(clip)}
                        >
                          <Trash2Icon className="text-destructive" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={watching !== null} onOpenChange={(open) => !open && setWatching(null)}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>{watching?.clip.channelName}</DialogTitle>
            <DialogDescription>
              {watching && new Date(watching.clip.start).toLocaleString('tr-TR')} ·{' '}
              {watching && formatDuration(watching.clip.durationSeconds)}
            </DialogDescription>
          </DialogHeader>

          {watching?.url ? (
            // key: adres degisince <video> yeniden kurulsun, onceki klip
            // acik kalmasin.
            <video
              key={watching.url}
              src={watching.url}
              controls
              autoPlay
              className="aspect-video w-full rounded-lg bg-black"
            />
          ) : (
            <div className="grid aspect-video w-full place-items-center rounded-lg bg-black">
              <Loader2Icon className="animate-spin text-muted-foreground" />
            </div>
          )}

          {watching?.url && (
            <Button variant="outline" onClick={() => void download(watching.clip)}>
              <DownloadIcon />
              İndir
            </Button>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
