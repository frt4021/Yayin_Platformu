import { useCallback, useEffect, useRef, useState } from 'react'
import Hls from 'hls.js'
import { radiosApi } from '@/api/endpoints'
import type { RadioDto } from '@/api/types'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { Loader2Icon, PauseIcon, PlayIcon, RadioIcon, Volume2Icon, XIcon } from 'lucide-react'
import { usePresence } from './usePresence'

type Status = 'loading' | 'playing' | 'error'

/** Oynatıcı çubuğunun yüksekliği; mini video oynatıcı bu değere göre yukarı kayar. */
export const RADIO_BAR_HEIGHT = 'h-16'

/**
 * Sayfa geçişlerinde susmayan radyo oynatıcı.
 *
 * <p><b>Neden burada:</b> {@code AppLayout} içinde {@code <Outlet/>}'in
 * dışında duruyor. Route'un içinde yaşasaydı kullanıcı başka sayfaya
 * geçtiğinde React bileşeni unmount eder, {@code <audio>} elementi yok olur
 * ve yayın kesilirdi. Canlı yayın oynatıcılarıyla ({@code PersistentPlayers})
 * birebir aynı gerekçe.
 *
 * <p><b>Tek {@code <audio>} kuralı:</b> element hiçbir koşulda koşullu
 * render edilmiyor. Farklı durumlar için farklı JSX dalları döndürseydik
 * React elementi söker ve her durum değişiminde yayın baştan bağlanırdı;
 * HLS'te bu ~5 saniyelik bir sessizlik demek.
 */
export function PersistentRadio({
  radioId,
  paused,
  onTogglePause,
  onStop,
}: {
  radioId: string | null
  paused: boolean
  onTogglePause: () => void
  onStop: () => void
}) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  // Hata kurtarma deneme sayısı. Sınırsız bırakılırsa her hata yeni segment
  // isteği açıp MediaMTX'te reader (dinleyici) sayısının sürekli artmasına
  // yol açıyor — eskileri kapanmadan yenisi açılıyor.
  const retryRef = useRef(0)

  const [radios, setRadios] = useState<RadioDto[]>([])
  const [status, setStatus] = useState<Status>('loading')
  const [volume, setVolume] = useState(1)

  // Duraklatılsa bile sekme hâlâ "dinliyor" sayılıyor; yalnızca radioId
  // null olunca (kapatınca) nabız kesiliyor.
  usePresence('radios', radioId)

  const load = useCallback(async () => {
    try {
      setRadios(await radiosApi.list())
    } catch {
      // Liste alınamazsa çalan yayına dokunmuyoruz; ses akmaya devam etsin.
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const radio = radios.find((r) => r.id === radioId) ?? null
  const src = radio?.hlsUrl ?? null

  // Yayını bağla. Yalnızca adres değiştiğinde çalışır — duraklatma ve ses
  // seviyesi ayrı efektlerde, yoksa her düğmeye basışta yayın yeniden kurulurdu.
  //
  // hls.js her açılışta MediaMTX'e bağlanır ve segmentler için ayrı HTTP
  // istekleri açar. Bu istekler tam kapanmadan yenileri açılırsa MediaMTX
  // her birini ayrı reader (dinleyici) sayıyor ve sayaç artıyor. Bu yüzden:
  //   1) Yalnızca src değişince yeniden kur — her render'da değil.
  //   2) Kurulumdan ÖNCE eski hls'i tamamen yok et (destroy).
  //   3) Hata kurtarmada startLoad yerine tamamen yeniden kur.
  useEffect(() => {
    const audio = audioRef.current
    if (!audio || !src) return

    // Oncelikle eski hls varsa tamamen yok et — birakilan hls arka planda
    // segment indirmeye devam eder ve MediaMTX'te reader birikir.
    if (hlsRef.current) {
      hlsRef.current.destroy()
      hlsRef.current = null
    }
    // audio elementinin src'ini de temizle: Safari yerel HLS'inde eski
    // baglanti kapanmadan yenisi kurulabilir.
    audio.removeAttribute('src')
    audio.load()

    setStatus('loading')
    retryRef.current = 0

    // Safari HLS'i yerel oynatır; hls.js'i araya sokmak orada daha kötü sonuç verir.
    if (!Hls.isSupported()) {
      if (audio.canPlayType('application/vnd.apple.mpegurl')) {
        audio.src = src
        void audio.play().catch(() => {})
        setStatus('playing')
        return
      }
      setStatus('error')
      return
    }

    const hls = new Hls({ lowLatencyMode: true, maxBufferLength: 15 })

    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      setStatus('playing')
      // Tarayıcı kullanıcı etkileşimi olmadan sesli oynatmaya izin vermiyor.
      // Buraya gelinmesi kullanıcının bir istasyona tıklamasıyla olduğu için
      // izin normalde var; yine de reddedilirse sessizce yutuluyor.
      void audio.play().catch(() => {})
    })

    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return
      // Icecast bağlantıları düşüp geri gelebiliyor. Ancak startLoad/
      // recoverMediaError her çağrıda yeni segment isteği açıp eskileri
      // kapatmıyor — MediaMTX reader birikiyor. Bunun yerine tamamen
      // yok edip yeniden kuruyoruz: tek bir temiz bağlantı.
      if (retryRef.current < 3) {
        retryRef.current += 1
        hls.destroy()
        // Kissa bir bekleme sonra yeniden kur — ayni anda kurmak yine
        // iki baglanti acardi.
        setTimeout(() => {
          if (hlsRef.current === hls) return // arada baska kurulmus
          const fresh = new Hls({ lowLatencyMode: true, maxBufferLength: 15 })
          fresh.on(Hls.Events.MANIFEST_PARSED, () => {
            setStatus('playing')
            void audio.play().catch(() => {})
          })
          fresh.on(Hls.Events.ERROR, (_e2, d2) => {
            if (!d2.fatal) return
            setStatus('error')
            fresh.destroy()
          })
          hlsRef.current = fresh
          fresh.loadSource(src)
          fresh.attachMedia(audio)
        }, 1000)
        return
      }
      setStatus('error')
      hls.destroy()
    })

    hlsRef.current = hls
    hls.loadSource(src)
    hls.attachMedia(audio)

    return () => {
      hlsRef.current = null
      retryRef.current = 0
      hls.destroy()
    }
  }, [src])

  useEffect(() => {
    const audio = audioRef.current
    if (!audio) return
    if (paused) audio.pause()
    else void audio.play().catch(() => {})
  }, [paused, status])

  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume
  }, [volume])

  return (
    <>
      {/* Element her zaman DOM'da: kaldırılsaydı yayın baştan bağlanırdı. */}
      <audio ref={audioRef} className="hidden" />

      {radio && (
        <div
          className={cn(
            'fixed inset-x-0 bottom-0 z-50 flex items-center gap-3 border-t bg-card px-4 shadow-lg',
            RADIO_BAR_HEIGHT,
          )}
        >
          <Logo radio={radio} className="size-10 shrink-0 rounded-md text-sm" />

          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium">{radio.name}</div>
            <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
              {status === 'loading' && !paused ? (
                <>
                  <Loader2Icon className="size-3 animate-spin" />
                  Bağlanıyor…
                </>
              ) : status === 'error' ? (
                <span className="text-status-error">Yayın alınamadı</span>
              ) : paused ? (
                'Duraklatıldı'
              ) : (
                <>
                  <span className="size-1.5 animate-pulse rounded-full bg-status-live" />
                  <span className="text-status-live">Canlı</span>
                </>
              )}
            </div>
          </div>

          <Button
            size="icon"
            variant="secondary"
            className="size-10 rounded-full"
            title={paused ? 'Devam et' : 'Duraklat'}
            onClick={onTogglePause}
          >
            {paused ? <PlayIcon /> : <PauseIcon />}
          </Button>

          {/* Ses seviyesi dar ekranda gizleniyor: çubuğun tamamı 64px yüksekliğinde
              ve istasyon adı önceliğe sahip. */}
          <div className="hidden items-center gap-2 sm:flex">
            <Volume2Icon className="size-4 text-muted-foreground" />
            <input
              type="range"
              aria-label="Ses seviyesi"
              min={0}
              max={1}
              step={0.05}
              value={volume}
              onChange={(e) => setVolume(Number(e.target.value))}
              className="w-24 accent-primary"
            />
          </div>

          <Button variant="ghost" size="icon" title="Radyoyu kapat" onClick={onStop}>
            <XIcon />
          </Button>
        </div>
      )}
    </>
  )
}

/**
 * İstasyon görseli. Logo yoksa adın baş harfleri gösteriliyor — boş bir kutu
 * yerine bu, listede istasyonları birbirinden ayırt etmeyi kolaylaştırıyor.
 */
export function Logo({ radio, className }: { radio: RadioDto; className?: string }) {
  const [broken, setBroken] = useState(false)

  if (radio.logoUrl && !broken) {
    return (
      <img
        src={radio.logoUrl}
        alt=""
        onError={() => setBroken(true)}
        className={cn('object-cover', className)}
      />
    )
  }

  const initials = radio.name
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word[0]?.toLocaleUpperCase('tr'))
    .join('')

  return (
    <div className={cn('grid place-items-center bg-secondary font-semibold', className)}>
      {initials || <RadioIcon className="size-4" />}
    </div>
  )
}
