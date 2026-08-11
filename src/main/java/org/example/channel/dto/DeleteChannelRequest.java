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
 * @param deleteContent {@code true} ise klipler, ekran görüntüleri ve
 *                    dosyaları da siliniyor. {@code false} ise içerik kalıyor
 *                    ve kanal bağı kopuyor.
 *                    <p><b>DVR her koşulda siliniyor</b> — bir kayıt
 *                    segmentinin kanalı olmadan hiçbir anlamı yok.
 */
public record DeleteChannelRequest(
    @NotBlank(message = "Şifre gerekli.") String password,
    boolean deleteContent
) {
}
