import { useCallback, useEffect, useLayoutEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { XIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { TOUR_STEPS, type TourStep } from './steps'

/** Vurgulanan alanın çevresine bırakılan boşluk. */
const PADDING = 8

/** Balon ile hedef arasındaki mesafe. */
const GAP = 14

const BALON_W = 340

/**
 * Rehberli tur.
 *
 * <h2>Neden kütüphane kullanılmadı</h2>
 * İhtiyaç dar: bir alanı vurgula, yanına balon koy, ileri/geri. Hazır turlar
 * (Joyride, Shepherd) bunun için kendi konumlandırma motorlarını, tema
 * katmanlarını ve React sürüm bağımlılıklarını getiriyor. React 19'da
 * uyumları belirsiz ve bu kadarlık bir iş için ölçüsüz.
 *
 * <h2>Vurgu nasıl yapılıyor</h2>
 * SVG maskesi ya da dört ayrı örtü yerine <b>tek bir kutu ve devasa bir
 * gölge</b>:
 *
 * <pre>box-shadow: 0 0 0 9999px rgba(0,0,0,.72)</pre>
 *
 * Kutunun kendisi saydam; gölge ekranın kalanını kaplıyor. Böylece delik her
 * zaman tam hizalı oluyor — dört parçalı örtüde parçalar arasında bir piksel
 * kayma kalıyordu.
 *
 * <h2>Neden portal</h2>
 * Yan çubuklar {@code z-20}, oynatıcı katmanı {@code z-10}, iletişim
 * kutuları {@code z-50}. Tur bunların hepsinin üstünde olmalı ve
 * {@code body}'ye taşınmadan bir ata elemanın {@code overflow} ya da
 * {@code transform}'u onu kırpabilirdi.
 */
export function GuidedTour({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [adim, setAdim] = useState(0)
  const [kutu, setKutu] = useState<DOMRect | null>(null)
  const [adimlar, setAdimlar] = useState<TourStep[]>([])

  /**
   * Hangi adımların gösterileceği <b>açılışta bir kez</b> belirleniyor.
   *
   * <p>Hedefi olmayan adımlar eleniyor: bir öğe o an ekranda olmayabilir
   * (yayında kanal yoksa karo eylemleri yok) ve boş bir balon göstermek turu
   * anlamsızlaştırırdı.
   *
   * <p><b>Dondurulması şart.</b> Liste her render'da DOM'dan hesaplansaydı,
   * tur sırasında bir karo açıldığında dizi uzar ve {@code adim} indeksi
   * bambaşka bir adımı gösterirdi — kullanıcı "İleri"ye bastığında geri
   * gitmiş gibi görünürdü.
   */
  useEffect(() => {
    if (!open) return
    setAdimlar(TOUR_STEPS.filter((s) => document.querySelector(`[data-tour="${s.target}"]`)))
    setAdim(0)
  }, [open])

  const mevcut: TourStep | undefined = adimlar[adim]

  const olc = useCallback(() => {
    if (!mevcut) return
    const el = document.querySelector(`[data-tour="${mevcut.target}"]`)
    setKutu(el ? el.getBoundingClientRect() : null)
  }, [mevcut])

  // useLayoutEffect: ölçüm boyamadan ÖNCE yapılmalı, yoksa balon ilk karede
  // yanlış yerde görünüp sonra zıplıyor.
  useLayoutEffect(() => {
    if (!open) return
    olc()
    const el = mevcut && document.querySelector(`[data-tour="${mevcut.target}"]`)
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [open, adim, olc, mevcut])

  useEffect(() => {
    if (!open) return
    window.addEventListener('resize', olc)
    // Yakalama aşamasında: kaydırma iç kapsayıcılarda oluyor ve olay
    // window'a kabarmıyor.
    window.addEventListener('scroll', olc, true)
    return () => {
      window.removeEventListener('resize', olc)
      window.removeEventListener('scroll', olc, true)
    }
  }, [open, olc])

  const kapat = useCallback(() => {
    setAdim(0)
    onClose()
  }, [onClose])

  useEffect(() => {
    if (!open) return
    const tus = (e: KeyboardEvent) => {
      if (e.key === 'Escape') kapat()
      if (e.key === 'ArrowRight') setAdim((a) => Math.min(a + 1, adimlar.length - 1))
      if (e.key === 'ArrowLeft') setAdim((a) => Math.max(a - 1, 0))
    }
    window.addEventListener('keydown', tus)
    return () => window.removeEventListener('keydown', tus)
  }, [open, kapat, adimlar.length])

  if (!open || !mevcut) return null

  const sonAdim = adim === adimlar.length - 1

  return createPortal(
    <div className="fixed inset-0 z-[60]">
      {/* Vurgu. Hedef ölçülemezse (öğe kayboldu) yalnızca karartma kalıyor. */}
      {kutu && (
        <div
          className="pointer-events-none absolute rounded-xl ring-2 ring-primary transition-all duration-200"
          style={{
            top: kutu.top - PADDING,
            left: kutu.left - PADDING,
            width: kutu.width + PADDING * 2,
            height: kutu.height + PADDING * 2,
            boxShadow: '0 0 0 9999px rgba(0,0,0,0.72)',
          }}
        />
      )}
      {!kutu && <div className="absolute inset-0 bg-black/72" />}

      {/* Karartmaya tıklamak turu kapatıyor — kullanıcı her an çıkabilmeli. */}
      <button
        type="button"
        aria-label="Turu kapat"
        className="absolute inset-0 cursor-default"
        onClick={kapat}
      />

      <div
        role="dialog"
        aria-label={mevcut.title}
        className="absolute w-[340px] rounded-2xl border bg-card p-4 shadow-2xl"
        style={konumla(kutu, mevcut.placement)}
      >
        <div className="flex items-start justify-between gap-3">
          <h3 className="font-semibold">{mevcut.title}</h3>
          <button
            type="button"
            onClick={kapat}
            aria-label="Turu kapat"
            className="-mr-1 -mt-1 rounded-lg p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <XIcon className="size-4" />
          </button>
        </div>

        <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{mevcut.body}</p>

        <div className="mt-4 flex items-center justify-between gap-3">
          {/* Nokta göstergesi: kaç adım kaldığını görmek, "bu daha ne kadar
              sürecek" sorusunu baştan cevaplıyor. */}
          <div className="flex gap-1.5">
            {adimlar.map((s, i) => (
              <span
                key={s.target}
                className={cn(
                  'size-1.5 rounded-full transition-colors',
                  i === adim ? 'bg-primary' : 'bg-border',
                )}
              />
            ))}
          </div>

          <div className="flex gap-2">
            {adim > 0 && (
              <Button size="sm" variant="ghost" onClick={() => setAdim(adim - 1)}>
                Geri
              </Button>
            )}
            <Button size="sm" onClick={() => (sonAdim ? kapat() : setAdim(adim + 1))}>
              {sonAdim ? 'Bitir' : 'İleri'}
            </Button>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  )
}

/**
 * Balonu hedefin yanına yerleştirir.
 *
 * <p>Tercih edilen yön ekrana sığmıyorsa karşısına çevriliyor; yatayda ve
 * dikeyde ayrıca pencere içine sıkıştırılıyor. Sabit bir yön kullanılsaydı
 * kenardaki hedeflerde balonun yarısı ekran dışında kalırdı.
 */
function konumla(
  kutu: DOMRect | null,
  yon: TourStep['placement'] = 'bottom',
): React.CSSProperties {
  if (!kutu) {
    return { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' }
  }

  const vw = window.innerWidth
  const vh = window.innerHeight
  // Yükseklik önceden bilinemiyor (metin uzunluğu değişken); ölçülen
  // örneklerde 190-230 px arasında. Sığma hesabında üst sınır kullanılıyor.
  const tahminiY = 230

  let ustSinir: number
  let solSinir: number

  const yatay = yon === 'left' || yon === 'right'
  if (yatay) {
    const sagaSigar = kutu.right + GAP + BALON_W < vw
    const gercekYon = yon === 'right' && !sagaSigar ? 'left' : yon
    solSinir = gercekYon === 'right' ? kutu.right + GAP : kutu.left - GAP - BALON_W
    ustSinir = kutu.top + kutu.height / 2 - tahminiY / 2
  } else {
    const altaSigar = kutu.bottom + GAP + tahminiY < vh
    const gercekYon = yon === 'bottom' && !altaSigar ? 'top' : yon
    ustSinir = gercekYon === 'bottom' ? kutu.bottom + GAP : kutu.top - GAP - tahminiY
    solSinir = kutu.left + kutu.width / 2 - BALON_W / 2
  }

  return {
    top: Math.min(Math.max(ustSinir, 12), vh - tahminiY - 12),
    left: Math.min(Math.max(solSinir, 12), vw - BALON_W - 12),
  }
}
