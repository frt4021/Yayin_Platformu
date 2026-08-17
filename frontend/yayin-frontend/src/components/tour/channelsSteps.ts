import type { TourStep } from './steps'

export const CHANNELS_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.kanallar'

export const CHANNELS_TOUR_STEPS: TourStep[] = [
  {
    target: 'kanal-kapasite',
    title: 'Yayın kapasitesi',
    body:
      'Aynı anda kaç kanalın yayında olabileceğinin üst sınırı. Doluluk bu sınıra '
      + 'yaklaştıkça rozet uyarı rengine döner — yeni bir kanalı aktif etmeden önce '
      + 'buraya bakmak reddedilme sürprizini önler.',
    placement: 'bottom',
  },
  {
    target: 'kanal-ekle',
    title: 'Yeni kanal',
    body:
      'Kaynak adresi, MediaMTX path\'i ve isteğe bağlı rendition\'ları (720p/480p gibi '
      + 'kalite seçenekleri) burada tanımlanır. Kanal "aktif" işaretlenirse yayın hemen '
      + 'çekilmeye başlar.',
    placement: 'bottom',
  },
  {
    target: 'kanal-yeniden-yaz',
    title: 'MediaMTX\'e yeniden yaz',
    body:
      'MediaMTX bağımsız olarak yeniden başlarsa tanımlı path\'lerini unutur. Bu düğme '
      + 'tüm aktif kanalları ona yeniden yazar — normalde gerekmez, yalnızca yayın '
      + 'sessizce durduysa ve MediaMTX\'in yeniden başladığından şüpheleniyorsanız kullanın.',
    placement: 'bottom',
  },
  {
    target: 'kanal-tablo',
    title: 'Kanal listesi',
    body:
      'Durum sütunu MediaMTX\'ten anlık okunuyor, 15 saniyede bir tazeleniyor. '
      + '"Bilinmiyor" MediaMTX\'e o an ulaşılamadığı anlamına gelir — kanalın kendisi '
      + 'yayında olabilir, bu bir arıza göstergesi değil.',
    placement: 'top',
  },
  {
    target: 'kanal-islemler',
    title: 'Kanal işlemleri',
    body:
      'HLS adresini kopyalayıp doğrudan paylaşabilirsiniz. Düzenle ve sil işlemleri '
      + 'yalnızca yönetici ve moderatörlere görünür — silme işlemi klip, ekran görüntüsü '
      + 've DVR kayıtlarının kaderini ayrı ayrı sormanızı sağlayan bir onay adımından geçer.',
    placement: 'left',
  },
]
