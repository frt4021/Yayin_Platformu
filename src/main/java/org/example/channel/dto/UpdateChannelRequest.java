package org.example.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Kanal güncelleme. PUT semantiği: tüm alanlar gönderilir, gönderilen hal
 * kanalın yeni hali olur.
 *
 * <p><b>renditions ve dvrRendition {@code @NotNull}</b> — eksik gönderilirse
 * 400 döner. Bu alanlar sonradan eklendi ve eski bir istemci onları
 * göndermediğinde PUT semantiği gereği sessizce siliniyorlardı: kanalın
 * çözünürlük merdiveni ve kayıt ayarı, kimse fark etmeden kayboluyordu.
 * Açık bir hata, sessiz veri kaybından iyidir. Temizlemek isteyen boş
 * string gönderir.
 * @param dvrEnabled Geriye sarma kaydı açık mı.
 */
public record UpdateChannelRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 512) String sourceUrl,
    @NotBlank @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9_-]+",
        message = "yalnızca harf, rakam, alt çizgi ve tire içerebilir")
    String mediamtxPath,
    boolean active,
    boolean dvrEnabled,
    @NotNull(message = "gönderilmeli; merdiveni temizlemek için boş string yollayın")
    @Size(max = 512) String renditions,
    @NotNull(message = "gönderilmeli; kaynaktan kaydetmek için boş string yollayın")
    @Size(max = 32) String dvrRendition
) {
}
