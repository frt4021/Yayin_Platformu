import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { videosApi } from '@/api/endpoints'
import { formatBytes, uploadToStorage, type UploadHandle } from '@/api/upload'
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
import { FileVideoIcon, ImageIcon, Loader2Icon, UploadIcon } from 'lucide-react'

/** Backend'in kabul ettiği uzantılar (videos.allowed-extensions ile aynı). */
const ACCEPTED = ['mp4', 'webm', 'mov', 'm4v']

/** Backend sınırı 5 GB; burada da denetlemek 5 GB'ı boşuna yüklemeyi önlüyor. */
const MAX_BYTES = 5 * 1024 ** 3

/** Küçük resim backend üzerinden geçtiği için sınır dar (backend ile aynı). */
const MAX_THUMB_BYTES = 2 * 1024 ** 2
const THUMB_TYPES = ['image/jpeg', 'image/png', 'image/webp']

type Phase = 'secim' | 'yukleniyor' | 'tamamlaniyor'

/**
 * Video yükleme.
 *
 * <p>Üç adımlı ve <b>dosya backend'den geçmiyor</b>:
 * <ol>
 *   <li>{@code POST /api/videos} — kayıt açılır, imzalı adres alınır</li>
 *   <li>{@code PUT <imzalı adres>} — dosya doğrudan nesne depolamasına gider</li>
 *   <li>{@code POST /api/videos/{id}/tamamlandi} — backend depolamadan doğrular</li>
 * </ol>
 *
 * <p>Üçüncü adım ulaşmazsa (sekme kapanır, ağ koparsa) süpürücü aynı işi
 * yapıyor; yani bu çağrı bir hızlandırma, doğruluk kaynağı değil.
 */
export function VideoUploadDialog({
  open,
  onOpenChange,
  onUploaded,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onUploaded: () => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [thumb, setThumb] = useState<File | null>(null)
  const [thumbPreview, setThumbPreview] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('secim')
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const handleRef = useRef<UploadHandle | null>(null)
  const thumbInputRef = useRef<HTMLInputElement>(null)

  // Onizleme icin uretilen blob adresi serbest birakilmazsa her secimde
  // bellekte bir kopya birikir.
  useEffect(() => {
    return () => {
      if (thumbPreview) URL.revokeObjectURL(thumbPreview)
    }
  }, [thumbPreview])

  function reset() {
    setFile(null)
    setTitle('')
    setDescription('')
    if (thumbPreview) URL.revokeObjectURL(thumbPreview)
    setThumb(null)
    setThumbPreview(null)
    setPhase('secim')
    setProgress(0)
    setError(null)
    handleRef.current = null
  }

  function pickThumb(selected: File | null) {
    setError(null)
    if (thumbPreview) URL.revokeObjectURL(thumbPreview)
    if (!selected) {
      setThumb(null)
      setThumbPreview(null)
      return
    }
    if (!THUMB_TYPES.includes(selected.type)) {
      setError('Küçük resim JPEG, PNG veya WebP olmalı.')
      setThumb(null)
      setThumbPreview(null)
      return
    }
    if (selected.size > MAX_THUMB_BYTES) {
      setError(`Küçük resim en fazla ${formatBytes(MAX_THUMB_BYTES)} olabilir.`)
      setThumb(null)
      setThumbPreview(null)
      return
    }
    setThumb(selected)
    setThumbPreview(URL.createObjectURL(selected))
  }

  function close(next: boolean) {
    if (!next && phase !== 'secim') {
      // Yukleme suruyor: kullaniciya sormadan kapatmak dosyayi yarida keserdi.
      if (!confirm('Yükleme sürüyor. İptal edilsin mi?')) return
      handleRef.current?.abort()
    }
    if (!next) reset()
    onOpenChange(next)
  }

  function pick(selected: File | null) {
    setError(null)
    if (!selected) {
      setFile(null)
      return
    }
    const ext = selected.name.split('.').pop()?.toLowerCase() ?? ''
    if (!ACCEPTED.includes(ext)) {
      setError(`Desteklenmeyen dosya türü: .${ext}. Kullanılabilir: ${ACCEPTED.join(', ')}`)
      setFile(null)
      return
    }
    if (selected.size > MAX_BYTES) {
      setError(
        `Dosya en fazla ${formatBytes(MAX_BYTES)} olabilir (seçilen: ${formatBytes(selected.size)}).`,
      )
      setFile(null)
      return
    }
    setFile(selected)
    // Baslik bos ise dosya adindan turet; kullanici degistirebilir.
    if (!title.trim()) {
      setTitle(selected.name.replace(/\.[^.]+$/, ''))
    }
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!file) return

    setError(null)
    setProgress(0)
    setPhase('yukleniyor')

    try {
      const ticket = await videosApi.startUpload({
        title: title.trim(),
        description: description.trim(),
        fileName: file.name,
        contentType: file.type,
        sizeBytes: file.size,
      })

      const handle = uploadToStorage(ticket.uploadUrl, file, ticket.contentType, (loaded, total) =>
        setProgress(Math.round((loaded / total) * 100)),
      )
      handleRef.current = handle
      await handle.done

      setPhase('tamamlaniyor')

      // Küçük resim, "tamamlandı"dan ÖNCE yükleniyor. Sonra yüklenseydi işçi
      // bu arada devreye girip kendi ürettiği kareyi yazabilirdi; bu sırayla
      // işçi işi aldığında kullanıcının görseli çoktan kayıtlı oluyor ve
      // kare hiç üretilmiyor.
      if (thumb) {
        await videosApi.uploadThumbnail(ticket.videoId, thumb)
      }

      await videosApi.completeUpload(ticket.videoId)

      toast.success(`${title.trim()} yüklendi, işleniyor.`)
      reset()
      onOpenChange(false)
      onUploaded()
    } catch (e) {
      setPhase('secim')
      setError(e instanceof ApiError || e instanceof Error ? e.message : 'Yükleme başarısız.')
    }
  }

  const busy = phase !== 'secim'

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Video yükle</DialogTitle>
          <DialogDescription>
            Dosya doğrudan depolamaya yüklenir. Yükleme bittikten sonra süre,
            çözünürlük ve küçük resim arka planda çıkarılır.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="flex min-h-0 flex-1 flex-col gap-4">
          <DialogBody className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="videoFile">Dosya</Label>
              <label
                htmlFor="videoFile"
                className="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed p-4 transition-colors hover:bg-accent/30"
              >
                {file ? (
                  <FileVideoIcon className="size-8 shrink-0 text-primary" />
                ) : (
                  <UploadIcon className="size-8 shrink-0 text-muted-foreground" />
                )}
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium">
                    {file ? file.name : 'Dosya seçin'}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    {file
                      ? formatBytes(file.size)
                      : `${ACCEPTED.join(', ')} · en fazla ${formatBytes(MAX_BYTES)}`}
                  </div>
                </div>
              </label>
              <input
                id="videoFile"
                type="file"
                className="hidden"
                disabled={busy}
                accept={ACCEPTED.map((e) => `.${e}`).join(',')}
                onChange={(e) => pick(e.target.files?.[0] ?? null)}
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="videoTitle">Başlık</Label>
              <Input
                id="videoTitle"
                required
                maxLength={200}
                disabled={busy}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="videoDesc">Açıklama (isteğe bağlı)</Label>
              <textarea
                id="videoDesc"
                rows={3}
                maxLength={5000}
                disabled={busy}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                className="rounded-md border bg-input-bg px-3 py-2 text-sm"
              />
            </div>

            <div className="flex flex-col gap-2 rounded-lg border p-3">
              <div className="flex items-center justify-between gap-2">
                <Label>Küçük resim (isteğe bağlı)</Label>
                {thumb && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() => pickThumb(null)}
                  >
                    Kaldır
                  </Button>
                )}
              </div>

              <div className="flex items-center gap-3">
                {thumbPreview ? (
                  <img
                    src={thumbPreview}
                    alt=""
                    className="h-16 w-28 shrink-0 rounded-md border object-cover"
                  />
                ) : (
                  <div className="grid h-16 w-28 shrink-0 place-items-center rounded-md border border-dashed">
                    <ImageIcon className="size-5 text-muted-foreground" />
                  </div>
                )}
                <div className="flex flex-col gap-1.5">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() => thumbInputRef.current?.click()}
                  >
                    Görsel seç
                  </Button>
                  <span className="text-xs text-muted-foreground">
                    JPEG, PNG veya WebP · en fazla {formatBytes(MAX_THUMB_BYTES)}
                  </span>
                </div>
              </div>

              <input
                ref={thumbInputRef}
                type="file"
                accept={THUMB_TYPES.join(',')}
                className="hidden"
                onChange={(e) => pickThumb(e.target.files?.[0] ?? null)}
              />

              <p className="text-xs text-muted-foreground">
                Boş bırakılırsa videodan otomatik bir kare seçilir (sürenin
                %10'u, en az 3. saniye).
              </p>
            </div>

            {busy && (
              <div className="flex flex-col gap-1.5">
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>
                    {phase === 'tamamlaniyor' ? 'Depolamada doğrulanıyor…' : 'Yükleniyor…'}
                  </span>
                  <span>{progress}%</span>
                </div>
                <div className="h-2 overflow-hidden rounded-full bg-secondary">
                  <div
                    className="h-full rounded-full bg-primary transition-[width]"
                    style={{ width: `${phase === 'tamamlaniyor' ? 100 : progress}%` }}
                  />
                </div>
                <p className="text-xs text-muted-foreground">
                  Sekmeyi kapatmayın — yükleme kesilirse baştan başlaması gerekir.
                </p>
              </div>
            )}
          </DialogBody>

          {error && (
            <p role="alert" className="text-sm text-status-error">
              {error}
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => close(false)}>
              {busy ? 'İptal et' : 'Vazgeç'}
            </Button>
            <Button type="submit" disabled={!file || busy}>
              {busy && <Loader2Icon className="animate-spin" />}
              Yükle
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
