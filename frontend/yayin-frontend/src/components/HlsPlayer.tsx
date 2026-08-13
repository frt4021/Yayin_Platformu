import { useCallback, useEffect, useRef, useState } from 'react'
import Hls from 'hls.js'
import { cn } from '@/lib/utils'
import { hlsGerideOku } from '@/player/oynaticiAyarlari'

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
/**
 * Kare yakalama için dışarıya açılan tutamak.
 *
 * <p>Video elementi bileşenin içinde; ekran görüntüsü alabilmek için ona
 * erişim gerekiyor. {@code playingDate} ise karenin ait olduğu YAYIN anını
 * veriyor — HLS'te izlenen an ile "şu an" arasında 6-20 saniye fark var ve
 * kareyi "şimdi" diye kaydetmek onu yanlış etiketlerdi.
 */
export interface CaptureHandle {
  video: HTMLVideoElement | null
  playingDate: () => Date
  /**
   * Canlı kenarın oynatma zaman çizgisindeki konumu, bilinmiyorsa {@code null}.
   *
   * <p>Denetimler bunu iki şey için okuyor: "canlı mı geride mi" göstergesi ve
   * ilerleme çubuğunun sağ ucu. {@code video.duration} canlı yayında
   * {@code Infinity} döndüğü için oradan hesaplanamıyor.
   */
  liveEdge: () => number | null
  /** Canlı kenara atlar. Geride kalındığında tek dönüş yolu. */
  goLive: () => void
}

export function HlsPlayer({
  src,
  muted = true,
  controls = false,
  className,
  onStatusChange,
  captureRef,
  onVideo,
  showLiveBadge = true,
}: {
  src: string
  muted?: boolean
  /** Doldurulursa kare yakalama tutamağı buraya yazılır. */
  captureRef?: { current: CaptureHandle | null }
  /** Duraklatma, ses, tam ekran, ileri/geri sarma. Mozaikte kapalı: 16 karoda
   *  16 kontrol çubuğu görüntüyü boğar, karo zaten çok küçük. */
  controls?: boolean
  /**
   * Video elementi hazır olduğunda haber verir.
   *
   * <p>{@code captureRef} yerine ayrı bir geri çağırım: ref'e yazmak yeniden
   * render tetiklemiyor ve özel denetimlerin elementi <b>görebilmek için</b>
   * render'a ihtiyacı var. Ref ile yapılsaydı denetimler ilk karede boş
   * element görüp bir daha güncellenmezdi.
   */
  onVideo?: (video: HTMLVideoElement | null) => void
  /**
   * Geride kalındığında "Canlıya dön" rozetini göster.
   *
   * <p>Özel denetim çubuğu kullanılan karolarda kapatılıyor: aynı işi yapan
   * CANLI hapı orada zaten var ve iki düğme aynı anda görününce hangisinin
   * ne yaptığı belirsizleşiyor. Mozaikte denetim çubuğu yok, rozet tek yol.
   */
  showLiveBadge?: boolean
  className?: string
  onStatusChange?: (status: Status) => void
}) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  // Hata kurtarma deneme sayısı. Sınırsız bırakılırsa her hata yeni segment
  // isteği açıp MediaMTX'te reader (izleyici) sayısının sürekli artmasına
  // yol açıyor — eskileri kapanmadan yenisi açılıyor.
  const retryRef = useRef(0)
  const [status, setStatus] = useState<Status>('loading')
  const [detail, setDetail] = useState<string | null>(null)
  const [behindLive, setBehindLive] = useState(false)

  useEffect(() => {
    onStatusChange?.(status)
  }, [status, onStatusChange])

  /**
   * Video elementini hem içeri hem dışarı bağlar.
   *
   * <p><b>Effect ile yapılamaz.</b> Bağımlılıksız bir effect her render'da
   * önce {@code onVideo(null)} sonra {@code onVideo(element)} çağırır; bunlar
   * durum güncellemesi tetiklediği için render → effect → render döngüsüne
   * girer. Ref geri çağırımı yalnızca element gerçekten değiştiğinde çalışıyor.
   *
   * <p>{@code onVideo} <b>kararlı olmalı</b> (örn. doğrudan bir setState).
   * Her render'da yeni bir ok fonksiyonu geçilirse ref her seferinde sökülüp
   * takılır ve aynı döngü geri gelir.
   */
  const bindVideo = useCallback(
    (el: HTMLVideoElement | null) => {
      videoRef.current = el
      onVideo?.(el)
    },
    [onVideo],
  )

  // Tutamağı her render'da tazele: video elementi ve hls örneği değişebiliyor.
  useEffect(() => {
    if (!captureRef) return
    captureRef.current = {
      video: videoRef.current,
      liveEdge: () => hlsRef.current?.liveSyncPosition ?? null,
      goLive,
      playingDate: () => {
        // hls.js, playlist'te EXT-X-PROGRAM-DATE-TIME varsa karenin gerçek
        // saatini veriyor (MediaMTX bunu üretiyor). Yoksa gecikmeyi canlı uç
        // ile oynatma konumu arasındaki farktan tahmin ediyoruz.
        const hls = hlsRef.current
        if (hls?.playingDate) return hls.playingDate
        const video = videoRef.current
        const live = hls?.liveSyncPosition
        if (video && live != null) {
          return new Date(Date.now() - (live - video.currentTime) * 1000)
        }
        return new Date()
      },
    }
    return () => {
      if (captureRef) captureRef.current = null
    }
  })

  useEffect(() => {
    const video = videoRef.current
    if (!video) return

    // Oncelikle eski hls varsa tamamen yok et — birakilan hls arka planda
    // segment indirmeye devam eder ve MediaMTX'te reader (izleyici) birikir.
    if (hlsRef.current) {
      hlsRef.current.destroy()
      hlsRef.current = null
    }
    video.removeAttribute('src')
    video.load()

    setStatus('loading')
    setDetail(null)
    retryRef.current = 0

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

      // İzleyicinin canlı kenardan geride kalma miktarı:
      //     liveSyncDurationCount x EXT-X-TARGETDURATION
      // Ölçülen yayında hedef süre 2 sn, yani 8 -> 16 saniye geride.
      //
      // BU DEĞER ALTYAZININ BÜTÇESİ. Arayüz altyazıyı
      // "baslangic <= playingDate() < bitis" ile eşliyor: altyazı, izleyici o
      // saniyeye varmadan üretilmiş olmalı. Geç kalan altyazı geç değil HİÇ
      // gösterilmiyor ve hiçbir yerde hata görünmüyor.
      //
      // Sabit 3'tü (6 sn). Ölçülen üretim gecikmesi p50 ~13 sn, p95 ~23 sn --
      // hiçbir altyazı yetişmiyordu. Artık .env'den geliyor
      // (ALTYAZI_HLS_GERIDE): GPU'da gecikme düşünce geri indirilmeli.
      //
      // AÇIKÇA VERİLMESİ ŞART. Sunucu oynatma listesinde PART-HOLD-BACK=0.5
      // ilan ediyor; lowLatencyMode açıkken hls.js kullanıcı ayarı yoksa ONU
      // kullanıyor ve bütçe yarım saniyeye düşüyor.
      liveSyncDurationCount: hlsGerideOku(),
    })

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setStatus('playing')
      video.play().catch(() => {})
    })

    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return
      // Ölümcül hatalarda hls.js'in startLoad/recoverMediaError kurtarması
      // her çağrıda yeni segment isteği açıyor; eskileri tam kapanmıyor ve
      // MediaMTX her birini ayrı reader (izleyici) sayıyor. Bunun yerine
      // tamamen yok edip temiz bir bağlantıyla yeniden kuruyoruz.
      if (retryRef.current < 3) {
        retryRef.current += 1
        hls.destroy()
        setTimeout(() => {
          if (hlsRef.current !== hls) return // arada baska kurulmus
          const video2 = videoRef.current
          if (!video2) return
          const fresh = new Hls({
            lowLatencyMode: true,
            capLevelToPlayerSize: true,
            maxBufferLength: 10,
            liveSyncDurationCount: hlsGerideOku(),
          })
          fresh.on(Hls.Events.MANIFEST_PARSED, () => {
            setStatus('playing')
            video2.play().catch(() => {})
          })
          fresh.on(Hls.Events.ERROR, (_e2, d2) => {
            if (!d2.fatal) return
            setStatus('error')
            setDetail(d2.details)
            fresh.destroy()
          })
          hlsRef.current = fresh
          fresh.loadSource(src)
          fresh.attachMedia(video2)
        }, 1000)
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
      retryRef.current = 0
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
        ref={bindVideo}
        muted={muted}
        playsInline
        controls={controls}
        className="size-full object-contain"
      />

      {showLiveBadge && behindLive && status === 'playing' && (
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
