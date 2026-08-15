package org.example.etkinlik.dto;

import java.util.Map;

/**
 * @param saatBazliGiris        saat (0-23) -> o saatte başlayan GIRIS sayısı
 * @param ortalamaIzlemeBaslangici24s "zapping" vekili — son 24 saatte aktif
 *                                    kullanıcı başına ortalama IZLEME_BASLADI sayısı
 */
public record GenelAktiviteDto(
    long dau, long mau,
    Map<Integer, Long> saatBazliGiris,
    double ortalamaIzlemeBaslangici24s
) {
}
