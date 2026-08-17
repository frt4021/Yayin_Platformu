import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/client'
import { videosApi } from '@/api/endpoints'
import { formatBytes, formatDuration } from '@/api/upload'
import type { VideoDto, VideoLinks } from '@/api/types'
import { subtitleLangs } from '@/player/SubtitleOverlay'
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
  const videoRef = useRef<HTMLVideoElement>(null)

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

  // Kullanıcı davranışı denetim izi: tamamlanma oranı + kaba (10 dilim)
  // tekrar-izleme ısı haritası. Fetch effect'inden AYRI tutuluyor -- bu
  // effect'in işi telemetri, o effect'in işi adres alma; ikisini
  // karıştırmak temizlik/yeniden başlatma sınırlarını bulanıklaştırırdı.
  useEffect(() => {
    const el = videoRef.current
    if (!el || !links || !video) return
    const durationSeconds = video.durationSeconds
    const videoId = video.id

    const oturum = {
      dilimler: new Set<number>(),
      duraklatma: 0,
      sarma: 0,
      tamamlandi: false,
      baslangic: Date.now(),
      basladiBildirildi: false,
    }

    function onTimeUpdate() {
      if (!durationSeconds || !el) return
      const dilim = Math.min(9, Math.max(0, Math.floor((el.currentTime / durationSeconds) * 10)))
      oturum.dilimler.add(dilim)
      if (dilim === 9) oturum.tamamlandi = true
    }
    function onPlay() {
      if (oturum.basladiBildirildi) return
      oturum.basladiBildirildi = true
      void videosApi.izlemeBasladi(videoId).catch(() => {})
    }
    function onPause() {
      if (!el?.ended) oturum.duraklatma += 1
    }
    function onSeeked() {
      oturum.sarma += 1
    }
    function onEnded() {
      oturum.tamamlandi = true
    }

    el.addEventListener('timeupdate', onTimeUpdate)
    el.addEventListener('play', onPlay)
    el.addEventListener('pause', onPause)
    el.addEventListener('seeked', onSeeked)
    el.addEventListener('ended', onEnded)

    return () => {
      el.removeEventListener('timeupdate', onTimeUpdate)
      el.removeEventListener('play', onPlay)
      el.removeEventListener('pause', onPause)
      el.removeEventListener('seeked', onSeeked)
      el.removeEventListener('ended', onEnded)

      // Tek beacon: dialog kapanırken/video değişirken. Oynatma hiç
      // başlamadıysa (hemen kapatıldıysa) boş bir oturum gönderilmiyor.
      if (oturum.basladiBildirildi) {
        void videosApi
          .izlemeOzeti(videoId, {
            ziyaretEdilenDilimler: [...oturum.dilimler].sort((a, b) => a - b),
            tamamlandi: oturum.tamamlandi,
            duraklatmaSayisi: oturum.duraklatma,
            sarmaSayisi: oturum.sarma,
            sureMs: Date.now() - oturum.baslangic,
          })
          .catch(() => {})
      }
    }
  }, [links, video])

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
              ref={videoRef}
              src={links.stream}
              controls
              autoPlay
              className="size-full"
            >
              {links.subtitles.map((t, i) => (
                <track
                  key={t.lang}
                  kind="subtitles"
                  srcLang={t.lang}
                  label={subtitleLangs().find((l) => l.kod === t.lang)?.ad ?? t.lang}
                  src={t.url}
                  default={i === 0}
                />
              ))}
            </video>
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
