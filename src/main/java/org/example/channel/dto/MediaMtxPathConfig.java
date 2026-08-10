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
 * @param runOnInit      Path kurulur kurulmaz çalıştırılan komut.
 *                       {@code runOnAvailable}'dan farkı: o, <b>yayın gelince</b>
 *                       tetiklenir. Radyo köprüsünde beklenecek bir yayın yok —
 *                       kaynağı komutun kendisi getiriyor, dolayısıyla path'in
 *                       kurulması tetikleyici olmalı.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record
MediaMtxPathConfig(
    String source,
    Boolean sourceOnDemand,
    Boolean record,
    String runOnAvailable,
    Boolean runOnAvailableRestart,
    String runOnInit,
    Boolean runOnInitRestart
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
            transcode, transcode == null ? null : Boolean.TRUE, null, null);
    }

    /**
     * Yayıncı tarafından beslenen path — kaynağı yok, transcode çıktıları böyle.
     *
     * @param record bu rendition DVR'a kaydediliyor mu. Kayıt tek bir path'e
     *               yazılır; hepsini kaydetmek diski rendition sayısıyla çarpardı.
     */
    public static MediaMtxPathConfig publisherFed(boolean record) {
        return new MediaMtxPathConfig(null, null, record, null, null, null, null);
    }

    /**
     * Kaynağı bir ffmpeg köprüsünün beslediği path — Icecast radyoları böyle.
     *
     * <p>{@code sourceOnDemand} <b>gönderilmiyor</b>: MediaMTX
     * {@code source: publisher} ile birlikte gelen bu alanı hata sayıyor
     * ("'sourceOnDemand' is useless when source is 'publisher'") ve path'in
     * tamamını reddediyor.
     *
     * <p>{@code runOnInitRestart}: Icecast bağlantıları düşer, ffmpeg de
     * onunla birlikte çıkar. Yeniden başlatılmazsa radyo bir daha kendiliğinden
     * yayına girmez.
     */
    public static MediaMtxPathConfig bridged(String command) {
        return new MediaMtxPathConfig("publisher", null, null, null, null,
            command, Boolean.TRUE);
    }
}
