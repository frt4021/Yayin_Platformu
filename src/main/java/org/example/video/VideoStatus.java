package org.example.video;

/**
 * Bir video kaydının yaşam döngüsü.
 *
 * <p>İsimler veritabanına string olarak yazılıyor (bkz. V9 migration ve
 * {@code videos_durum_gecerli} kısıtı); yeniden adlandırmak migration
 * gerektirir.
 *
 * <p>Kliplerden farklı olarak burada <b>iki</b> bekleme durumu var:
 * dosya önce tarayıcıdan MinIO'ya yükleniyor ({@code YUKLENIYOR}), sonra
 * işçi tarafından işleniyor ({@code ISLENIYOR}). Tek durum olsaydı
 * "tarayıcı hâlâ yüklüyor mu, yoksa yükleme yarım mı kaldı" ayrımı
 * yapılamaz ve süpürücü çalışan bir yüklemeyi iptal edebilirdi.
 */
public enum VideoStatus {

    /**
     * Kayıt açıldı, imzalı adres verildi, dosya henüz tamamlanmadı.
     *
     * <p>Bu durumda kalan eski kayıtları süpürücü ele alır: nesne MinIO'da
     * varsa yükleme aslında bitmiştir (bildirim kaybolmuş), yoksa iptal edilir.
     */
    YUKLENIYOR,

    /** Dosya depolamada doğrulandı; işçi metadata ve küçük resim üretiyor. */
    ISLENIYOR,

    /** İzlenebilir: küçük resim ve metadata hazır. */
    HAZIR,

    /** Kalıcı olarak başarısız; sebep {@code error} alanında. */
    HATA
}
