package org.example.screenshot.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Galerideki bir kare.
 *
 * <p>İmzalı adresler <b>listede</b> geliyor: galeri bir ızgara ve her kart
 * için ayrı istek atmak N+1 çağrı olurdu. Küçük resimlerdeki gerekçenin
 * aynısı; imza hesabı yerel bir HMAC.
 *
 * @param capturedAt karenin ait olduğu <b>yayın</b> anı — {@code createdAt}
 *                   ise kaydın oluşturulduğu an. Geriye sarmadan
 *                   yakalananlarda ikisi saatlerce farklı olabilir.
 */
public record ScreenshotDto(
    UUID id,
    UUID channelId,
    String channelName,
    Instant capturedAt,
    Integer width,
    Integer height,
    long sizeBytes,
    String note,
    String viewUrl,
    String downloadUrl,
    String fileName,
    String capturedBy,
    Instant createdAt
) {
}
