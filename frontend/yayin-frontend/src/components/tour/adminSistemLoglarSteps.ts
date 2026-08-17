import type { TourStep } from './steps'

export const ADMIN_SISTEM_LOGLAR_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.yonetim-sistem-loglari'

export const ADMIN_SISTEM_LOGLAR_TOUR_STEPS: TourStep[] = [
  {
    target: 'sistemlog-seviye-filtre',
    title: 'Seviye filtresi',
    body:
      'Yalnızca Hata, Uyarı, Başarı ya da Bilgi seviyesindeki olayları görmek için '
      + 'buradan süzün — bir kesinti sırasında gürültüyü azaltıp doğrudan hatalara '
      + 'odaklanmak için kullanışlı.',
    placement: 'bottom',
  },
  {
    target: 'sistemlog-servis-filtre',
    title: 'Servise göre ara',
    body:
      'Örneğin yalnızca "triton" ya da "video-worker" yazıp o servisin loglarını görün. '
      + 'Konteyner adının bir kısmını yazmak yeterli, tam eşleşme gerekmez.',
    placement: 'bottom',
  },
  {
    target: 'sistemlog-tablo',
    title: 'Türkçeye yorumlanmış loglar',
    body:
      'Bu ekran ham "docker logs" çıktısı değil — health-check tekrarları, erişim '
      + 'kayıtları gibi rutin gürültü bilerek süzülüyor. Yalnızca bilinen bir hata/uyarı/'
      + 'başarı örüntüsüne uyan satırlar, anlaşılır Türkçe cümlelere çevrilmiş halde '
      + 'burada görünür.',
    placement: 'top',
  },
  {
    target: 'sistemlog-seviye-sutun',
    title: 'Seviye rozetleri ve ham log',
    body:
      'Kırmızı Hata, turuncu Uyarı, yeşil Başarı, gri Bilgi demek. Meraklıysanız bir '
      + 'satıra tıklayın — konteynerin gönderdiği orijinal, ham log satırı altında açılır.',
    placement: 'left',
  },
]
