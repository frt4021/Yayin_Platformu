package org.example.etkinlik.dto;

/**
 * @param yayinKopmaOrani Yüzde (ondalıklı): (Σ OYNATMA_HATASI.sayi + Σ
 *                        OYNATMA_TAKILMA.sayi) ÷ (COUNT(IZLEME_BASLADI) +
 *                        COUNT(DINLEME_BASLADI)) × 100. Hiç izleme/dinleme
 *                        başlangıcı yoksa {@code null} (oran anlamsız).
 */
public record TeknikDto(long basarisizPlanliKayit, long videoIslemeHatasi, Double yayinKopmaOrani) {
}
