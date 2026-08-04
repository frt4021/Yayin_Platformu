package org.example.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Yeni kanal.
 *
 * @param mediamtxPath MediaMTX'teki path adı; HLS adresi bundan türer
 *                     ({@code http://host:8888/<path>/index.m3u8}). Bir URL
 *                     yolu parçası olarak kullanıldığı için harf, rakam,
 *                     alt çizgi ve tire ile sınırlandırıldı — boşluk veya
 *                     eğik çizgi adresi bozar.
 * @param active       {@code true} ise kanal oluşturulur oluşturulmaz
 *                     MediaMTX'e yazılır ve yayın çekilmeye başlanır.
 * @param dvrEnabled Geriye sarma kaydı açık mı. Yayında olmaktan bağımsız:
 *                   6 Mbps'lik bir kanal 7 günde ~454 GB yazar.
 */
public record CreateChannelRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 512) String sourceUrl,
    @NotBlank @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9_-]+",
        message = "yalnızca harf, rakam, alt çizgi ve tire içerebilir")
    String mediamtxPath,
    boolean active,
    boolean dvrEnabled,
    @Size(max = 512) String renditions,
    @Size(max = 32) String dvrRendition
) {
}
