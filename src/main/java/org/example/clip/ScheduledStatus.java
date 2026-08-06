package org.example.clip;

/** Planlı bir kayıt emrinin yaşam döngüsü. */
public enum ScheduledStatus {

    /** Aralık henüz başlamadı. İptal edilebilir. */
    BEKLIYOR,

    /**
     * Aralık başladı, MediaMTX yazıyor. İptal edilebilir ama o ana kadar
     * yazılanlar için klip üretilmez — kullanıcı iptal ettiyse istemiyordur.
     */
    KAYITTA,

    /** Aralık geçti ve klip işi açıldı. */
    TAMAMLANDI,

    /**
     * Klip açılamadı. Sebep {@code hata} sütununda; en sık görüleni aralığın
     * tamamının kayıtlı olmaması (kanal o sırada yayında değildi).
     */
    BASARISIZ,

    /** Kullanıcı vazgeçti. */
    IPTAL
}
