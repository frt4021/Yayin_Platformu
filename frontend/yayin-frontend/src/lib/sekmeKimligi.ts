/**
 * {@code crypto.randomUUID} yalnızca "secure context"te var (HTTPS ya da
 * "localhost") — düz HTTP + LAN IP'yle açıldığında (ör. http://192.168.1.200:3000)
 * tanımsız kalıp "crypto.randomUUID is not a function" ile sayfayı çökertiyordu
 * (ÖLÇÜLDÜ: LAN IP'den bir kanala tıklayınca). Bu kimlik güvenlik amaçlı değil,
 * yalnızca sekmeyi ayırt etmek için — Math.random tabanlı üretim yeterli.
 */
function uuidUret(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * Bu tarayıcı SEKMESİNE özgü kalıcı kimlik.
 *
 * <p>{@code sessionStorage} kullanılıyor: sayfa yenilense de aynı kalır
 * (kullanıcı F5'e basınca "yeni izleyici" sayılmasın), ama YENİ bir sekme
 * açıldığında (sessionStorage sekmeler arası paylaşılmaz) farklı bir kimlik
 * üretilir — aynı kişi aynı kanalı iki sekmede açarsa ikisi de ayrı ayrı
 * sayılmalı.
 */
export function sekmeKimligi(): string {
  const KEY = 'yayin-sekme-kimligi'
  let id = sessionStorage.getItem(KEY)
  if (!id) {
    id = uuidUret()
    sessionStorage.setItem(KEY, id)
  }
  return id
}
