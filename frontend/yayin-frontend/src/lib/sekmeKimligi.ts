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
    id = crypto.randomUUID()
    sessionStorage.setItem(KEY, id)
  }
  return id
}
