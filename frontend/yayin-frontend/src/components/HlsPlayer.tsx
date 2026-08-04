import { useEffect, useRef, useState } from 'react'
import Hls from 'hls.js'
import { cn } from '@/lib/utils'

type Status = 'loading' | 'playing' | 'error'

/**
 * Tek bir HLS yayınını oynatan oynatıcı.
 *
 * <p>Grid görünümünde onlarca örneği aynı anda çalışabildiği için ayarlar
 * kaynak tüketimini sınırlayacak şekilde seçildi:
 * <ul>
 *   <li>{@code capLevelToPlayerSize} — 4x4 gridde her karo ~480px genişliğinde
 *       oluyor; bu ayar olmadan hls.js 1080p rendition çekip 16 kez 1080p
 *       çözerdi. Karo boyutuna uyan en küçük rendition seçiliyor.</li>
 *   <li>{@code maxBufferLength} kısa — 16 yayın × uzun tampon bellek şişirir.</li>
 * </ul>
 *
 * @param muted Grid'de ses odağı tek karoda olur; diğerleri sessizdir.
 *              Tarayıcılar sesli otomatik oynatmayı engellediği için ilk
 *              yükleme her zaman sessiz başlar.
 */
export function HlsPlayer({
  src,
  muted = true,
  controls = false,
  className,
  onStatusChange,
}: {
  src: string
  muted?: boolean
  /** Duraklatma, ses, tam ekran, ileri/geri sarma. Mozaikte kapalı: 16 karoda
   *  16 kontrol çubuğu görüntüyü boğar, karo zaten çok küçük. */
  controls?: boolean
  className?: string
  onStatusChange?: (status: Status) => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [detail, setDetail] = useState<string | null>(null)
  const [behindLive, setBehindLive] = useState(false)

  useEffect(() => {
    onStatusChange?.(status)
  }, [status, onStatusChange])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return

    setStatus('loading')
    setDetail(null)

    // Safari HLS'i yerel olarak oynatır; hls.js'i araya sokmak gereksiz
    // ve Safari'de daha kötü sonuç verir.
    if (!Hls.isSupported()) {
      if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = src
        video.play().catch(() => {})
        setStatus('playing')
        return
      }
      setStatus('error')
      setDetail('Bu tarayıcı HLS oynatamıyor.')
      return
    }

    const hls = new Hls({
      lowLatencyMode: true,
      capLevelToPlayerSize: true,
      maxBufferLength: 10,
      liveSyncDurationCount: 3,
    })

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setStatus('playing')
      video.play().catch(() => {})
    })

    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return
      // Ölümcül hatalarda hls.js kendini toparlayabiliyor. Canlı yayında
      // kaynak birkaç saniye kesilip geri gelebildiği için önce kurtarmayı
      // deniyoruz; hemen hata göstermek yanıltıcı olurdu.
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        hls.startLoad()
        return
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError()
        return
      }
      setStatus('error')
      setDetail(data.details)
      hls.destroy()
    })

    hlsRef.current = hls
    hls.loadSource(src)
    hls.attachMedia(video)

    return () => {
      hlsRef.current = null
      hls.destroy()
    }
  }, [src])

  /**
   * Canlı yayında duraklatınca kullanıcı geride kalır ve hls.js kendiliğinden
   * öne atlamaz — yayın "donmuş" gibi görünür. Geri kalmayı ölçüp açık bir
   * dönüş yolu sunuyoruz.
   */
  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const timer = setInterval(() => {
      const live = hlsRef.current?.liveSyncPosition
      setBehindLive(live !== null && live !== undefined && live - video.currentTime > 5)
    }, 2000)
    return () => clearInterval(timer)
  }, [])

  function goLive() {
    const video = videoRef.current
    const live = hlsRef.current?.liveSyncPosition
    if (video && live != null) {
      video.currentTime = live
      void video.play()
    }
  }

  return (
    <div className={cn('relative overflow-hidden bg-black', className)}>
      <video
        ref={videoRef}
        muted={muted}
        playsInline
        controls={controls}
        className="size-full object-contain"
      />

      {behindLive && status === 'playing' && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            goLive()
          }}
          className="absolute left-2 top-2 z-10 rounded-full bg-destructive px-2.5 py-1 text-xs font-medium text-destructive-foreground shadow"
        >
          ● Canlıya dön
        </button>
      )}
      {status !== 'playing' && (
        <div className="absolute inset-0 grid place-items-center bg-black/70 p-2 text-center text-xs">
          {status === 'loading' ? (
            <span className="text-muted-foreground">Bağlanıyor…</span>
          ) : (
            <span className="text-destructive">Yayın alınamadı{detail ? `: ${detail}` : ''}</span>
          )}
        </div>
      )}
    </div>
  )
}
