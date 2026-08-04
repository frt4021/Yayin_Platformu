package org.example.channel.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MediaMTX path yapılandırması. {@code null} alanlar gönderilmez — PATCH
 * isteğinde yalnızca değişen alanın iletilmesi, diğer ayarların MediaMTX
 * tarafındaki değerlerinin korunmasını sağlar.
 *
 * @param runOnAvailable Yayın hazır olunca konteyner içinde çalıştırılacak
 *                       komut. Çözünürlük düşürme (transcode) bunun üzerinden
 *                       yapılıyor: MediaMTX'in kendisi transcode etmiyor.
 * @param sourceOnDemand {@code false} ise MediaMTX kaynağa hemen bağlanır ve
 *                       izleyici olmasa da yayını çeker. Kanalların yeniden
 *                       başlatma sonrası kendiliğinden ayağa kalkması bunu
 *                       gerektiriyor; {@code true} olsaydı path tanımlı olur
 *                       ama ilk izleyici gelene kadar yayın akmazdı.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaMtxPathConfig(
    String source,
    Boolean sourceOnDemand,
    Boolean record,
    String runOnAvailable,
    Boolean runOnAvailableRestart
) {
    /**
     * Sürekli çeken bir kanal yapılandırması.
     *
     * @param record DVR açık mı. 6 Mbps'lik bir kanal 7 günde ~454 GB yazar;
     *               bu yüzden kanal bazında açılıp kapanabiliyor.
     */
    public static MediaMtxPathConfig alwaysOn(String source, boolean record, String transcode) {
        // runOnAvailableRestart yalnızca komut varken anlamlı; null bırakmak
        // PATCH'te alanın MediaMTX tarafındaki değerini korur.
        return new MediaMtxPathConfig(source, false, record,
            transcode, transcode == null ? null : Boolean.TRUE);
    }

    /**
     * Yayıncı tarafından beslenen path — kaynağı yok, transcode çıktıları böyle.
     *
     * @param record bu rendition DVR'a kaydediliyor mu. Kayıt tek bir path'e
     *               yazılır; hepsini kaydetmek diski rendition sayısıyla çarpardı.
     */
    public static MediaMtxPathConfig publisherFed(boolean record) {
        return new MediaMtxPathConfig(null, null, record, null, null);
    }
}
