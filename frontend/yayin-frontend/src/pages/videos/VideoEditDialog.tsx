import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { videosApi } from '@/api/endpoints'
import { formatDuration } from '@/api/upload'
import type { VideoDto } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ImageIcon, Loader2Icon } from 'lucide-react'

/** Küçük resmin nereden geldiği — üç durum, ikisi alandan türetiliyor. */
function thumbnailSource(video: VideoDto | null): string {
  if (!video) return '—'
  if (video.thumbnailIsUpload) return 'yüklenen görsel'
  if (video.thumbnailAtSeconds != null) return `${video.thumbnailAtSeconds}. saniye`
  return 'otomatik seçilen kare'
}

/**
 * Video düzenleme.
 *
 * <p>Yalnızca kullanıcının sahip olduğu alanlar: başlık, açıklama ve küçük
 * resmin alınacağı an. Süre, çözünürlük ve boyut işçinin dosyayı okuyarak
 * tespit ettiği gerçekler — burada değiştirilemez.
 */
export function VideoEditDialog({
  video,
  open,
  onOpenChange,
  onSaved,
}: {
  video: VideoDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [thumbAt, setThumbAt] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [uploading, setUploading] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  /**
   * Görsel yükleme kaydetmeyi BEKLEMİYOR: ayrı bir uç ve anında etkili.
   * Forma bağlansaydı kullanıcı görseli seçip vazgeçtiğinde ne olacağı
   * belirsiz kalırdı.
   */
  async function onPickImage(file: File | null) {
    if (!file || !video) return
    if (file.size > 2 * 1024 * 1024) {
      setError('Görsel en fazla 2 MB olabilir.')
      return
    }
    setError(null)
    setUploading(true)
    try {
      await videosApi.uploadThumbnail(video.id, file)
      toast.success('Küçük resim güncellendi.')
      setThumbAt('')
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Görsel yüklenemedi.')
    } finally {
      setUploading(false)
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  useEffect(() => {
    if (!open || !video) return
    setError(null)
    setTitle(video.title)
    setDescription(video.description ?? '')
    setThumbAt(video.thumbnailAtSeconds == null ? '' : String(video.thumbnailAtSeconds))
  }, [open, video])

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!video) return

    const seconds = thumbAt.trim() === '' ? null : Number(thumbAt)
    if (seconds !== null && (!Number.isFinite(seconds) || seconds < 0)) {
      setError('Kare anı sıfır veya daha büyük bir sayı olmalı.')
      return
    }
    if (seconds !== null && video.durationSeconds != null && seconds >= video.durationSeconds) {
      setError(
        `Seçilen an videonun süresinden uzun: ${seconds} sn / ${video.durationSeconds} sn.`,
      )
      return
    }

    setError(null)
    setBusy(true)
    try {
      await videosApi.update(video.id, {
        title: title.trim(),
        description: description.trim(),
        thumbnailAtSeconds: seconds,
      })
      const changed = seconds !== null && seconds !== video.thumbnailAtSeconds
      toast.success(
        changed ? `${title.trim()} güncellendi, küçük resim yenileniyor.` : `${title.trim()} güncellendi.`,
      )
      onOpenChange(false)
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Video güncellenemedi.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Videoyu düzenle</DialogTitle>
          <DialogDescription>
            Dosyanın kendisi değiştirilemez; yeni bir dosya için yeni kayıt açın.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="flex min-h-0 flex-1 flex-col gap-4">
          <DialogBody className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="editTitle">Başlık</Label>
              <Input
                id="editTitle"
                required
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="editDesc">Açıklama</Label>
              <textarea
                id="editDesc"
                rows={4}
                maxLength={5000}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="rounded-md border bg-input-bg px-3 py-2 text-sm"
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="editThumb">Küçük resim anı (saniye)</Label>
              <div className="flex items-center gap-2">
                <Input
                  id="editThumb"
                  inputMode="numeric"
                  placeholder="otomatik"
                  value={thumbAt}
                  onChange={(e) => setThumbAt(e.target.value)}
                  className="w-32"
                />
                {video?.durationSeconds != null && (
                  <span className="text-xs text-muted-foreground">
                    süre: {formatDuration(video.durationSeconds)}
                  </span>
                )}
              </div>
              <p className="text-xs text-muted-foreground">
                Boş bırakılırsa mevcut küçük resme dokunulmaz. Değiştirilirse kare
                yeniden üretilir — video bu sırada kısa süre "İşleniyor" görünür.
                Otomatik seçim sürenin %10'unu, en az 3. saniyeyi alıyor; videoların
                ilk saniyeleri sıklıkla siyah oluyor.
              </p>
            </div>

            <div className="flex flex-col gap-2 rounded-lg border p-3">
              <div className="flex items-center justify-between gap-2">
                <Label>Mevcut küçük resim</Label>
                <Badge variant="outline">{thumbnailSource(video)}</Badge>
              </div>

              {video?.thumbnailUrl ? (
                <img
                  src={video.thumbnailUrl}
                  alt=""
                  className="w-48 rounded-md border object-cover"
                />
              ) : (
                <p className="text-xs text-muted-foreground">Henüz üretilmedi.</p>
              )}

              <Label htmlFor="thumbFile" className="mt-1">
                <span className="sr-only">Görsel yükle</span>
              </Label>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={uploading}
                  onClick={() => fileRef.current?.click()}
                >
                  {uploading ? <Loader2Icon className="animate-spin" /> : <ImageIcon />}
                  Görsel yükle
                </Button>
                <span className="text-xs text-muted-foreground">
                  JPEG, PNG veya WebP · en fazla 2 MB
                </span>
              </div>
              <input
                id="thumbFile"
                ref={fileRef}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                className="hidden"
                onChange={(e) => void onPickImage(e.target.files?.[0] ?? null)}
              />
              <p className="text-xs text-muted-foreground">
                Videodaki hiçbir kare uygun değilse kendi görselinizi yükleyin.
                Yüklenen görsel kalıcıdır — kare anı temizlenir ve işçi onu
                yeniden üretmeye çalışmaz.
              </p>
            </div>
          </DialogBody>

          {error && (
            <p role="alert" className="text-sm text-status-error">
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
