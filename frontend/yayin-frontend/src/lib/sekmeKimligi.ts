const ANAHTAR = 'yayin.sekmeKimligi'

/**
 * Bu tarayıcı sekmesi için sabit bir kimlik.
 *
 * sessionStorage kullanılıyor (tokens.ts ile aynı gerekçe): sayfa
 * yenilense de aynı kalır, ama yeni bir sekmede FARKLI olur. İzleyici
 * sayısının sekme bazlı sayılabilmesi (bkz. ViewerPresence.java) bu
 * kimliğin sekme ömrü boyunca sabit olmasına dayanıyor.
 */
export function sekmeKimligi(): string {
  let id = sessionStorage.getItem(ANAHTAR)
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem(ANAHTAR, id)
  }
  return id
}
