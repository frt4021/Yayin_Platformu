package org.example.clip;

/**
 * Klibin nasıl üretildiği.
 *
 * <p>İkisi de aynı tabloda duruyor çünkü ürün ve yaşam döngüsü aynı: her
 * ikisi de MediaMTX'ten çekilen bir zaman aralığı, MinIO'da bir MP4. Fark
 * yalnızca kullanıcının onu nasıl istediği — arayüz "kliplerim" ve
 * "kayıtlarım" listelerini bu alana göre ayırıyor.
 *
 * <p>İsimler veritabanına string olarak yazılıyor (bkz. V13 ve
 * {@code clips_origin_gecerli} kısıtı); yeniden adlandırmak migration ister.
 */
public enum ClipOrigin {

    /** Zaman çizelgesinden aralık seçilerek. */
    ARALIK,

    /** "Kayda başla" / "durdur" ile. */
    MANUEL_KAYIT
}
