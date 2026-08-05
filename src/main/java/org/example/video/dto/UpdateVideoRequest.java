package org.example.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Video düzenleme.
 *
 * <p>Yalnızca <b>kullanıcının sahip olduğu</b> alanlar burada. Dosyanın
 * kendisi, boyutu, süresi ve çözünürlüğü değiştirilemez: bunlar işçinin
 * dosyayı okuyarak tespit ettiği gerçekler, kullanıcı tercihi değil.
 * Yeni bir dosya istenirse yeni bir kayıt açılır.
 *
 * @param thumbnailAtSeconds Küçük resmin alınacağı an. Değiştirilirse işçi
 *                           kareyi yeniden üretir. {@code null} bırakılırsa
 *                           mevcut küçük resme dokunulmaz — "otomatiğe dön"
 *                           demek değildir, çünkü o durumda kullanıcının
 *                           yüklediği bir görseli sessizce silmek gerekirdi.
 */
public record UpdateVideoRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 5000) String description,
    @PositiveOrZero Integer thumbnailAtSeconds
) {
}
