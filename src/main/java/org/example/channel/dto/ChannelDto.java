package org.example.channel.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Kanalın dışarıya açılan gösterimi.
 *
 * @param hlsUrl    tarayıcının doğrudan oynatabileceği manifest adresi.
 *                  Sunucuda üretiliyor ki frontend MediaMTX'in adres şemasını
 *                  bilmek zorunda kalmasın.
 * @param streaming MediaMTX'ten okunan anlık durum — yayın gerçekten akıyor mu.
 *                  {@code active} kullanıcının niyeti, bu ise gerçeklik;
 *                  kaynak erişilemiyorsa {@code active=true} iken
 *                  {@code streaming=false} olur. MediaMTX'e ulaşılamadıysa
 *                  {@code null}.
 * @param viewers   o an izleyen oturum sayısı; {@code streaming} gibi
 *                  MediaMTX'e ulaşılamadığında {@code null}.
 */
public record ChannelDto(
    UUID id,
    String name,
    String sourceUrl,
    String mediamtxPath,
    boolean active,
    boolean dvrEnabled,
    String renditions,
    String dvrRendition,
    String hlsUrl,
    Boolean streaming,
    Integer viewers,
    String createdBy,
    Instant createdAt
) {
}
