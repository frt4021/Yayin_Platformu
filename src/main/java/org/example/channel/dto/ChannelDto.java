package org.example.channel.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Kanalın dışarıya açılan gösterimi.
 *
 * @param hlsUrl    tarayıcının doğrudan oynatabileceği manifest adresi.
 *                  Varsayılan olarak <b>göreli</b> ({@code /hls/kanal1/index.m3u8}):
 *                  ana bilgisayar adını tarayıcı kendi bulunduğu adresten
 *                  alıyor. Mutlak yazılsaydı arayüze hangi adresten
 *                  girildiği ile yayının geldiği adres ayrışabilirdi.
 *                  Sunucuda üretiliyor ki frontend MediaMTX'in adres şemasını
 *                  bilmek zorunda kalmasın.
 * @param streaming MediaMTX'ten okunan anlık durum — yayın gerçekten akıyor mu.
 *                  {@code active} kullanıcının niyeti, bu ise gerçeklik;
 *                  kaynak erişilemiyorsa {@code active=true} iken
 *                  {@code streaming=false} olur. MediaMTX'e ulaşılamadıysa
 *                  {@code null}.
 * @param viewers   o an izleyen SEKME sayısı ({@code ViewerPresence}, tarayıcı
 *                  heartbeat'i) — MediaMTX'in reader sayısı DEĞİL, o yeniden
 *                  bağlanmalarda çok sayıyordu. MediaMTX'ten bağımsız olduğu
 *                  için artık her zaman gerçek bir sayı, {@code null} olmaz.
 */
public record ChannelDto(
    UUID id,
    String name,
    String sourceUrl,
    String mediamtxPath,
    boolean active,
    boolean dvrEnabled,
    String renditions,
    /**
     * MediaMTX'e gerçekte yazılan adres; master playlist'ten bir varyant
     * seçildiyse dolu, aksi halde {@code null}. Arayüz "girdiğin adres bu
     * değil" durumunu görünür kılabilsin diye açılıyor.
     */
    String resolvedSourceUrl,
    /** Kaynağın tespit edilen çözünürlüğü; bilinmiyorsa {@code null}. */
    Integer sourceWidth,
    Integer sourceHeight,
    String hlsUrl,
    Boolean streaming,
    Integer viewers,
    String createdBy,
    Instant createdAt
) {
}
