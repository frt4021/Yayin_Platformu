package org.example.etkinlik.dto;

import java.util.List;

/**
 * @param toplamDvrBoyutBayt Bilinen kaviat: {@code DvrStorage}'ın kendi
 *                            javadoc'u DVR segment satırlarının MinIO ILM
 *                            silmelerinden sonra senkron kalmadığını
 *                            söylüyor — bu sayı zamanla şişebilir.
 */
public record DepolamaDto(
    List<KullaniciKullanimDto> enYuksekKullanicilar,
    long gelecek24SaatPlanliKayit,
    long toplamDvrBoyutBayt
) {
}
