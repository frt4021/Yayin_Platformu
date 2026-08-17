import type { TourStep } from './steps'

export const CLIPS_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.klipler'

export const CLIPS_TOUR_STEPS: TourStep[] = [
  {
    target: 'klip-filtre',
    title: 'Klip türü',
    body:
      'Aynı tabloda iki farklı üretim yolu var: "Aralık seçimi" geriye sarma '
      + 'sayfasında bir zaman aralığı seçilerek, "Kayıtlarım" ise canlı '
      + 'izlerken başlat/durdur ile alınan kayıtlar. Buradan süzebilirsiniz.',
    placement: 'bottom',
  },
  {
    target: 'klip-tablo',
    title: 'Klip listesi',
    body:
      'Klipler kanala göre gruplanır, her grupta en yeni önce gelir. '
      + 'Kanalı silinmiş kliplerin grubu "silinmiş kanal" etiketiyle en sona '
      + 'düşer — bunlar arşiv, aktif bir iş değil.',
    placement: 'top',
  },
  {
    target: 'klip-nasil',
    title: 'Nasıl üretildi',
    body: '"aralık" geriye sarmadan seçilerek, "kayıt" ise canlı izlerken manuel başlatılarak alındı.',
    placement: 'bottom',
  },
  {
    target: 'klip-durum',
    title: 'Durum',
    body:
      'Klip arka planda kuyrukta bekleyip işleniyor, hazır olunca yeşil '
      + '"Hazır" görürsünüz. "CC" rozeti o klipte altyazı bulunduğunu, '
      + 'hangi dillerde olduğunu üzerine gelince gösterir.',
    placement: 'bottom',
  },
  {
    target: 'klip-islem',
    title: 'İzle, indir, sil',
    body:
      'Klip hazır olmadan bu düğmeler pasif kalır. İzle ve indir bağlantıları '
      + 'süreli imzalıdır; sil, işlenmekte olan bir klip için kapalıdır.',
    placement: 'left',
  },
]
