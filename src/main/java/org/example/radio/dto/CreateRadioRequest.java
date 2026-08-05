package org.example.radio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.radio.RadioSourceKind;

/**
 * Yeni radyo.
 *
 * @param sourceKind  Kaynağın MediaMTX'e nasıl bağlanacağı. <b>Zorunlu ve
 *                    tahmin edilmiyor:</b> MediaMTX {@code http(s)}
 *                    kaynaklarını HLS sayıyor, düz bir Icecast MP3 adresini
 *                    kabul edip sessizce hiç yayına almıyor. Yanlış tahminin
 *                    cezası görünür bir hata değil, çalışmayan bir radyo.
 * @param mediamtxPath MediaMTX'teki path adı; HLS adresi bundan türer. Bir URL
 *                     yolu parçası olduğu için harf, rakam, alt çizgi ve tire
 *                     ile sınırlı.
 * @param bitrate     Yalnızca {@code KOPRU} modunda kullanılır. Boş bırakılırsa
 *                     varsayılan uygulanır. Kaynağınkinin üzerine çıkmak
 *                     kaliteyi artırmaz, yalnızca bant genişliği harcar.
 */
public record CreateRadioRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 512) String sourceUrl,
    @NotNull RadioSourceKind sourceKind,
    @NotBlank @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9_-]+",
        message = "yalnızca harf, rakam, alt çizgi ve tire içerebilir")
    String mediamtxPath,
    @Size(max = 16) String bitrate,
    boolean active,
    @Size(max = 512) String logoUrl,
    int sortOrder
) {
}
