import { useEffect, useRef, useState } from 'react'
import {
  MaximizeIcon,
  PauseIcon,
  PictureInPicture2Icon,
  PlayIcon,
  RotateCcwIcon,
  RotateCwIcon,
  Volume2Icon,
  VolumeXIcon,
} from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Video üzerine bindirilen özel denetim katmanı.
 *
 * <h2>Neden yerleşik {@code controls} yerine bu</h2>
 * Tarayıcının kendi çubuğu her tarayıcıda başka görünüyor, renklendirilemiyor
 * ve <b>canlı yayını anlamıyor</b>: {@code duration} canlıda {@code Infinity}
 * olduğu için Chrome boş bir çubuk çiziyor, "canlı mı geride mi" diye bir
 * kavramı yok ve canlıya dönmenin bir yolunu sunmuyor.
 *
 * <h2>İlerleme çubuğu ne gösteriyor</h2>
 * <b>{@code video.seekable}</b> — yani gerçekten atlanabilen aralık. Canlı
 * HLS'te bu yalnızca playlist'teki segmentler kadar (bizde ~14 sn: 7 × 1,96).
 * Geri sarılmış bölümde ise DVR'dan gelen mp4'ün tamamı (~120 sn) ve çubuk
 * orada gerçekten dolu.
 *
 * <p>Bilerek <b>uzun bir zaman çizgisi çizilmiyor.</b> Tasarımda çubuk yarıya
 * kadar dolu görünüyor ama canlıda öyle bir aralık yok; çizilseydi kullanıcı
 * saatler öncesine tıklayabileceğini sanır, tıklar ve hiçbir şey olmazdı.
 * Uzun geri sarma ayrı bir yol: {@code LiveRewind} ve Geriye sarma sayfası.
 *
 * <h2>Neden ayrı bileşen</h2>
 * Hem canlı HLS oynatıcısına hem geri sarılmış mp4'e aynı çubuk takılıyor.
 * İkisi farklı elementler ama denetim ihtiyacı aynı.
 */
export function PlayerControls({
  video,
  container,
  liveEdge,
  onGoLive,
  onBufferExceeded,
  className,
}: {
  /** Denetlenecek element. Hazır değilse çubuk çizilmiyor. */
  video: HTMLVideoElement | null
  /**
   * Tam ekrana alınacak kapsayıcı — karonun kökü.
   *
   * <p>Video elementinin kendisi <b>yeterli değil</b>: denetim çubuğu ve
   * altyazı bindirmesi onun kardeşi, içinde değil. Video tam ekrana
   * alınsaydı ikisi de kaybolurdu.
   */
  container?: HTMLElement | null
  /**
   * Canlı kenarın konumu. {@code null} ise yayın canlı değil (geri sarılmış
   * bölüm) ve CANLI göstergesi gizleniyor.
   */
  liveEdge?: () => number | null
  onGoLive?: () => void
  /**
   * Canlı HLS tamponu (`video.seekable`, ~14 sn) tükenip daha geriye
   * gidilemediğinde çağrılır — parametre, canlı kenardan o ana kadar kaç
   * saniye geriye gidilmiş olduğu. Çağıran taraf bunu DVR'dan devam
   * ettirmek için kullanabilir ({@link LiveRewind}'in {@code seekTo}'su
   * gibi) — aksi halde kullanıcı art arda tıkladıkça tamponun kenarında
   * takılı kalır.
   */
  onBufferExceeded?: (secondsBehindLive: number) => void
  className?: string
}) {
  const [paused, setPaused] = useState(true)
  const [muted, setMuted] = useState(true)
  const [now, setNow] = useState(0)
  const [range, setRange] = useState<{ start: number; end: number } | null>(null)
  const barRef = useRef<HTMLDivElement>(null)

  // Element durumunu izle. Olay dinleyicileri + düşük frekanslı bir sayaç
  // birlikte: timeupdate saniyede ~4 kez geliyor ama seekable aralık olay
  // üretmiyor, canlı yayında ise sürekli ileri kayıyor.
  useEffect(() => {
    if (!video) return

    const oku = () => {
      setPaused(video.paused)
      setMuted(video.muted)
      setNow(video.currentTime)
      // seekable boş olabiliyor (henüz tampon yok); o durumda çubuk
      // çizilmiyor -- sıfır genişlikte bir çubuk tıklanabilir görünürdü.
      if (video.seekable.length > 0) {
        setRange({
          start: video.seekable.start(0),
          end: video.seekable.end(video.seekable.length - 1),
        })
      } else {
        setRange(null)
      }
    }

    oku()
    const olaylar = ['play', 'pause', 'timeupdate', 'volumechange', 'durationchange'] as const
    olaylar.forEach((e) => video.addEventListener(e, oku))
    const timer = setInterval(oku, 500)
    return () => {
      olaylar.forEach((e) => video.removeEventListener(e, oku))
      clearInterval(timer)
    }
  }, [video])

  if (!video) return null

  const canli = liveEdge?.() ?? null
  // "Geride" eşiği 5 sn: canlı akışta oynatma konumu sürekli birkaç saniye
  // salınıyor ve daha dar bir eşik göstergeyi yanıp söndürürdü.
  const gerideKaldi = canli != null && canli - now > 5

  function atla(saniye: number) {
    if (!video || !range) return
    const hedef = video.currentTime + saniye

    // Geri giderken tamponun (seekable.start) DIŞINA taşıyorsa -- canlı
    // HLS'te bu yalnızca ~14 sn, geri sarılmış bir DVR parçasında da o
    // parçanın kendi süresi kadar -- burada durup "gidemiyorsun" demek
    // yerine DVR'a devrediyoruz/zincirliyoruz. canli null OLABİLİR (zaten
    // geri sarılmış bir parça izlenirken -- liveEdge o durumda undefined
    // geçiliyor): bu durumda parametre 0 gidiyor, çağıran taraf (Tile)
    // kendi izlediği parçanın başlangıcından devam ediyor. Bunu YALNIZCA
    // canlıdayken engellemek, kullanıcının art arda tıkladıkça tamponun
    // kenarına kilitlenip (Math.max ile hep aynı noktaya clamp'lenip)
    // hls.js'in canlı-senkron kurtarmasıyla "şimdi"ye sıçramasına yol
    // açıyordu -- ölçülen/bildirilen hata buydu.
    if (hedef < range.start && onBufferExceeded) {
      onBufferExceeded(canli != null ? canli - range.start : 0)
      return
    }

    // Atlama seekable aralığın DIŞINA taşmamalı: canlı kenarın ötesine
    // gitmek oynatmayı durduruyor, başlangıcın gerisi ise hata veriyor.
    video.currentTime = Math.min(Math.max(hedef, range.start), range.end)
  }

  function konumla(e: React.MouseEvent<HTMLDivElement>) {
    if (!video || !range || !barRef.current) return
    const kutu = barRef.current.getBoundingClientRect()
    const oran = Math.min(Math.max((e.clientX - kutu.left) / kutu.width, 0), 1)
    video.currentTime = range.start + oran * (range.end - range.start)
  }

  const ilerleme =
    range && range.end > range.start ? ((now - range.start) / (range.end - range.start)) * 100 : 0

  return (
    // pointer-events-none ZORUNLU: karoya tıklayınca büyüten katman bunun
    // ALTINDA duruyor. Kapsayıcı tıklamayı yutsaydı büyütme tamamen çalışmaz,
    // videonun her yeri ölü alan olurdu. Gerçek denetimler tek tek geri
    // açıyor (pointer-events-auto).
    <div className={cn('pointer-events-none absolute inset-0 z-10', className)}>
      {/* --- Orta: büyük oynat + atlama ---
          Yalnızca duraklatılmışken ya da fare üzerindeyken görünüyor.
          Sürekli görünseydi yayının üstünde kalıcı bir daire dururdu. */}
      <div
        className={cn(
          'pointer-events-none absolute inset-0 flex items-center justify-center gap-10 transition-opacity',
          paused ? 'opacity-100' : 'opacity-0 group-hover:opacity-100',
        )}
      >
        <OrtaDugme label={`${ATLAMA_SN} sn geri`} onClick={() => atla(-ATLAMA_SN)}>
          <RotateCcwIcon className="size-6" />
          <span className="text-[10px] font-medium">{ATLAMA_SN}</span>
        </OrtaDugme>

        <OrtaDugme
          buyuk
          label={paused ? 'Oynat' : 'Duraklat'}
          onClick={() => (paused ? void video.play() : video.pause())}
        >
          {paused ? <PlayIcon className="size-9 fill-current" /> : <PauseIcon className="size-9 fill-current" />}
        </OrtaDugme>

        <OrtaDugme label={`${ATLAMA_SN} sn ileri`} onClick={() => atla(ATLAMA_SN)}>
          <RotateCwIcon className="size-6" />
          <span className="text-[10px] font-medium">{ATLAMA_SN}</span>
        </OrtaDugme>
      </div>

      {/* --- Alt çubuk --- */}
      <div className="pointer-events-auto absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/90 via-black/70 to-transparent px-3 pb-2.5 pt-8">
        {/* İlerleme. Yükseklik dar ama tıklama alanı geniş: 4 piksellik bir
            çubuğa isabet ettirmek zor, o yüzden dolgu ile büyütüldü. */}
        <div
          ref={barRef}
          role="slider"
          aria-label="Konum"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(ilerleme)}
          tabIndex={0}
          className={cn(
            'group/bar -mx-1 px-1 py-2',
            range ? 'cursor-pointer' : 'pointer-events-none opacity-40',
          )}
          onClick={konumla}
        >
          <div className="relative h-1 rounded-full bg-white/25">
            <div
              className="absolute inset-y-0 left-0 rounded-full bg-primary"
              style={{ width: `${ilerleme}%` }}
            />
            <span
              className="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary opacity-0 transition-opacity group-hover/bar:opacity-100"
              style={{ left: `${ilerleme}%` }}
            />
          </div>
        </div>

        <div className="flex items-center gap-1 text-white">
          <AltDugme
            label={paused ? 'Oynat' : 'Duraklat'}
            onClick={() => (paused ? void video.play() : video.pause())}
          >
            {paused ? <PlayIcon className="size-4 fill-current" /> : <PauseIcon className="size-4 fill-current" />}
          </AltDugme>

          <AltDugme
            label={muted ? 'Sesi aç' : 'Sesi kapat'}
            onClick={() => {
              video.muted = !video.muted
            }}
          >
            {muted ? <VolumeXIcon className="size-4" /> : <Volume2Icon className="size-4" />}
          </AltDugme>

          {/* Saat: canlıda YAYIN anı, geri sarılmış bölümde geçen süre.
              Canlıda oynatma konumunu (0'dan artan saniye) göstermek
              anlamsız olurdu -- canlı yayının başlangıcı yok. */}
          <span className="ml-1 font-mono text-xs tabular-nums text-white/80">
            {canli != null ? saatBicimi(new Date(Date.now() - (canli - now) * 1000)) : sureBicimi(now)}
          </span>

          {canli != null && (
            <button
              type="button"
              onClick={onGoLive}
              disabled={!gerideKaldi}
              title={gerideKaldi ? 'Canlıya dön' : 'Canlı yayındasınız'}
              className={cn(
                'mx-auto flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition-colors',
                gerideKaldi
                  ? 'cursor-pointer bg-white/15 text-white/80 hover:bg-white/25'
                  : 'cursor-default bg-white/10 text-white',
              )}
            >
              {/* Nokta canlıyken kırmızı, gerideyken sönük: rozetin kendisi
                  her iki durumda da görünüyor ve rengi tek ayırt edici. */}
              <span
                className={cn(
                  'size-1.5 rounded-full',
                  gerideKaldi ? 'bg-white/40' : 'bg-status-live',
                )}
              />
              CANLI
            </button>
          )}

          <div className={cn('flex items-center gap-1', canli == null && 'ml-auto')}>
            <AltDugme
              label="Küçük pencere"
              onClick={() => {
                // Tarayıcı desteklemiyorsa ya da izin vermiyorsa sessizce
                // geçiliyor: kullanıcıya gösterilecek bir eylem yok.
                void video.requestPictureInPicture?.().catch(() => {})
              }}
            >
              <PictureInPicture2Icon className="size-4" />
            </AltDugme>
            <AltDugme
              label="Tam ekran"
              onClick={() => {
                const kutu = container ?? video
                if (document.fullscreenElement) void document.exitFullscreen()
                else void kutu.requestFullscreen?.().catch(() => {})
              }}
            >
              <MaximizeIcon className="size-4" />
            </AltDugme>
          </div>
        </div>
      </div>
    </div>
  )
}

/** Ortadaki yuvarlak düğmeler — oynat ve atlama. */
function OrtaDugme({
  children,
  label,
  onClick,
  buyuk = false,
}: {
  children: React.ReactNode
  label: string
  onClick: () => void
  buyuk?: boolean
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      className={cn(
        // pointer-events-auto: kapsayıcı katman tıklamayı geçirmiyor,
        // düğmelerin tek tek geri açması gerekiyor.
        'pointer-events-auto grid place-items-center rounded-full bg-black/45 text-white',
        'transition-colors hover:bg-black/65',
        buyuk ? 'size-20' : 'size-14',
      )}
    >
      {children}
    </button>
  )
}

/** Alt çubuktaki küçük ikon düğmeler. */
function AltDugme({
  children,
  label,
  onClick,
}: {
  children: React.ReactNode
  label: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      onClick={onClick}
      className="grid size-8 place-items-center rounded-lg text-white/85 transition-colors hover:bg-white/15 hover:text-white"
    >
      {children}
    </button>
  )
}

/**
 * Atlama adımı.
 *
 * <p>10 saniye, tasarımdaki değer. Canlı yayında zaten daha fazlası mümkün
 * değil: atlanabilir aralık ~14 saniye ve 15 sn geri gitmek aralığın dışına
 * düşerdi. Daha uzun geri sarma {@code LiveRewind} üzerinden DVR'dan geliyor.
 */
const ATLAMA_SN = 10

/** Yayın anı — canlıda saat göstermek "ne zamanı izliyorum" sorusunu cevaplıyor. */
function saatBicimi(d: Date): string {
  return d.toLocaleTimeString('tr-TR', { hour12: false })
}

/** Geçen süre — geri sarılmış bölümde. */
function sureBicimi(saniye: number): string {
  if (!Number.isFinite(saniye)) return '--:--'
  const s = Math.max(0, Math.floor(saniye))
  const dk = Math.floor(s / 60)
  return `${String(dk).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
