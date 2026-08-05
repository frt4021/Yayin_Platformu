package org.example.video.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Yükleme izni: istemcinin dosyayı doğrudan nesne depolamasına yazması için
 * gereken her şey.
 *
 * <p>Adres <b>tek bir nesneye</b> ve <b>tek bir yönteme</b> (PUT) kapsanmış
 * durumda ve kısa ömürlü. Sızsa bile yapılabilecek tek şey, zaten o kullanıcı
 * adına açılmış olan kayda ait nesneyi yazmak.
 *
 * @param videoId     yükleme bitince {@code POST /api/videos/{id}/tamamlandi}
 *                    çağrılırken kullanılır
 * @param uploadUrl   imzalı PUT adresi
 * @param contentType İstemcinin PUT isteğinde göndermesi <b>beklenen</b>
 *                    içerik tipi. İmzaya dahil <b>değil</b>: dahil edilseydi
 *                    en ufak başlık farkı MinIO'dan 403 getirirdi ve hata
 *                    istemcide "erişim reddedildi" gibi görünüp teşhisi
 *                    zorlaşırdı. Gönderilmezse nesne yanlış içerik tipiyle
 *                    kaydolur ve tarayıcı oynatmayabilir; işçi bunu düzeltir.
 * @param expiresAt   adresin son geçerlilik anı; arayüz süre dolmadan
 *                    yükleyemezse kullanıcıya anlamlı bir mesaj verebilsin
 */
public record UploadTicket(
    UUID videoId,
    String uploadUrl,
    String contentType,
    Instant expiresAt
) {
}
