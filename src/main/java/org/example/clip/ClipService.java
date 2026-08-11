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

    @Inject
    org.example.storage.QuotaService quota;

    /**
     * Klip işi oluşturur ve kuyruğa alır.
     *
     * <p>İstek burada biter; dosya arka planda üretilir. Senkron üretilseydi
     * 2 saatlik bir klip (6 Mbps'te ~5.4 GB) HTTP bağlantısını dakikalarca
     * açık tutar, kullanıcı sekmeyi kapatınca iş boşa giderdi.
     */
    @Transactional
    public ClipDto create(UUID channelId, CreateClipRequest req, String keycloakId) {
        return create(channelId, req, keycloakId, ClipOrigin.ARALIK);
    }

    /**
     * @param origin klibin nasıl istendiği. Manuel kayıtlar da bu yoldan
     *               geçiyor — ürün ve yaşam döngüsü aynı olduğu için ayrı bir
     *               üretim hattı kurmak gereksiz olurdu.
     */
    @Transactional
    public ClipDto create(UUID channelId, CreateClipRequest req, String keycloakId,
                          ClipOrigin origin) {
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
        // Geriye sarma sarti YALNIZCA aralik seciminde. Manuel ve planli
        // kayitta kanalin DVR'i kapali olabilir: kayit is suresince
        // aciliyor (ChannelRecordingGate) ve bitince geri kapaniyor, yani
        // istenen aralik diske YAZILMIS oluyor. Burada koru koru reddetmek,
        // kaydin durdurulup hicbir klip uretilmemesine yol aciyordu.
        if (origin == ClipOrigin.ARALIK && !channel.dvrEnabled) {
            throw AppException.badRequest("Bu kanalda geriye sarma kapalı: " + channel.name);
        }

        // Aralik secimiyle istenen kliplerde tam kapsama araniyor: kullanici
        // cizelgeden bilerek bir bolge secti, eksigini sessizce vermek
        // yaniltici olurdu.
        //
        // MANUEL KAYITTA ARANMIYOR. Kullanici o pencereyi zaten kaydetti;
        // arada sunucu yeniden baslamis ve cizelgede bosluk olusmussa bu onun
        // hatasi degil ve elindekini tamamen kaybetmesi en kotu sonuc olur.
        // MediaMTX bosluklu araligi sorunsuz veriyor (olculdu: 10 dakikalik
        // bosluklu pencereden 57 MB gecerli MP4).
        if (origin == ClipOrigin.ARALIK
            && !dvrService.isFullyRecorded(channelId, req.start(), req.end())) {
            throw AppException.badRequest(
                "Seçilen aralığın tamamı kayıtlı değil. Zaman çizelgesinde dolu bir bölge seçin.");
        }

        // Klip boyutu ONCEDEN bilinmiyor (dosya arka planda uretiliyor), o
        // yuzden yalnizca "kota zaten dolu mu" sorulabiliyor.
        quota.requireRoom(keycloakId, 0);

        Clip clip = new Clip();
        clip.channel = channel;
        // Kanal adinin kopyasi. Kanal silinip bag koparilinca (V21) geriye
        // kalan tek ipucu bu; silme aninda degil OLUSTURMA aninda yaziliyor
        // ki bu yolun disinda olusan satirlar bos kalmasin.
        clip.channelName = channel.name;
        clip.requestedBy = requireLocalUser(keycloakId);
        clip.startAt = req.start();
        clip.endAt = req.end();
        clip.status = ClipStatus.BEKLIYOR;
        clip.origin = origin;
        clip.persist();

        queued.fire(new ClipQueuedEvent(clip.id));

        LOG.infof("Klip kuyruğa alındı: %s %s..%s (%d sn)",
            channel.name, req.start(), req.end(), duration.toSeconds());
        return toDto(clip);
    }

    public List<ClipDto> list(UUID channelId, String keycloakId, boolean isAdmin) {
        return list(channelId, null, keycloakId, isAdmin);
    }

    /**
     * @param origin {@code null} ise hepsi; doluysa yalnızca o türdekiler
     *               ("kliplerim" / "kayıtlarım" ayrımı)
     */
    public List<ClipDto> list(UUID channelId, ClipOrigin origin,
                              String keycloakId, boolean isAdmin) {
        // Adlandirilmis parametre: uc opsiyonel suzgecte konumsal (?1, ?2)
        // yaklasim sira hatasina cok acikti.
        StringBuilder ql = new StringBuilder();
        io.quarkus.panache.common.Parameters params = new io.quarkus.panache.common.Parameters();

        // Yönetici hepsini görür; diğerleri yalnızca kendi kliplerini.
        // Klipler kayıt içeriği barındırdığı için varsayılan kapalı olmalı.
        if (!isAdmin) {
            ql.append("requestedBy.keycloakId = :sahip");
            params.and("sahip", keycloakId);
        }
        if (channelId != null) {
            appendAnd(ql).append("channel.id = :kanal");
            params.and("kanal", channelId);
        }
        if (origin != null) {
            appendAnd(ql).append("origin = :kaynak");
            params.and("kaynak", origin);
        }
        ql.append(ql.isEmpty() ? "order by createdAt desc" : " order by createdAt desc");

        return Clip.find(ql.toString(), params).<Clip>list()
            .stream().map(this::toDto).toList();
    }

    private static StringBuilder appendAnd(StringBuilder ql) {
        if (!ql.isEmpty()) {
            ql.append(" and ");
        }
        return ql;
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

    /**
     * İndirme dosya adı.
     *
     * <p>Kanal silinmiş olabilir (V21 sonrası {@code channel} null olabiliyor);
     * o durumda kaydedilen kanal adına düşülüyor. İkisi de yoksa yalnızca
     * zaman kalıyor — dosyanın adsız kalmasından iyi.
     */
    static String fileNameOf(Clip clip) {
        String kanal = clip.channel != null
            ? clip.channel.mediamtxPath
            : org.example.storage.StoragePaths.slug(clip.channelName);
        String zaman = clip.startAt.toString().replace(":", "-");
        return kanal == null || kanal.isEmpty() ? zaman + ".mp4" : kanal + "_" + zaman + ".mp4";
    }

    public static boolean isAdmin(java.util.Set<String> roles) {
        return roles.contains(Roles.YONETICI);
    }

    ClipDto toDto(Clip clip) {
        return new ClipDto(
            clip.id,
            // Kanal silinmis olabilir: bag koparilmis ama klip duruyor.
            // Arayuz kimlik yoksa adi "(silinmis)" diye gosteriyor.
            clip.channel != null ? clip.channel.id : null,
            clip.channel != null ? clip.channel.name : clip.channelName,
            clip.startAt,
            clip.endAt,
            clip.duration().toSeconds(),
            clip.status,
            clip.origin,
            clip.sizeBytes,
            clip.error,
            clip.requestedBy.username,
            clip.createdAt,
            clip.completedAt
        );
    }
}
