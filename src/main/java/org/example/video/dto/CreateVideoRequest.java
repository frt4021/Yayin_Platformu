package org.example.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Yükleme başlatma isteği. Yanıtı {@link UploadTicket}.
 *
 * <p>Dosyanın kendisi bu istekte <b>gelmiyor</b>: istemci yalnızca ne
 * yükleyeceğini bildiriyor, backend imzalı bir adres üretiyor, dosya
 * doğrudan nesne depolamasına gidiyor.
 *
 * @param fileName    yalnızca gösterim ve indirme adı için. <b>Nesne anahtarı
 *                    bundan türetilmez</b> — istemcinin verdiği bir isim
 *                    yol ayracı veya başka bir kaydın anahtarını taşıyabilir.
 * @param contentType imzaya dahil edilecekse istemcinin göndereceğiyle
 *                    birebir aynı olmalı; farklı olursa MinIO 403 döner.
 * @param sizeBytes   istemcinin BEYANI. Üst sınır kontrolü için erken bir
 *                    süzgeç; gerçek boyut yükleme bitince depolamadan
 *                    okunup doğrulanır. Buna güvenilerek kayıt HAZIR
 *                    yapılmaz.
 */
public record CreateVideoRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 5000) String description,
    @NotBlank @Size(max = 255) String fileName,
    @Size(max = 100) String contentType,
    @Positive Long sizeBytes
) {
}
