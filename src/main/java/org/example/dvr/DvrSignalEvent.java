package org.example.dvr;

import java.util.UUID;

/**
 * Kaydediciye gönderilen anlık emir.
 *
 * <h2>Neden gerekli</h2>
 * Kaydı <b>başlatan</b> süreç backend, <b>kaydeden</b> süreç video işçisi —
 * ayrı konteynerler. Aralarındaki tek bağ şimdiye kadar veritabanıydı ve
 * kaydedici oraya {@code dvr.sync-interval} (10 sn) aralıklarla bakıyordu.
 * Kısa kayıtlarda bu tamamen ıskalıyordu: 6 saniyelik bir kayıt, kaydedici
 * daha başlamadan bitiyordu.
 *
 * <p>Dinleyici {@code TransactionPhase.AFTER_SUCCESS} ile bağlı
 * ({@link DvrSignal}). {@link Tur#BASLAT} için bu <b>şart</b>: kaydediciye
 * haber, sinyalin sebebi olan {@code ActiveRecording} satırı commit edilmeden
 * giderse, kaydedici veritabanına bakıp hiçbir şey bulamaz ve sinyal boşa
 * gider.
 */
public record DvrSignalEvent(Tur tur, UUID channelId) {

    public enum Tur {
        /**
         * Bu kanalı <b>şimdi</b> kaydetmeye başla.
         *
         * <p>Kaydedici bir sonraki yoklamasını beklemeden eşitleme yapıyor.
         */
        BASLAT,

        /**
         * Süren segmenti <b>şimdi</b> kapat.
         *
         * <p>Segment kapanmadan zaman çizelgesine satır yazılmıyor; 30 saniyelik
         * segmentte bu, kaydı durduran kullanıcının yarım dakika boyunca
         * "bu aralıkta kayıt yok" görmesi demekti. Emir alınınca segment ilk
         * paket sınırında kapanıyor ve saniyeler içinde çizelgeye düşüyor.
         */
        KES
    }

    public static DvrSignalEvent baslat(UUID channelId) {
        return new DvrSignalEvent(Tur.BASLAT, channelId);
    }

    public static DvrSignalEvent kes(UUID channelId) {
        return new DvrSignalEvent(Tur.KES, channelId);
    }
}
