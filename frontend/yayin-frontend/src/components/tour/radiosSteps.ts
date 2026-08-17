import type { TourStep } from './steps'

export const RADIOS_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.radyolar'

export const RADIOS_TOUR_STEPS: TourStep[] = [
  {
    target: 'radyo-arama',
    title: 'İstasyon arama',
    body:
      'İstasyon sayısı arttıkça aradığınızı buradan süzün. Çalan istasyon aramaya '
      + 'rağmen listeden kaybolmaz, aksi halde durdurma düğmesi de kaybolurdu.',
    placement: 'bottom',
  },
  {
    target: 'radyo-ekle',
    title: 'Yeni radyo',
    body:
      'İki kaynak türü var: "Doğrudan" (adres zaten HLS/MediaMTX kaynağı sayılır) ve '
      + '"Köprü" (MediaMTX içinde bir ffmpeg süreci, örneğin Icecast MP3 akışını AAC\'ye '
      + 'kodlar). Yanlış seçim görünür bir hata vermez, istasyon sessizce yayına girmez.',
    placement: 'bottom',
  },
  {
    target: 'radyo-geri-yukle',
    title: 'Geri yükle',
    body:
      'MediaMTX bağımsız olarak yeniden başlarsa tanımlı path\'lerini unutur. Bu düğme '
      + 'tüm aktif radyoları ona yeniden yazar — normalde gerekmez.',
    placement: 'bottom',
  },
  {
    target: 'radyo-liste',
    title: 'İstasyonlar',
    body:
      'Bir karta tıklamak dinlemeye başlatır, tekrar tıklamak duraklatır. Aynı anda '
      + 'yalnızca bir istasyon çalar — yeni birini seçmek öncekini durdurur.',
    placement: 'top',
  },
  {
    target: 'radyo-durum',
    title: 'Yayın durumu',
    body:
      '"Bilinmiyor" MediaMTX\'e o an ulaşılamadığı anlamına gelir, istasyonun kendisi '
      + 'yayında olabilir — bir arıza göstergesi değil. Dinleyici sayısı yalnızca '
      + 'gerçekten dinleyeni olan istasyonlarda görünür.',
    placement: 'right',
  },
]
