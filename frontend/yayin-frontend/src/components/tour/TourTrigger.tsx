import { HelpCircleIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Sayfa turunu elle yeniden başlatma düğmesi.
 *
 * <p>Tur ilk girişte otomatik açılıp bir daha kendiliğinden açılmıyor
 * (bkz. {@code usePageTour}) — bunu unutan/kaçıran bir kullanıcının turu
 * tekrar görebilmesi için her sayfada aynı yerde, aynı görünümde bir çıkış
 * noktası gerekiyor.
 *
 * <p><b>Sağ alt köşede sabit (floating):</b> başlığın yanında olsaydı sayfa
 * kaydırıldığında gözden kaybolurdu — sabit konum, turu her an, kaydırma
 * durumundan bağımsız erişilebilir kılıyor. {@code z-40}: radyo çubuğunun
 * ({@code z-50}, ekranın altını kaplıyor) altında kalıyor, üstüne binmiyor;
 * turun kendi katmanının ({@code z-60}) altında kalıyor, tur açıkken bu
 * düğmeye tıklanamaz olması sorun değil çünkü tur zaten kendi kapatma
 * yollarını taşıyor.
 */
export function TourTrigger({ onClick, className }: { onClick: () => void; className?: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      title="Bu sayfanın turunu göster"
      aria-label="Bu sayfanın turunu göster"
      className={cn(
        'fixed bottom-6 right-6 z-40 grid size-11 place-items-center rounded-full',
        'bg-primary text-primary-foreground shadow-lg transition-transform hover:scale-105',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2',
        className,
      )}
    >
      <HelpCircleIcon className="size-5" />
    </button>
  )
}
