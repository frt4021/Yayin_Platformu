import type { TourStep } from './steps'

export const PROFILE_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.profil'

export const PROFILE_TOUR_STEPS: TourStep[] = [
  {
    target: 'profil-hesap',
    title: 'Hesap bilgileriniz',
    body: 'Kullanıcı adınız, e-postanız ve rolünüz — rolünüz neyi yapıp yapamayacağınızı belirler.',
    placement: 'bottom',
  },
  {
    target: 'profil-sifre',
    title: 'Şifre değiştirme',
    body: 'Güvenlik için mevcut şifreniz doğrulanmadan yenisi kabul edilmez.',
    placement: 'top',
  },
  {
    target: 'profil-kota',
    title: 'Depolama kotası',
    body:
      'Klip, ekran görüntüsü ve videolarınızın toplam kapladığı yer. Kota dolunca yeni '
      + 'işler reddedilir ama var olan içerik silinmez — hangisini sileceğinize siz karar verirsiniz.',
    placement: 'top',
  },
]
