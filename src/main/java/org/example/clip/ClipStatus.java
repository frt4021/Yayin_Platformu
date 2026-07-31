package org.example.clip;

/**
 * Klip işinin durumu.
 *
 * <p>İsimler veritabanına string olarak yazılıyor (bkz. V5 migration);
 * yeniden adlandırmak migration gerektirir.
 */
public enum ClipStatus {

    /** Kuyrukta, henüz alınmadı. */
    BEKLIYOR,

    /** Bir işçi tarafından alındı, MediaMTX'ten çekiliyor. */
    ISLENIYOR,

    /** Nesne depolamasına yazıldı, indirilebilir. */
    HAZIR,

    /** Kalıcı olarak başarısız; sebep {@code error} alanında. */
    HATA
}
