package org.example.clip.dto;

import org.example.clip.ClipOrigin;
import org.example.clip.ClipStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Klibin dışarıya açılan gösterimi.
 *
 * @param sizeBytes      yalnızca {@code HAZIR} durumunda dolu
 * @param error          yalnızca {@code HATA} durumunda dolu; kullanıcıya gösterilir
 * @param subtitleLangs  WebVTT altyazısı üretilen diller; boşsa altyazı yok
 *                       (kaynakta veri yoktu ya da üretim başarısız oldu —
 *                       klip yine de izlenebilir)
 * @param previewUrl     ızgarada fare kartın üzerine gelince oynayan kısa
 *                       önizleme klibinin imzalı adresi; üretilmediyse
 *                       {@code null} — kart o zaman ikon yer tutucuya düşer
 * @param thumbnailUrl   önizleme klibinden çıkarılan tek karelik kapak
 *                       görselinin imzalı adresi; üretilmediyse {@code null}
 */
public record ClipDto(
    UUID id,
    UUID channelId,
    String channelName,
    Instant start,
    Instant end,
    long durationSeconds,
    ClipStatus status,
    /** Klibin nasıl istendiği: aralık seçimi mi, manuel kayıt mı. */
    ClipOrigin origin,
    Long sizeBytes,
    String error,
    String requestedBy,
    Instant createdAt,
    Instant completedAt,
    List<String> subtitleLangs,
    String previewUrl,
    String thumbnailUrl
) {
}
