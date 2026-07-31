package org.example.dvr.dto;

import java.time.Instant;

/**
 * Zaman çizelgesinde kayıt bulunan bir aralık.
 *
 * <p>MediaMTX'in süre tabanlı gösterimi yerine başlangıç/bitiş kullanılıyor:
 * arayüz aralıkları doğrudan çizeceği için bitiş anını kendisi hesaplamak
 * zorunda kalmasın.
 */
public record TimelineSpan(
    Instant start,
    Instant end
) {
}
