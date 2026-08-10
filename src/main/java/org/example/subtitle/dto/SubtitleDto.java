package org.example.subtitle.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Oynatıcıya giden altyazı parçası.
 *
 * <p>Zaman damgaları <b>mutlak</b>: oynatıcı kendi {@code playingDate()}
 * değeriyle eşleştiriyor. Göreli süre gönderilseydi izleyicinin yayında
 * nerede olduğu bilinmediği için eşleşme mümkün olmazdı.
 *
 * @param metinler dil kodundan metne — {@code en} her zaman var (pivot)
 */
public record SubtitleDto(
    UUID id,
    Instant baslangic,
    Instant bitis,
    String kaynakDil,
    Float guven,
    Map<String, String> metinler,
    boolean kesik
) {
}
