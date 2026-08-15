package org.example.etkinlik;

/**
 * Kayıt altına alınan kullanıcı davranışı olayları. {@code etkinlik_kayitlari.tur}
 * kolonu düz metin — yeni bir değer eklemek migration gerektirmez.
 */
public enum EtkinlikTuru {
    GIRIS,
    GIRIS_BASARISIZ,
    CIKIS,

    IZLEME_BASLADI,
    IZLEME_BITTI,
    DINLEME_BASLADI,
    DINLEME_BITTI,

    ALTYAZI_DIL_DEGISTI,
    KALITE_DEGISTI,
    DVR_GERI_SARILDI,

    KLIP_OLUSTURULDU,
    KAYIT_BASLADI,
    KAYIT_DURDU,

    KANAL_EKLENDI,
    KANAL_SILINDI,
    RADYO_EKLENDI,
    RADYO_SILINDI,
    KULLANICI_EKLENDI,
    KULLANICI_SILINDI,
    KULLANICI_ROLU_DEGISTI,
    VIDEO_YUKLENDI,
    VIDEO_SILINDI,

    VIDEO_IZLEME_BASLADI,
    VIDEO_IZLEME_BITTI,
    OYNATMA_HATASI,
    OYNATMA_TAKILMA,
}
