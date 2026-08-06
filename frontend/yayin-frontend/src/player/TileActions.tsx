import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { recordingsApi, screenshotsApi } from '@/api/endpoints'
import type { ActiveRecordingDto, ChannelDto } from '@/api/types'
import type { CaptureHandle } from '@/components/HlsPlayer'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { CameraIcon, CircleIcon, Loader2Icon, SquareIcon } from 'lucide-react'

/** JPEG kalitesi: 0.92 gözle kayıpsız sayılır, PNG'nin üçte biri yer kaplar. */
const JPEG_QUALITY = 0.92

function gecenSure(startedAt: string): string {
  const s = Math.max(0, Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000))
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

/**
 * Karo üzerindeki kayıt ve kare yakalama düğmeleri.
 *
 * <p><b>Kare tarayıcıda yakalanıyor.</b> Canvas'a çizilen görüntü kullanıcının
 * gördüğü karenin ta kendisi; sunucudan yakalansaydı HLS gecikmesi yüzünden
 * 6-20 saniye ilerideki bir kare gelirdi. Video MSE ile beslendiği için
 * canvas "tainted" olmuyor, {@code toBlob} çalışıyor.
 */
export function TileActions({
  channel,
  capture,
  recording,
  onRecordingChanged,
  compact,
}: {
  channel: ChannelDto
  capture: { current: CaptureHandle | null }
  /** Bu kanalda devam eden kayıt; yoksa null. */
  recording: ActiveRecordingDto | null
  onRecordingChanged: () => void
  compact: boolean
}) {
  const [busy, setBusy] = useState(false)
  const [, tick] = useState(0)
  const shotBusy = useRef(false)

  // Kayıt sürerken saniye sayacını ilerlet. Süre istemcide hesaplanıyor;
  // sunucudan saniye saniye çekmek gereksiz trafik olurdu.
  useEffect(() => {
    if (!recording) return
    const timer = setInterval(() => tick((n) => n + 1), 1000)
    return () => clearInterval(timer)
  }, [recording])

  async function toggleRecording() {
    setBusy(true)
    try {
      if (recording) {
        await recordingsApi.stop(channel.id)
        toast.success('Kayıt durduruldu, klip hazırlanıyor.')
      } else {
        await recordingsApi.start(channel.id)
        toast.success(`${channel.name} kaydediliyor.`)
      }
      onRecordingChanged()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Kayıt işlemi başarısız.')
    } finally {
      setBusy(false)
    }
  }

  async function captureFrame() {
    const handle = capture.current
    const video = handle?.video
    if (!video || !video.videoWidth) {
      toast.error('Görüntü henüz hazır değil.')
      return
    }
    if (shotBusy.current) return
    shotBusy.current = true

    try {
      const canvas = document.createElement('canvas')
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      const ctx = canvas.getContext('2d')
      if (!ctx) throw new Error('Canvas oluşturulamadı.')
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height)

      const blob = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY),
      )
      if (!blob) throw new Error('Kare kodlanamadı.')

      await screenshotsApi.capture(
        channel.id, blob, handle.playingDate(), canvas.width, canvas.height,
      )
      toast.success('Kare galeriye eklendi.')
    } catch (e) {
      toast.error(e instanceof ApiError || e instanceof Error ? e.message : 'Kare alınamadı.')
    } finally {
      shotBusy.current = false
    }
  }

  return (
    <div className="pointer-events-auto flex items-center gap-1">
      {recording && (
        <span className="flex items-center gap-1 rounded-full bg-status-live-bg px-2 py-0.5 text-xs font-medium text-status-live">
          <span className="size-1.5 animate-pulse rounded-full bg-status-live" />
          {gecenSure(recording.startedAt)}
        </span>
      )}

      <Button
        variant="secondary"
        size="icon"
        className={cn('size-7', recording && 'text-status-live')}
        disabled={busy || !channel.dvrEnabled}
        title={
          !channel.dvrEnabled
            ? 'Kayıt için kanalda geriye sarma açık olmalı'
            : recording
              ? 'Kaydı durdur'
              : 'Kayda başla'
        }
        onClick={(e) => {
          e.stopPropagation()
          void toggleRecording()
        }}
      >
        {busy ? <Loader2Icon className="animate-spin" /> : recording ? <SquareIcon /> : <CircleIcon />}
      </Button>

      {!compact && (
        <Button
          variant="secondary"
          size="icon"
          className="size-7"
          title="Kare yakala"
          onClick={(e) => {
            e.stopPropagation()
            void captureFrame()
          }}
        >
          <CameraIcon />
        </Button>
      )}
    </div>
  )
}
