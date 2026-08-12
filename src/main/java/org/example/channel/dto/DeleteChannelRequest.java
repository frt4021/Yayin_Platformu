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
 * @param deleteDvr DVR nesneleri <b>hemen</b> silinsin mi.
 *
 * <p><b>DVR'da "sakla" farklı anlama geliyor.</b> Zaman çizelgesi
 * ({@code dvr_segments}) kanala {@code CASCADE} bağlı ve her koşulda
 * gidiyor — kanalı olmayan bir segmentin gösterileceği yer yok.
 * Seçilebilen tek şey MinIO'daki <b>baytların</b> kaderi:
 *
 * <ul>
 *   <li>{@code true} — nesneler şimdi siliniyor, yer hemen boşalıyor</li>
 *   <li>{@code false} — nesneler duruyor; saklama kuralı ({@code
 *       DVR_RETENTION_DAYS}) süresi dolunca kendisi temizliyor</li>
 * </ul>
 *
 * <p>İkincisinin değeri bir <b>yanlış tıklama ağı</b> olması: kanal
 * silinse bile kayıt birkaç gün MinIO'da kalıyor ve konsoldan
 * {@code dvr/&lt;kanal&gt;/} önekinden kurtarılabiliyor.
 */
public record DeleteChannelRequest(
    @NotBlank(message = "Şifre gerekli.") String password,
    boolean deleteClips,
    boolean deleteScreenshots,
    boolean deleteDvr
) {
}
