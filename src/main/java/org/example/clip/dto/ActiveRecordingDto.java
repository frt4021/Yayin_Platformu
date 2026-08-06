package org.example.clip.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Devam eden bir manuel kayıt.
 *
 * @param startedAt         kaydın başladığı an. Arayüz geçen süreyi buradan
 *                          hesaplıyor — sunucudan saniye saniye süre çekmek
 *                          gereksiz trafik olurdu.
 * @param maxMinutes        üst sınır. Arayüz "şu kadar kaldı" gösterebilsin ve
 *                          sınıra gelindiğinde kaydın kendiliğinden
 *                          duracağını söyleyebilsin diye gönderiliyor.
 */
public record ActiveRecordingDto(
    UUID channelId,
    String channelName,
    Instant startedAt,
    int maxMinutes
) {
}
