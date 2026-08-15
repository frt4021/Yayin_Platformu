package org.example.etkinlik.dto;

import org.example.etkinlik.EtkinlikTuru;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * @param hedefAdi {@code hedefId}'nin çözümlenmiş insan-okunur adı (kanal/radyo/video
 *                 adı, kullanıcı adı vb.) — hedef silinmişse "Silinmiş …", hedef yoksa
 *                 (örn. {@code CIKIS}) {@code null}.
 */
public record EtkinlikDto(
    UUID id,
    UUID kullaniciId,
    String kullaniciAdi,
    EtkinlikTuru tur,
    String hedefTuru,
    UUID hedefId,
    String hedefAdi,
    Map<String, Object> detay,
    Instant olusturmaZamani
) {
}
