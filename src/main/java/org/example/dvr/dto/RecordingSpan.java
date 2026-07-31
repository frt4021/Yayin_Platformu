package org.example.dvr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MediaMTX'in {@code /list} yanıtındaki bir kayıt aralığı.
 *
 * <p>{@code url} alanı bilerek taşınmıyor: MediaMTX oraya kendi iç adresini
 * ({@code http://mediamtx:9996/...}) koyuyor. Dışarıya verilse hem tarayıcı
 * çözemez hem de yetkilendirmesiz bir kapı açılırdı.
 *
 * @param start    aralığın başlangıcı, ISO-8601 UTC
 * @param duration aralığın süresi, saniye
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordingSpan(
    String start,
    double duration
) {
}
