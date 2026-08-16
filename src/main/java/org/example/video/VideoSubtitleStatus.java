package org.example.video;

/**
 * Bir videonun altyazı (STT) üretim durumu — video işleme kuyruğundan
 * (bkz. {@link VideoStatus}) tamamen ayrı, çünkü STT Triton/GPU'ya bağlı ve
 * dakikalar sürebilir; aynı havuza konsaydı thumbnail/önizleme işini
 * yavaşlatırdı.
 */
public enum VideoSubtitleStatus {

    /** Özellik kapalı (bkz. {@code videos.subtitle-enabled}) ya da videoda ses akışı yok. */
    KAPALI,

    /** Kuyruğa alındı, işçi henüz başlamadı. */
    BEKLIYOR,

    /** İşçi ses çıkarıp VAD+Triton'dan geçiriyor. */
    ISLENIYOR,

    /** İşlendi. {@code subtitleLangs} boş olabilir — konuşma tespit edilmemiş olabilir, bu bir hata değil. */
    HAZIR,

    /** Kalıcı olarak başarısız; sebep {@code subtitleError} alanında. Video'nun kendisi ETKİLENMEZ. */
    HATA
}
