import type { TourStep } from './steps'

export const ADMIN_USERS_TOUR_SEEN_KEY = 'yayin-merkezi.tur-gorundu.yonetim-kullanicilar'

export const ADMIN_USERS_TOUR_STEPS: TourStep[] = [
  {
    target: 'arama',
    title: 'Kullanıcı arama',
    body:
      'Kullanıcı adı, ad, soyad veya e-posta ile süzün. Her tuşa basışta değil, kısa '
      + 'bir gecikmeyle istek atılır — yazarken sunucuyu gereksiz yormaz.',
    placement: 'bottom',
  },
  {
    target: 'yeni-kullanici',
    title: 'Yeni kullanıcı',
    body:
      'Keycloak\'ta doğrudan bir hesap oluşturur — kullanıcı adı, e-posta, geçici ya da '
      + 'kalıcı şifre ve başlangıç rolünü burada belirlersiniz.',
    placement: 'left',
  },
  {
    target: 'esitle',
    title: 'Keycloak ile eşitle',
    body:
      'Keycloak konsolundan yapılan değişiklikler (yeni kullanıcı, silme) buraya '
      + 'otomatik yansımaz — bu düğme yerel tabloyu Keycloak\'la karşılaştırıp farkları '
      + 'uygular. Keycloak\'ta silinmiş ama yerelde kalan kayıtlar otomatik silinmez, '
      + 'sonuçta ayrıca bildirilir.',
    placement: 'bottom',
  },
  {
    target: 'kullanici-adi',
    title: 'Kullanıcı bilgi ekranı',
    body:
      'Bir kullanıcının adına tıklayınca, izlediği kanallar/radyolar ve genel '
      + 'aktivitesini gösteren bir bilgi ekranı açılır — satırdan çıkmadan hızlı bir '
      + 'özet almanın yolu budur.',
    placement: 'right',
  },
  {
    target: 'kullanici-tablosu',
    title: 'Kullanıcı listesi',
    body:
      'Rol sütunundaki açılır menüden anında rol değiştirebilirsiniz — ayrı bir '
      + 'kaydet düğmesi yok, seçim yapınca hemen uygulanır.',
    placement: 'top',
  },
  {
    target: 'kullanici-islemler',
    title: 'Şifre sıfırlama ve silme',
    body:
      'Her satırda o kullanıcıya özel işlemler: şifre sıfırlama ve hesabı kalıcı olarak '
      + 'silme. Kendi hesabınızı silemezsiniz — düğme bilerek kapatılmış, sunucu zaten '
      + 'reddediyor.',
    placement: 'left',
  },
]
