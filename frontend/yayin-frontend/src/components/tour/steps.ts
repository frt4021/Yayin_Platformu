/**
 * Rehberli tur adımları.
 *
 * <h2>Neden hedefler CSS seçici değil, veri özniteliği</h2>
 * Sınıf adları Tailwind'de sürekli değişiyor ve bir sınıfı düzenleyen kişi
 * turu bozduğunu fark edemezdi. {@code data-tour="..."} açık bir sözleşme:
 * özniteliği silen, turun o adımını sildiğini görüyor.
 *
 * <h2>Neden hepsi İzleme sayfasında</h2>
 * Tur, rota değiştirerek ilerleseydi her adımda sayfa yüklenmesini beklemek
 * ve hedefin gelmesini yoklamak gerekirdi. Ürünün merkezi zaten bu sayfa;
 * diğer sayfalar buradaki gezinmeden bir tık uzakta ve tur onu gösteriyor.
 */
export interface TourStep {
  /** {@code data-tour} değeri. Hedef bulunamazsa adım <b>atlanıyor</b>. */
  target: string
  title: string
  body: string
  /** Balonun hedefe göre tercih edilen yönü; sığmazsa otomatik çevriliyor. */
  placement?: 'top' | 'bottom' | 'left' | 'right'
}

export const TOUR_STEPS: TourStep[] = [
  {
    target: 'nav',
    title: 'Gezinme',
    body:
      'Kanallar, radyolar, geriye sarma, klipler ve video kütüphanesi buradan. '
      + 'Şu an İzleme sayfasındasınız — yayınları burada izliyorsunuz.',
    placement: 'right',
  },
  {
    target: 'arama',
    title: 'Kanal arama',
    body:
      'Kanal sayısı arttığında aradığınızı buradan süzün. Açık olan kanallar '
      + 'aramaya rağmen listede kalır, böylece kapatma düğmeleri kaybolmaz.',
    placement: 'bottom',
  },
  {
    target: 'kanal-cipleri',
    title: 'Kanal seçimi',
    body:
      'Bir kanala tıklayınca karosu açılır, tekrar tıklayınca kapanır. '
      + 'Yeşil olanlar açık. Aynı anda en fazla 16 karo açılabilir.',
    placement: 'bottom',
  },
  {
    target: 'toplu-eylemler',
    title: 'Toplu açma',
    body: 'Yayındaki tüm kanalları tek seferde açar ya da hepsini kapatır.',
    placement: 'bottom',
  },
  {
    target: 'karo-alani',
    title: 'İzleme alanı',
    body:
      'Karolar açık kanal sayısına göre kendiliğinden diziliyor. Bir karoya '
      + 'tıklamak onu büyütür; tekrar tıklamak ızgaraya döndürür.',
    placement: 'top',
  },
  {
    target: 'karo-eylemleri',
    title: 'Karo denetimleri',
    body:
      'Kayıt başlatma, ekran görüntüsü alma, altyazı dili, çözünürlük ve ses '
      + 'odağı. Altyazı dili her karo için ayrı — farklı kanalları farklı '
      + 'dilde izleyebilirsiniz.',
    placement: 'bottom',
  },
  {
    target: 'yayin-paneli',
    title: 'Etkin yayın',
    body:
      'Yayındaki kanalların durumu: çözünürlük, hangisi açık, hangisinde ses '
      + 'var. Kaynağı düşmüş kanallar "bekliyor" olarak görünür.',
    placement: 'left',
  },
  {
    target: 'hesap',
    title: 'Hesabınız',
    body:
      'Rolünüz burada yazıyor ve ne yapabileceğinizi o belirliyor. Turu '
      + 'istediğiniz zaman Profilim sayfasından yeniden başlatabilirsiniz.',
    placement: 'top',
  },
]

/** localStorage anahtarı — tur bir kez gösterilip bırakılıyor. */
export const TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu'
