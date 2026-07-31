import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi } from '@/api/endpoints'
import type { ChannelDto } from '@/api/types'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2Icon } from 'lucide-react'

/** Backend'in mediamtx_path için uyguladığı kural; aynısını burada da sınıyoruz. */
const PATH_PATTERN = /^[A-Za-z0-9_-]+$/

const EMPTY = { name: '', sourceUrl: '', mediamtxPath: '', active: true, dvrEnabled: false }

/**
 * Kanal ekleme ve düzenleme.
 *
 * @param channel null ise yeni kanal, doluysa düzenleme.
 * @param open    dialog görünürlüğü; kapanışta form sıfırlanır.
 */
export function ChannelFormDialog({
  channel,
  open,
  onOpenChange,
  onSaved,
}: {
  channel: ChannelDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}) {
  const [form, setForm] = useState(EMPTY)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Dialog her açıldığında düzenlenen kanalın değerleriyle doldurulur;
  // aksi halde bir önceki kaydın verisi formda kalırdı.
  useEffect(() => {
    if (!open) return
    setError(null)
    setForm(
      channel
        ? {
            name: channel.name,
            sourceUrl: channel.sourceUrl,
            mediamtxPath: channel.mediamtxPath,
            active: channel.active,
            dvrEnabled: channel.dvrEnabled,
          }
        : EMPTY,
    )
  }, [open, channel])

  function set<K extends keyof typeof EMPTY>(key: K, value: (typeof EMPTY)[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()

    const payload = {
      name: form.name.trim(),
      sourceUrl: form.sourceUrl.trim(),
      mediamtxPath: form.mediamtxPath.trim(),
      active: form.active,
      dvrEnabled: form.dvrEnabled,
    }

    if (!PATH_PATTERN.test(payload.mediamtxPath)) {
      setError('Path yalnızca harf, rakam, alt çizgi ve tire içerebilir.')
      return
    }

    setError(null)
    setBusy(true)
    try {
      if (channel) {
        await channelsApi.update(channel.id, payload)
        toast.success(`${payload.name} güncellendi.`)
      } else {
        await channelsApi.create(payload)
        toast.success(`${payload.name} oluşturuldu.`)
      }
      onOpenChange(false)
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Kanal kaydedilemedi.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{channel ? 'Kanalı düzenle' : 'Yeni kanal'}</DialogTitle>
          <DialogDescription>
            Kanal aktifken MediaMTX'e yazılır ve kaynaktan yayın çekilmeye başlanır.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="name">Kanal adı</Label>
            <Input
              id="name"
              required
              maxLength={128}
              value={form.name}
              onChange={(e) => set('name', e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="sourceUrl">Kaynak adresi</Label>
            <Input
              id="sourceUrl"
              required
              maxLength={512}
              placeholder="https://ornek.com/yayin/master.m3u8"
              value={form.sourceUrl}
              onChange={(e) => set('sourceUrl', e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              HLS, RTSP, RTMP, SRT veya UDP adresi olabilir.
            </p>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="mediamtxPath">MediaMTX path</Label>
            <Input
              id="mediamtxPath"
              required
              maxLength={128}
              placeholder="kanal1"
              value={form.mediamtxPath}
              onChange={(e) => set('mediamtxPath', e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              HLS adresi bundan türer:{' '}
              <code>…:8888/{form.mediamtxPath || 'path'}/index.m3u8</code>
            </p>
            {channel && channel.mediamtxPath !== form.mediamtxPath.trim() && (
              // Path değişimi MediaMTX'te eski path'in silinip yenisinin
              // kurulmasına yol açıyor; izleyicilerin adresi değişir.
              <p className="text-xs text-destructive">
                Path değişiyor — mevcut izleyicilerin yayın adresi geçersiz olacak.
              </p>
            )}
          </div>

          <div className="flex flex-col gap-2 rounded-lg border p-3">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(e) => set('active', e.target.checked)}
              />
              Yayında olsun
            </label>

            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={form.dvrEnabled}
                onChange={(e) => set('dvrEnabled', e.target.checked)}
              />
              Geriye sarma kaydı (DVR)
            </label>
            <p className="text-xs text-muted-foreground">
              7 gün geriye dönük kayıt tutulur. 6 Mbps'lik bir kanal haftada
              ~454 GB yer kaplar — disk kapasitesini hesaba katın.
            </p>
          </div>

          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Vazgeç
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2Icon className="animate-spin" />}
              Kaydet
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
