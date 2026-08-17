import type { TourStep } from './steps'

export const ADMIN_ETKINLIKLER_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.yonetim-etkinlikler'

export const ADMIN_ETKINLIKLER_TOUR_STEPS: TourStep[] = [
  {
    target: 'etkinlik-tur-filtre',
    title: 'Tür filtresi',
    body:
      'Giriş/çıkış, izleme/dinleme oturumları, kanal/radyo/kullanıcı yönetimi, oynatma '
      + 'hataları — sistemdeki her kullanıcı ve içerik olayı burada tek bir türe göre '
      + 'süzülebilir.',
    placement: 'bottom',
  },
  {
    target: 'etkinlik-kullanici-filtre',
    title: 'Kullanıcıya göre ara',
    body:
      'Belirli bir kullanıcının tüm geçmişini görmek için kullanıcı adını buraya yazın. '
      + 'Tür filtresiyle birlikte kullanılabilir — örn. yalnızca bir kullanıcının '
      + 'başarısız giriş denemeleri.',
    placement: 'bottom',
  },
  {
    target: 'etkinlik-tablo',
    title: 'Denetim izi',
    body:
      'Her satır gerçekleştiği anda kaydedilmiş bir olay — sonradan değiştirilemez. '
      + '"Hedef" sütunu olayın hangi kanal/radyo/kullanıcı/videoyla ilgili olduğunu, '
      + '"Detay" sütunu ise türüne özel kısa bir özeti (örn. izleme süresi, eski/yeni rol) '
      + 'gösterir.',
    placement: 'top',
  },
  {
    target: 'etkinlik-sayfalama',
    title: 'Sayfalama',
    body:
      'Kayıtlar en yeniden eskiye, 50\'şer sayfa halinde geliyor. Filtre değiştirdiğinizde '
      + 'otomatik olarak ilk sayfaya dönülür.',
    placement: 'top',
  },
]
