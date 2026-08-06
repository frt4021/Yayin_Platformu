package org.example.clip.dto;

import org.example.clip.ScheduledStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Planlı bir kayıt emri.
 *
 * @param clipId    üretilen klip; henüz üretilmediyse {@code null}
 * @param hata      başarısız olduysa sebebi
 * @param dvrBizden kanalın geriye sarması bu emir için mi açıldı — arayüz
 *                  kullanıcıya bunu söylüyor, kanal ayarı sessizce değişmiş
 *                  gibi görünmesin
 */
public record ScheduledRecordingDto(
    UUID id,
    UUID channelId,
    String channelName,
    Instant baslangic,
    Instant bitis,
    long durationSeconds,
    ScheduledStatus durum,
    UUID clipId,
    String hata,
    boolean dvrBizden,
    String requestedBy,
    Instant createdAt
) {
}
