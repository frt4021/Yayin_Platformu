package org.example.radio.dto;

import org.example.radio.RadioSourceKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Radyonun dışarıya açılan gösterimi.
 *
 * @param hlsUrl    tarayıcının doğrudan oynatabileceği manifest adresi.
 *                  Ses-only bir HLS akışı; {@code <audio>} değil hls.js ile
 *                  oynatılır çünkü tarayıcıların çoğu HLS'i yerel desteklemez.
 * @param streaming MediaMTX'ten okunan anlık durum. {@code active} kullanıcının
 *                  niyeti, bu ise gerçeklik: kaynak erişilemiyorsa ya da ffmpeg
 *                  köprüsü ayağa kalkamadıysa {@code active=true} iken
 *                  {@code streaming=false} olur. MediaMTX'e ulaşılamadıysa
 *                  {@code null}.
 * @param listeners o an dinleyen SEKME sayısı ({@code ViewerPresence}, tarayıcı
 *                  heartbeat'i) — MediaMTX'in reader sayısı DEĞİL, o yeniden
 *                  bağlanmalarda çok sayıyordu. Artık her zaman gerçek bir
 *                  sayı, {@code null} olmaz.
 */
public record RadioDto(
    UUID id,
    String name,
    String sourceUrl,
    RadioSourceKind sourceKind,
    String mediamtxPath,
    String bitrate,
    boolean active,
    String logoUrl,
    int sortOrder,
    String hlsUrl,
    Boolean streaming,
    Integer listeners,
    String createdBy,
    Instant createdAt
) {
}
