package org.example.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Kanal güncelleme. PUT semantiği: tüm alanlar gönderilir, gönderilen hal
 * kanalın yeni hali olur.
 * @param dvrEnabled Geriye sarma kaydı açık mı.
 */
public record UpdateChannelRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 512) String sourceUrl,
    @NotBlank @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9_-]+",
        message = "yalnızca harf, rakam, alt çizgi ve tire içerebilir")
    String mediamtxPath,
    boolean active,
    boolean dvrEnabled
) {
}
