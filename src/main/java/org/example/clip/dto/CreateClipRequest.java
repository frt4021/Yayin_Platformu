package org.example.clip.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Klip isteği. Zamanlar UTC.
 *
 * <p>Süre üst sınırı sunucuda uygulanır ({@code clips.max-duration},
 * varsayılan 2 saat): sınırsız bırakılsa "7 günü tek klip yap" isteği
 * 6 Mbps'te ~450 GB'lık bir dosya üretmeye çalışırdı.
 */
public record CreateClipRequest(
    @NotNull Instant start,
    @NotNull Instant end
) {
}
