import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { videosApi } from '@/api/endpoints'
import { formatBytes, formatDuration } from '@/api/upload'
import type { VideoDto, VideoLinks } from '@/api/types'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { DownloadIcon, Loader2Icon } from 'lucide-react'

/**
 * Video oynatma.
 *
 * <p>Adresler açılışta alınıyor, listede değil: imzalı adreslerin süresi
 * üretildikleri anda işlemeye başlıyor ve ızgaradaki her video için
 * üretmek gereksiz olurdu.
 *
 * <p>Oynatma progressive MP4 — HLS paketleme transcode gerektirdiği için
 * kütüphanede yok. Dosya doğrudan nesne depolamasından geliyor, backend'den
 * geçmiyor.
 */
export function VideoPlayerDialog({
  video,
  open,
  onOpenChange,
}: {
  video: VideoDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [links, setLinks] = useState<VideoLinks | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open || !video) {
      setLinks(null)
      setError(null)
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const result = await videosApi.links(video.id)
        if (!cancelled) setLinks(result)
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : 'İzleme adresi alınamadı.')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [open, video])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl">
        <DialogHeader>
          <DialogTitle className="truncate">{video?.title}</DialogTitle>
          <DialogDescription>
            {video && (
              <>
                {formatDuration(video.durationSeconds)}
                {video.width ? ` · ${video.width}x${video.height}` : ''}
                {` · ${formatBytes(video.sizeBytes)}`}
                {video.uploadedBy ? ` · ${video.uploadedBy}` : ''}
              </>
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="aspect-video overflow-hidden rounded-lg bg-black">
          {error ? (
            <div className="grid size-full place-items-center p-4 text-center text-sm text-status-error">
              {error}
            </div>
          ) : links ? (
            // key: adres degisince oynatici yeniden kurulmali.
            <video
              key={links.stream}
              src={links.stream}
              controls
              autoPlay
              className="size-full"
            />
          ) : (
            <div className="grid size-full place-items-center">
              <Loader2Icon className="size-6 animate-spin text-muted-foreground" />
            </div>
          )}
        </div>

        {video?.description && (
          <p className="max-h-24 overflow-y-auto whitespace-pre-wrap text-sm text-muted-foreground">
            {video.description}
          </p>
        )}

        <DialogFooter>
          {links && (
            <Button variant="outline" asChild>
              <a href={links.download} download={links.fileName}>
                <DownloadIcon />
                İndir
              </a>
            </Button>
          )}
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Kapat
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
