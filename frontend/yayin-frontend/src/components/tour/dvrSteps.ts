import type { TourStep } from './steps'

export const DVR_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.geriye-sarma'

export const DVR_TOUR_STEPS: TourStep[] = [
  {
    target: 'dvr-kanallar',
    title: 'Kanal seçimi',
    body:
      'Yalnızca geriye sarması açık kanallar burada listelenir. Bir kanala '
      + 'tıklayınca zaman çizelgesi ve önizleme o kanala göre yenilenir.',
    placement: 'bottom',
  },
  {
    target: 'dvr-pencere',
    title: 'Zaman aralığı',
    body:
      'Çizelgenin ne kadar geriye gideceğini seçin — son 1 saatten son 7 güne '
      + 'kadar (kayıt saklama süresiyle aynı). Kısa pencere, uzun bir kaydı '
      + 'saniye saniye görmek için daha kullanışlı.',
    placement: 'bottom',
  },
  {
    target: 'dvr-zaman-cizelgesi',
    title: 'Zaman çizelgesi',
    body:
      'Kayıtlı bölümleri gösterir. Bir noktaya tıklayıp o andan izleyin ya da '
      + 'sürükleyerek bir aralık seçin — seçim, klip almanın ilk adımı.',
    placement: 'top',
  },
  {
    target: 'dvr-onizleme',
    title: 'Önizleme',
    body:
      'Seçtiğiniz aralık burada oynatılır. Alttaki şerit, seçimin başından '
      + 'sonuna eşit aralıklı kareler gösterir — neyi kırptığınızı oynatmadan '
      + 'da anlayabilirsiniz. Bir kareye tıklamak o andan oynatır.',
    placement: 'left',
  },
  {
    target: 'dvr-secim',
    title: 'Seçilen aralık',
    body:
      'Seçimin başlangıcı, bitişi, süresi ve tahmini boyutu burada. "İnce ayar" '
      + 'düğmeleriyle uçları saniye saniye kaydırabilir, hazır olunca "Klip '
      + 'oluştur"a basabilirsiniz — klip arka planda üretilir.',
    placement: 'left',
  },
  {
    target: 'dvr-planli-kayit',
    title: 'Planlı kayıt',
    body:
      'Çizelge yalnızca geçmişi gösterir — gelecekteki bir yayın aralığını '
      + 'önceden ayırtmak için bu formu kullanın. Bir seçiminiz varsa '
      + 'başlangıç/bitiş oradan otomatik doldurulur.',
    placement: 'top',
  },
]
