package org.example.clip;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.entity.Channel;
import org.example.clip.dto.ClipDto;
import org.example.clip.dto.CreateClipRequest;
import org.example.clip.entity.Clip;
import org.example.dvr.DvrService;
import org.example.exception.AppException;
import org.example.user.Roles;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Klip işlerinin oluşturulması ve sorgulanması. Asıl üretim
 * {@link ClipWorker} tarafından arka planda yapılır.
 */
@ApplicationScoped
public class
ClipService {

    private static final Logger LOG = Logger.getLogger(ClipService.class);

    @Inject
    DvrService dvrService;

    @Inject
    ClipStorage storage;

    /**
     * Yeni işi duyurur. Dinleyici AFTER_SUCCESS ile bağlı: bildirim ancak
     * transaction commit edildikten sonra Redis'e gider.
     */
    @Inject
    Event<ClipQueuedEvent> queued;

    @ConfigProperty(name = "clips.max-duration-minutes")
    int maxDurationMinutes;

    /**
     * Klip işi oluşturur ve kuyruğa alır.
     *
     * <p>İstek burada biter; dosya arka planda üretilir. Senkron üretilseydi
     * 2 saatlik bir klip (6 Mbps'te ~5.4 GB) HTTP bağlantısını dakikalarca
     * açık tutar, kullanıcı sekmeyi kapatınca iş boşa giderdi.
     */
    @Transactional
    public ClipDto create(UUID channelId, CreateClipRequest req, String keycloakId) {
        Duration duration = Duration.between(req.start(), req.end());
        if (duration.isNegative() || duration.isZero()) {
            throw AppException.badRequest("Bitiş zamanı başlangıçtan sonra olmalı.");
        }
        if (duration.toMinutes() > maxDurationMinutes) {
            throw AppException.badRequest(
                "Klip en fazla " + maxDurationMinutes + " dakika olabilir "
                    + "(istenen: " + duration.toMinutes() + " dakika).");
        }

        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        if (!channel.dvrEnabled) {
            throw AppException.badRequest("Bu kanalda geriye sarma kapalı: " + channel.name);
        }

        // Kısmen kayıtlı bir aralıktan klip üretmek, kullanıcının beklediğinden
        // kısa veya boşluklu bir dosya çıkarır. Baştan reddetmek daha dürüst.
        if (!dvrService.isFullyRecorded(channelId, req.start(), req.end())) {
            throw AppException.badRequest(
                "Seçilen aralığın tamamı kayıtlı değil. Zaman çizelgesinde dolu bir bölge seçin.");
        }

        Clip clip = new Clip();
        clip.channel = channel;
        clip.requestedBy = requireLocalUser(keycloakId);
        clip.startAt = req.start();
        clip.endAt = req.end();
        clip.status = ClipStatus.BEKLIYOR;
        clip.persist();

        queued.fire(new ClipQueuedEvent(clip.id));

        LOG.infof("Klip kuyruğa alındı: %s %s..%s (%d sn)",
            channel.name, req.start(), req.end(), duration.toSeconds());
        return toDto(clip);
    }

    public List<ClipDto> list(UUID channelId, String keycloakId, boolean isAdmin) {
        // Yönetici hepsini görür; diğerleri yalnızca kendi kliplerini.
        // Klipler kayıt içeriği barındırdığı için varsayılan kapalı olmalı.
        String query = isAdmin ? "1=1" : "requestedBy.keycloakId = ?1";
        Object[] params = isAdmin ? new Object[0] : new Object[]{keycloakId};

        List<Clip> clips = channelId == null
            ? Clip.find(query + " order by createdAt desc", params).list()
            : Clip.find(query + " and channel.id = ?" + (params.length + 1)
                + " order by createdAt desc",
                append(params, channelId)).list();

        return clips.stream().map(this::toDto).toList();
    }

    public ClipDto get(UUID id, String keycloakId, boolean isAdmin) {
        return toDto(requireVisible(id, keycloakId, isAdmin));
    }

    /**
     * İzleme ve indirme adresleri. Dosya MinIO'dan doğrudan gelir,
     * backend'den geçmez.
     *
     * @param stream   {@code <video src>} ile oynatılabilir
     * @param download tarayıcıyı dosyayı kaydetmeye zorlar
     * @param fileName önerilen dosya adı
     */
    public record ClipLinks(String stream, String download, String fileName) {
    }

    public ClipLinks links(UUID id, String keycloakId, boolean isAdmin) {
        Clip clip = requireVisible(id, keycloakId, isAdmin);
        if (clip.status != ClipStatus.HAZIR) {
            throw AppException.badRequest(
                "Klip henüz hazır değil (durum: " + clip.status + ").");
        }
        String name = fileNameOf(clip);
        return new ClipLinks(
            storage.streamUrl(clip.objectKey),
            storage.downloadUrl(clip.objectKey, name),
            name);
    }

    @Transactional
    public void delete(UUID id, String keycloakId, boolean isAdmin) {
        Clip clip = requireVisible(id, keycloakId, isAdmin);
        if (clip.status == ClipStatus.ISLENIYOR) {
            throw AppException.conflict("İşlenmekte olan klip silinemez.");
        }
        if (clip.objectKey != null) {
            storage.delete(clip.objectKey);
        }
        clip.delete();
        LOG.infof("Klip silindi: %s", id);
    }

    // ------------------------------------------------------------------

    private Clip requireVisible(UUID id, String keycloakId, boolean isAdmin) {
        Clip clip = Clip.findById(id);
        if (clip == null) {
            throw AppException.notFound("Klip bulunamadı: " + id);
        }
        if (!isAdmin && !clip.requestedBy.keycloakId.equals(keycloakId)) {
            // "Bulunamadı" değil "yasak": klibin varlığı zaten id'yi bilene belli.
            throw AppException.forbidden("Bu klibe erişiminiz yok.");
        }
        return clip;
    }

    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    private static Object[] append(Object[] params, Object extra) {
        Object[] out = new Object[params.length + 1];
        System.arraycopy(params, 0, out, 0, params.length);
        out[params.length] = extra;
        return out;
    }

    static String fileNameOf(Clip clip) {
        return clip.channel.mediamtxPath + "_" + clip.startAt.toString().replace(":", "-") + ".mp4";
    }

    public static boolean isAdmin(java.util.Set<String> roles) {
        return roles.contains(Roles.YONETICI);
    }

    ClipDto toDto(Clip clip) {
        return new ClipDto(
            clip.id,
            clip.channel.id,
            clip.channel.name,
            clip.startAt,
            clip.endAt,
            clip.duration().toSeconds(),
            clip.status,
            clip.sizeBytes,
            clip.error,
            clip.requestedBy.username,
            clip.createdAt,
            clip.completedAt
        );
    }
}
