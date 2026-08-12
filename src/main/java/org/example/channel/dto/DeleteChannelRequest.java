package org.example.channel.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Kanal silme isteği.
 *
 * <h2>Neden gövdeli</h2>
 * Şifre <b>sorgu parametresinde taşınamaz</b>: erişim günlüklerine, tarayıcı
 * geçmişine ve vekil sunucu kayıtlarına düz metin olarak düşer. Bu yüzden uç
 * {@code DELETE} değil {@code POST /api/channels/{id}/silme}.
 *
 * @param password    işlemi yapanın <b>kendi</b> şifresi. Paylaşılan bir
 *                    yönetici parolası yerine kişisel şifre: kimin sildiği
 *                    Keycloak tarafında izlenebilir kalıyor
 * @param deleteClips {@code true} ise klipler ve dosyaları siliniyor;
 *                    {@code false} ise kalıyor ve kanal bağı kopuyor
 * @param deleteScreenshots aynısı, ekran görüntüleri için
 *
 * <p><b>İkisi ayrı seçenek.</b> Tek bir "içeriği sil" bayrağıydı ve
 * kullanıcıyı olmayan bir tercihe zorluyordu: klip emek harcanmış bir
 * çıktı, ekran görüntüsü tek tıkla yeniden alınabilir. Birini tutup
 * diğerini atmak meşru bir istek.
 *
 * <p><b>DVR her koşulda siliniyor</b> ve seçeneği yok — bir kayıt
 * segmentinin kanalı olmadan hiçbir anlamı yok, geriye sarılacak yer de.
 */
public record DeleteChannelRequest(
    @NotBlank(message = "Şifre gerekli.") String password,
    boolean deleteClips,
    boolean deleteScreenshots
) {
}
