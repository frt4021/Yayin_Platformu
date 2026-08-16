package org.example.sistemlog.dto;

import java.time.Instant;

/**
 * Bir konteyner log satırının yorumlanmış hali — admin panelin
 * "Sistem Logları" ekranında gösteriliyor.
 *
 * @param seviye  {@code BASARI}/{@code BILGI}/{@code UYARI}/{@code HATA}
 *                ({@code org.example.sistemlog.SistemLogYorumlayici.Seviye}
 *                enum'ının adı; String olarak taşınıyor ki DTO,
 *                yorumlayıcının iç enum'ına bağımlı olmasın)
 * @param mesaj   Türkçe, kullanıcı dostu özet
 * @param hamMesaj orijinal log satırı — meraklısı için katlanabilir detayda
 */
public record SistemLogDto(
    Instant zaman,
    String servis,
    String seviye,
    String mesaj,
    String hamMesaj
) {
}
