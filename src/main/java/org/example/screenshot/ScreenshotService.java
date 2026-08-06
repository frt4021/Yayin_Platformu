package org.example.screenshot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.entity.Channel;
import org.example.exception.AppException;
import org.example.screenshot.dto.ScreenshotDto;
import org.example.screenshot.entity.Screenshot;
import org.example.storage.QuotaService;
import org.example.user.Roles;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Canlı yayından yakalanan kareler ve kronolojik galeri.
 *
 * <p><b>Kare TARAYICIDA yakalanıyor</b>, sunucuda değil. Gerekçe:
 *
 * <ul>
 *   <li><b>Anındalık.</b> ffmpeg yalnızca işçi konteynerinde var; sunucu
 *       tarafı yakalama kuyruğa girmek demekti ve "ekran görüntüsü al"
 *       düğmesinin birkaç saniye sonra sonuç vermesi bozuk hissettirirdi.</li>
 *   <li><b>Doğru kare.</b> HLS'te izlenen an ile canlı uç arasında 6-20 saniye
 *       fark var. Sunucu canlı uçtan yakalasaydı kullanıcının <i>gördüğü</i>
 *       kare olmazdı.</li>
 * </ul>
 *
 * <p>Bedeli: kare, kullanıcının izlediği rendition kalitesinde oluyor —
 * 480p izleyen 480p kare alıyor. Kaynak çözünürlüğü gerekirse ileride
 * {@code capturedAt} kullanılarak DVR'dan sunucu tarafı yakalama eklenebilir;
 * o an zaten kaydediliyor.
 *
 * <p>Görsel backend üzerinden geçiyor (video dosyasının aksine): birkaç yüz
 * kilobayt için imzalı adres dansı kurmak gereksiz karmaşıklık olurdu.
 */
@ApplicationScoped
public class ScreenshotService {

    private static final Logger LOG = Logger.getLogger(ScreenshotService.class);

    @Inject
    ScreenshotStorage storage;

    @Inject
    QuotaService quota;

    @ConfigProperty(name = "screenshots.max-bytes")
    long maxBytes;

    /**
     * Kareyi kaydeder.
     *
     * @param capturedAt karenin ait olduğu <b>yayın</b> anı; istemci bildiriyor
     *                   çünkü hangi anı gördüğünü yalnızca o biliyor
     */
    @Transactional
    public ScreenshotDto capture(UUID channelId, Instant capturedAt, String note,
                                 Path file, String contentType, long size,
                                 Integer width, Integer height, String keycloakId) {
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        if (size <= 0) {
            throw AppException.badRequest("Boş görsel gönderildi.");
        }
        if (size > maxBytes) {
            throw AppException.badRequest(
                "Ekran görüntüsü en fazla " + QuotaService.human(maxBytes) + " olabilir.");
        }
        String extension = extensionOf(contentType);
        quota.requireRoom(keycloakId, size);

        Screenshot shot = new Screenshot();
        shot.channel = channel;
        shot.capturedBy = requireLocalUser(keycloakId);
        // Istemci gelecege ait bir an bildirmesin; gecmis serbest (geriye
        // sarmadan yakalanabiliyor).
        shot.capturedAt = capturedAt == null || capturedAt.isAfter(Instant.now())
            ? Instant.now()
            : capturedAt;
        shot.note = note == null || note.isBlank() ? null : note.trim();
        shot.width = width;
        shot.height = height;
        // Anahtar SUNUCUDA uretiliyor; istemciden asla alinmaz.
        shot.objectKey = org.example.storage.StoragePaths.channelFile(
            shot.capturedBy, channel.mediamtxPath, UUID.randomUUID() + "." + extension);
        shot.sizeBytes = storage.put(shot.objectKey, file, contentType);
        shot.persist();

        LOG.infof("Ekran görüntüsü kaydedildi: %s (%s, %,d bayt)",
            channel.name, shot.objectKey, shot.sizeBytes);
        return toDto(shot);
    }

    /** Kronolojik galeri. Yönetici tümünü, diğerleri yalnızca kendi karelerini görür. */
    public List<ScreenshotDto> gallery(UUID channelId, int offset, int limit,
                                       String keycloakId, boolean isAdmin) {
        return Screenshot.gallery(isAdmin ? null : keycloakId, channelId, offset, limit)
            .stream().map(this::toDto).toList();
    }

    @Transactional
    public void delete(UUID id, String keycloakId, boolean isAdmin) {
        Screenshot shot = requireVisible(id, keycloakId, isAdmin);
        storage.delete(shot.objectKey);
        shot.delete();
        LOG.infof("Ekran görüntüsü silindi: %s", id);
    }

    /** Temizlik süpürücüsü için; politika gereği siler, kullanıcı adına değil. */
    @Transactional
    public int deleteOlderThan(Instant cutoff, int limit) {
        List<Screenshot> expired = Screenshot.olderThan(cutoff, limit);
        for (Screenshot shot : expired) {
            storage.delete(shot.objectKey);
            shot.delete();
        }
        return expired.size();
    }

    // ------------------------------------------------------------------

    private Screenshot requireVisible(UUID id, String keycloakId, boolean isAdmin) {
        Screenshot shot = Screenshot.findById(id);
        if (shot == null) {
            throw AppException.notFound("Ekran görüntüsü bulunamadı: " + id);
        }
        if (!isAdmin && !shot.capturedBy.keycloakId.equals(keycloakId)) {
            // "Bulunamadi" degil "yasak": kaydin varligi zaten id'yi bilene belli.
            throw AppException.forbidden("Bu ekran görüntüsüne erişiminiz yok.");
        }
        return shot;
    }

    /** Uzantı bildirilen içerik tipinden; dosya adından türetmek yol ayracına açık kapı bırakırdı. */
    private static String extensionOf(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            default -> throw AppException.badRequest(
                "Desteklenmeyen görsel türü: " + contentType + ". PNG, JPEG veya WebP olmalı.");
        };
    }

    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    public static boolean isAdmin(Set<String> roles) {
        return roles.contains(Roles.YONETICI);
    }

    private ScreenshotDto toDto(Screenshot shot) {
        String fileName = shot.channel.mediamtxPath + "_"
            + shot.capturedAt.toString().replace(":", "-") + "."
            + shot.objectKey.substring(shot.objectKey.lastIndexOf('.') + 1);
        return new ScreenshotDto(
            shot.id,
            shot.channel.id,
            shot.channel.name,
            shot.capturedAt,
            shot.width,
            shot.height,
            shot.sizeBytes,
            shot.note,
            storage.viewUrl(shot.objectKey),
            storage.downloadUrl(shot.objectKey, fileName),
            fileName,
            shot.capturedBy.username,
            shot.createdAt);
    }
}
