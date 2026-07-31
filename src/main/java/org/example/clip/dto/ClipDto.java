package org.example.clip.dto;

import org.example.clip.ClipStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Klibin dışarıya açılan gösterimi.
 *
 * @param sizeBytes yalnızca {@code HAZIR} durumunda dolu
 * @param error     yalnızca {@code HATA} durumunda dolu; kullanıcıya gösterilir
 */
public record ClipDto(
    UUID id,
    UUID channelId,
    String channelName,
    Instant start,
    Instant end,
    long durationSeconds,
    ClipStatus status,
    Long sizeBytes,
    String error,
    String requestedBy,
    Instant createdAt,
    Instant completedAt
) {
}
