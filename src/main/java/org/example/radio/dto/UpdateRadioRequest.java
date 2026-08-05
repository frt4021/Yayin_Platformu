package org.example.radio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.radio.RadioSourceKind;

/**
 * Radyo güncelleme. Tüm alanlar gönderilir; kısmi güncelleme yok.
 *
 * <p>{@code sourceKind} {@code @NotNull} — eksik gönderilirse enum
 * {@code null} gelir ve radyo, kullanıcının hiç istemediği bir modda
 * yeniden kurulmaya çalışılırdı.
 */
public record UpdateRadioRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 512) String sourceUrl,
    @NotNull RadioSourceKind sourceKind,
    @NotBlank @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9_-]+",
        message = "yalnızca harf, rakam, alt çizgi ve tire içerebilir")
    String mediamtxPath,
    @Size(max = 16) String bitrate,
    boolean active,
    @Size(max = 512) String logoUrl,
    int sortOrder
) {
}
