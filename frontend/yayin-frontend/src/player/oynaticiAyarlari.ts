import { api } from '@/api/client'

/**
 * Oynatıcının sunucudan gelen ayarları.
 *
 * <h2>Neden derlemeye gömülmüyor</h2>
 * Bu değerlerin **ölçerek** bulunması gerekiyor ve her denemede
 * `docker compose build frontend` çalıştırmak makul değil. Backend `.env`'i
 * zaten okuyor; buradan tek seferlik alınıyor.
 *
 * <h2>Neden modül değişkeni, React state değil</h2>
 * `HlsPlayer` ayarı **hls.js kurulurken** okuyor ve kurulum bir kez oluyor.
 * State'ten gelseydi ilk kurulum varsayılanla yapılır, doğru değer geldiğinde
 * oynatıcının yeniden kurulması gerekirdi — canlı yayında görünür bir kesinti.
 *
 * Uygulama açılışında bir kez {@link ayarlariYukle} çağrılıyor; ondan sonra
 * okuma senkron.
 */

/**
 * İzleyicinin canlı kenardan kaç bölüt geriden izleyeceği.
 *
 * **Altyazının bütçesi bu.** Ayrıntı `application.properties`
 * → `altyazi.hls-geride`.
 *
 * Varsayılan 8: sunucuya ulaşılamazsa bile altyazının yetişebileceği bir
 * değerle başlanıyor. 3 (eski sabit) yazsaydık, ayar ucu bir sebeple
 * cevap vermediğinde altyazı sessizce görünmez olurdu.
 */
let hlsGeride = 8

export function hlsGerideOku(): number {
  return hlsGeride
}

/**
 * Ayarları sunucudan alır. Uygulama kurulmadan önce bir kez çağrılıyor.
 *
 * <p>Hata **yutuluyor**: ayar ucuna ulaşılamaması uygulamanın açılmasını
 * engellememeli. Varsayılanlarla devam ediliyor.
 */
export async function ayarlariYukle(): Promise<void> {
  try {
    const gelen = await api.get<{ hlsGeride: number }>('/api/ayarlar/oynatici')
    // Sifir ve negatif REDDEDILIYOR: hls.js'te kullanici ayari verilmemis
    // sayilir ve sunucunun PART-HOLD-BACK=0.5 onerisine duser -- butce yarim
    // saniyeye iner ve altyazi hicbir kosulda yetismez.
    if (Number.isFinite(gelen.hlsGeride) && gelen.hlsGeride > 0) {
      hlsGeride = gelen.hlsGeride
    }
  } catch {
    // Varsayilanlarla devam.
  }
}
