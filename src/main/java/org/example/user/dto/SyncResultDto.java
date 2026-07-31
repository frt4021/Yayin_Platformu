package org.example.user.dto;

import java.util.List;

/**
 * Keycloak → yerel {@code users} tablosu eşitlemesinin sonucu.
 *
 * @param created  yerelde olmayıp Keycloak'ta bulunan ve şimdi eklenen kullanıcı adları
 * @param updated  kullanıcı adı veya rolü Keycloak'takinden farklı olduğu için güncellenenler
 * @param orphaned Keycloak'ta artık bulunmayan ama yerelde duran kayıtlar. Bunlar
 *                 <b>otomatik silinmez</b>: uygulama verisi (kanal, kayıt) bu satırlara
 *                 foreign key ile bağlı olabilir, sessizce silmek veri kaybı olurdu.
 *                 Yönetici incelenip DELETE ile temizlemelidir.
 */
public record SyncResultDto(
    List<String> created,
    List<String> updated,
    List<String> orphaned
) {
}
