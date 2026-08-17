import type { TourStep } from './steps'

export const ADMIN_ANALITIK_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.yonetim-analitik'

export const ADMIN_ANALITIK_TOUR_STEPS: TourStep[] = [
  {
    target: 'analitik-canli-durum',
    title: 'Canlı sistem durumu',
    body:
      'Şu anki eşzamanlı izleyici/dinleyici sayısı ve devam eden DVR kayıtları — anlık, '
      + 'sayfa yenilenmeden değişmez. "Anlık trafik" gibi henüz ölçüm altyapısı olmayan '
      + 'alanlar sessizce sıfır göstermek yerine açıkça "ölçülmüyor" yazar.',
    placement: 'bottom',
  },
  {
    target: 'analitik-icerik',
    title: 'İçerik ve kanal performansı',
    body:
      'En çok izlenen kanallar, en çok dinlenen radyolar ve en çok kaydedilen yayınlar — '
      + 'hangi içeriğin gerçekten talep gördüğünü gösterir, yayın planlaması için kullanılabilir.',
    placement: 'top',
  },
  {
    target: 'analitik-depolama',
    title: 'Depolama ve DVR',
    body:
      'Toplam DVR boyutu ve kotasına en çok yaklaşan kullanıcılar burada. Depolama '
      + 'darboğazı yaşanmadan önce kimin/neyin yer kapladığını görmek için buraya bakın.',
    placement: 'top',
  },
  {
    target: 'analitik-teknik',
    title: 'Teknik ve hata takibi',
    body:
      'Başarısız planlı kayıtlar, video işleme hataları ve yayın kopma oranı — bir şey '
      + 'sessizce bozulduğunda önce burada görünür.',
    placement: 'top',
  },
  {
    target: 'analitik-genel',
    title: 'Genel kullanıcı aktivitesi',
    body:
      'DAU/MAU (günlük/aylık aktif kullanıcı) ve en yoğun giriş saati — platformun genel '
      + 'kullanım yoğunluğunu ve zamanlamasını özetler.',
    placement: 'top',
  },
  {
    target: 'analitik-video',
    title: 'Video tamamlanma ve ısı haritası',
    body:
      'Her videonun kaç kez izlendiği ve ne kadarının tamamlandığı. Bir satıra tıklayınca '
      + 'altta videonun 10 dilimlik izlenme ısı haritası açılır — izleyicilerin videonun '
      + 'hangi bölümünde daha çok kaldığını/ayrıldığını gösterir.',
    placement: 'top',
  },
]
