import type { TourStep } from './steps'

export const GALERI_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.galeri'

export const GALERI_TOUR_STEPS: TourStep[] = [
  {
    target: 'galeri-filtre',
    title: 'Kanala göre süz',
    body: 'Bir kanal seçerek yalnızca o kanaldan alınan kareleri görün.',
    placement: 'bottom',
  },
  {
    target: 'galeri-izgara',
    title: 'Ekran görüntüleri',
    body:
      'Kareler güne göre gruplanır, en yeni en üstte. İzleme ekranındaki kamera '
      + 'düğmesiyle yakaladığınız her kare burada birikir.',
    placement: 'top',
  },
  {
    target: 'galeri-eylemler',
    title: 'İndir ve sil',
    body: 'Bir karenin üzerine gelince indirme ve silme düğmeleri belirir.',
    placement: 'left',
  },
]
