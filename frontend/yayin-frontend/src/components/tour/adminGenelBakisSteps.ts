import type { TourStep } from './steps'

export const ADMIN_GENEL_BAKIS_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.yonetim-genel-bakis'

export const ADMIN_GENEL_BAKIS_TOUR_STEPS: TourStep[] = [
  {
    target: 'canli-durum',
    title: 'Canlı Durum',
    body:
      'Şu an sistemi kimler kullanıyor: eşzamanlı izleyici/dinleyici sayısı, devam eden '
      + 'DVR kaydı ve MediaMTX\'ten hesaplanan anlık ağ trafiği. Trafik ilk açılışta '
      + '"ölçülmüyor" görünebilir — hız hesaplamak için iki ardışık ölçüm gerekiyor, '
      + 'birkaç saniye sonra dolar.',
    placement: 'bottom',
  },
  {
    target: 'sistem-sagligi',
    title: 'Sistem Sağlığı',
    body:
      'Veritabanı, yayınlar, MediaMTX, MinIO, Triton, Keycloak ve Redis\'in erişilebilir '
      + 'olup olmadığını gösterir. Bu yalnızca "ulaşılabiliyor mu" sorusuna cevap veriyor '
      + '— gerçek sayısal değerler için aşağıdaki Servis Metrikleri\'ne bakın.',
    placement: 'bottom',
  },
  {
    target: 'servis-metrikleri',
    title: 'Servis Metrikleri',
    body:
      'Prometheus\'tan okunan gerçek sayılar: bağlantı sayıları, bellek kullanımı, '
      + 'gecikmeler. Bir servis Prometheus\'a hiç veri göndermemişse ya da Prometheus\'a '
      + 'ulaşılamıyorsa ilgili alan sessizce 0 göstermek yerine "ölçülmüyor" yazar.',
    placement: 'top',
  },
  {
    target: 'triton-model-gecikme',
    title: 'Dil bazlı çeviri gecikmesi',
    body:
      'Canlı altyazı hattının en kritik göstergesi: Whisper ve her hedef dilin '
      + '(Türkçe/Almanca/Rusça) ayrı ayrı ortalama gecikmesi. Biri aniden saniyelerce '
      + 'sürmeye başlarsa GPU\'nun kapasite sınırına dayandığının ilk işareti burada '
      + 'görünür — toplam ortalama bunu gizleyebilir, dil bazlı kırılım gizlemez.',
    placement: 'top',
  },
  {
    target: 'son-etkinlikler',
    title: 'Son Etkinlikler',
    body:
      'Kullanıcı davranışlarının denetim izi — kim ne zaman izlemeye başladı, giriş '
      + 'yaptı, klip aldı. Daha ayrıntılı filtreleme (tarih aralığı, kullanıcı, tür) '
      + 'için Etkinlikler sayfasına geçin.',
    placement: 'top',
  },
]
