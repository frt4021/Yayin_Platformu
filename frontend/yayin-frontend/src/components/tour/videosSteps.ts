import type { TourStep } from './steps'

export const VIDEOS_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.videolar'

export const VIDEOS_TOUR_STEPS: TourStep[] = [
  {
    target: 'videolar-arama',
    title: 'Video arama',
    body: 'Kütüphane büyüdükçe başlıkta arama yapıp aradığınız videoyu hızlıca bulun.',
    placement: 'bottom',
  },
  {
    target: 'videolar-yukle',
    title: 'Video yükleme',
    body:
      'Yükleme yalnızca Yönetici ve Moderatör rolünde. Dosya doğrudan depolamaya '
      + 'akar, işlenip hazır olduğunda kütüphanede görünür.',
    placement: 'bottom',
  },
  {
    target: 'videolar-izgara',
    title: 'Video kütüphanesi',
    body:
      'Paylaşılan bir arşiv — giriş yapmış herkes tüm videoları izleyebilir. '
      + 'Bir karta tıklamak oynatmayı başlatır.',
    placement: 'top',
  },
  {
    target: 'videolar-cc',
    title: 'Altyazı göstergesi',
    body: '"CC" rozeti, o video için altyazı hazır olduğunu gösterir — oynatıcıda dil seçilebilir.',
    placement: 'top',
  },
  {
    target: 'videolar-eylemler',
    title: 'Düzenleme ve silme',
    body:
      'Karta gelince görünür. Yalnızca kendi yüklediğiniz videolarda (Yönetici '
      + 'için tüm videolarda) çalışır.',
    placement: 'top',
  },
]
