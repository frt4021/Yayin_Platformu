package org.example.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.dto.ChannelDto;
import org.example.channel.dto.CreateChannelRequest;
import org.example.channel.dto.MediaMtxPathList;
import org.example.channel.dto.UpdateChannelRequest;
import org.example.channel.entity.Channel;
import org.example.exception.AppException;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kanal yönetimi.
 *
 * <p>Her yazma işlemi iki yeri birden değiştirir: veritabanı (kalıcı tanım) ve
 * MediaMTX (çalışan yapılandırma). Sıra her zaman <b>önce veritabanı, sonra
 * MediaMTX</b>. MediaMTX çağrısı patlarsa transaction geri alınır ve iki taraf
 * da değişmemiş olur; ters sırada çalışsaydık MediaMTX'te kaydı olmayan bir
 * yayın kalabilirdi.
 */
@ApplicationScoped
public class ChannelService {

    private static final Logger LOG = Logger.getLogger(ChannelService.class);

    @Inject
    MediaMtxService mediaMtx;

    @ConfigProperty(name = "mediamtx.hls-base-url")
    String hlsBaseUrl;

    @ConfigProperty(name = "channels.max-active")
    int maxActiveChannels;

    /** @param active şu an yayında olan kanal sayısı */
    public record Capacity(long active, int max) {
    }

    public Capacity capacity() {
        return new Capacity(Channel.countActive(null), maxActiveChannels);
    }

    /**
     * Kapasite denetimi. Sınırı MediaMTX'in kendisi uygulamıyor; aşıldığında
     * sessizce kabul edip <b>tüm</b> kanallarda bozulmaya yol açıyor. Bu yüzden
     * sınır burada, açık bir hata olarak uygulanıyor.
     */
    private void requireCapacity(UUID exceptId) {
        long active = Channel.countActive(exceptId);
        if (active >= maxActiveChannels) {
            throw AppException.conflict(
                "Aynı anda en fazla " + maxActiveChannels + " kanal yayında olabilir "
                    + "(şu an " + active + "). Önce bir kanalı pasife alın.");
        }
    }

    public List<ChannelDto> list() {
        Map<String, MediaMtxPathList.Item> states = mediaMtx.pathStates();
        return Channel.<Channel>listAll().stream()
            .map(channel -> toDto(channel, states.get(channel.mediamtxPath)))
            .toList();
    }

    public ChannelDto get(UUID id) {
        Channel channel = require(id);
        return toDto(channel, mediaMtx.pathStates().get(channel.mediamtxPath));
    }

    @Transactional
    public ChannelDto create(CreateChannelRequest req, String keycloakId) {
        requireNameFree(req.name(), null);
        requirePathFree(req.mediamtxPath(), null);
        if (req.active()) {
            requireCapacity(null);
        }

        Channel channel = new Channel();
        channel.name = req.name();
        channel.sourceUrl = req.sourceUrl();
        channel.mediamtxPath = req.mediamtxPath();
        channel.active = req.active();
        channel.dvrEnabled = req.dvrEnabled();
        channel.renditions = normalize(req.renditions());
        channel.dvrRendition = resolveDvrRendition(req.dvrRendition(), channel.renditions);
        channel.createdBy = requireLocalUser(keycloakId);
        channel.persist();

        if (channel.active) {
            mediaMtx.applyPath(channel.mediamtxPath, channel.sourceUrl,
                channel.dvrEnabled, channel.renditions, channel.dvrRendition);
        }
        LOG.infof("Kanal oluşturuldu: %s (path=%s, aktif=%s)",
            channel.name, channel.mediamtxPath, channel.active);
        return toDto(channel, null);
    }

    @Transactional
    public ChannelDto update(UUID id, UpdateChannelRequest req) {
        Channel channel = require(id);
        requireNameFree(req.name(), id);
        requirePathFree(req.mediamtxPath(), id);
        if (req.active()) {
            // Kanalın kendisi sayımdan düşülüyor: zaten aktif olan bir kanalı
            // düzenlemek kapasiteyi artırmıyor.
            requireCapacity(id);
        }

        String previousPath = channel.mediamtxPath;
        String previousRenditions = channel.renditions;
        boolean wasActive = channel.active;

        channel.name = req.name();
        channel.sourceUrl = req.sourceUrl();
        channel.mediamtxPath = req.mediamtxPath();
        channel.active = req.active();
        channel.dvrEnabled = req.dvrEnabled();
        channel.renditions = normalize(req.renditions());
        channel.dvrRendition = resolveDvrRendition(req.dvrRendition(), channel.renditions);

        // Path adı değiştiyse eski path artık hiçbir kanala ait değil; kaldırılmazsa
        // MediaMTX'te sahipsiz bir yayın olarak akmaya devam eder.
        if (!previousPath.equals(channel.mediamtxPath) && wasActive) {
            mediaMtx.removePath(previousPath, previousRenditions);
        } else if (wasActive && !previousRenditions.equals(channel.renditions)) {
            // Merdivenden çıkarılan rendition'ların path'i kalırsa MediaMTX'te
            // sahipsiz yayın olarak akmaya devam eder ve GPU'yu meşgul eder.
            mediaMtx.removeRenditions(channel.mediamtxPath, previousRenditions);
        }

        if (channel.active) {
            mediaMtx.applyPath(channel.mediamtxPath, channel.sourceUrl,
                channel.dvrEnabled, channel.renditions, channel.dvrRendition);
        } else if (wasActive) {
            mediaMtx.removePath(channel.mediamtxPath, channel.renditions);
        }

        LOG.infof("Kanal güncellendi: %s (path=%s, aktif=%s)",
            channel.name, channel.mediamtxPath, channel.active);
        return toDto(channel, null);
    }

    @Transactional
    public void delete(UUID id) {
        Channel channel = require(id);
        String path = channel.mediamtxPath;
        String name = channel.name;
        String rends = channel.renditions;
        channel.delete();
        mediaMtx.removePath(path, rends);
        LOG.infof("Kanal silindi: %s (path=%s)", name, path);
    }

    /**
     * Veritabanındaki aktif kanalları MediaMTX'e yeniden yazar.
     *
     * <p>Açılışta {@link ChannelRestorer} tarafından, sonrasında ihtiyaç
     * duyulduğunda uçtan elle çağrılır. Tek tek hata yönetiliyor: bir kanalın
     * kaynağı erişilemez olduğunda diğerlerinin de ayağa kalkmaması saçma olurdu.
     *
     * @return başarıyla yazılan kanal sayısı
     */
    public int restoreActiveChannels() {
        List<Channel> active = Channel.listActive();
        if (active.size() > maxActiveChannels) {
            // Sınır sonradan düşürülmüş olabilir. Kanalları kendiliğinden
            // kapatmıyoruz — hangisinin kapanacağı işletme kararı.
            LOG.warnf("Aktif kanal sayısı (%d) kapasitenin (%d) üzerinde; "
                + "hepsi yine de yazılıyor ama yayın kalitesi düşebilir.",
                active.size(), maxActiveChannels);
        }
        int restored = 0;
        for (Channel channel : active) {
            try {
                mediaMtx.applyPath(channel.mediamtxPath, channel.sourceUrl,
                    channel.dvrEnabled, channel.renditions, channel.dvrRendition);
                restored++;
            } catch (RuntimeException e) {
                LOG.errorf(e, "Kanal MediaMTX'e yazılamadı: %s (path=%s)",
                    channel.name, channel.mediamtxPath);
            }
        }
        LOG.infof("Aktif kanallar geri yüklendi: %d/%d", restored, active.size());
        return restored;
    }

    // ------------------------------------------------------------------

    /**
     * Merdiven tanımını doğrular ve normalleştirir.
     *
     * <p>Doğrulama burada yapılıyor: geçersiz bir tanım MediaMTX'e ulaşırsa
     * ffmpeg komutu bozuk üretilir ve hata ancak yayın başlarken, konteyner
     * logunda görünür — kullanıcıya hiç yansımaz.
     */
    private String normalize(String spec) {
        if (spec == null || spec.isBlank()) {
            return "";
        }
        String trimmed = spec.trim();
        try {
            Rendition.parse(trimmed);
        } catch (RuntimeException e) {
            throw AppException.badRequest(
                "Geçersiz çözünürlük merdiveni. Beklenen biçim: "
                    + "720p|1280x720|1500k,480p|854x480|800k");
        }
        return trimmed;
    }

    /** DVR kaydı için varsayılan rendition. */
    private static final String DEFAULT_DVR_RENDITION = "720p";

    /**
     * Kaydın alınacağı rendition'ı belirler.
     *
     * <p>İstek boş bırakılmışsa ve merdivende {@value #DEFAULT_DVR_RENDITION}
     * varsa oradan kaydedilir: ölçümde kaynak 2.33 Mbps, 720p 1.65 Mbps —
     * diskte %29 tasarruf. İstenen rendition merdivende yoksa kaynağa düşülür,
     * çünkü var olmayan bir path'e kayıt açmak sessizce hiç kayıt üretmezdi.
     */
    private String resolveDvrRendition(String requested, String renditionSpec) {
        List<Rendition> ladder = Rendition.parse(renditionSpec);
        if (ladder.isEmpty()) {
            return "";
        }
        String wanted = (requested == null || requested.isBlank())
            ? DEFAULT_DVR_RENDITION
            : requested.trim();
        return ladder.stream().anyMatch(r -> r.suffix().equals(wanted)) ? wanted : "";
    }

    private Channel require(UUID id) {
        Channel channel = Channel.findById(id);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + id);
        }
        return channel;
    }

    private void requireNameFree(String name, UUID exceptId) {
        if (Channel.nameTaken(name, exceptId)) {
            throw AppException.conflict("Bu isimde bir kanal zaten var: " + name);
        }
    }

    private void requirePathFree(String path, UUID exceptId) {
        if (Channel.pathTaken(path, exceptId)) {
            throw AppException.conflict("Bu MediaMTX path'i zaten kullanılıyor: " + path);
        }
    }

    /**
     * Kanalı oluşturan kullanıcının yerel kaydı. Normalde
     * {@code UserProvisioningFilter} bu satırı istek başında oluşturur; yoksa
     * eşitleme çalışmamış demektir ve foreign key zaten yazmaya izin vermezdi.
     */
    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    private ChannelDto toDto(Channel channel, MediaMtxPathList.Item state) {
        return new ChannelDto(
            channel.id,
            channel.name,
            channel.sourceUrl,
            channel.mediamtxPath,
            channel.active,
            channel.dvrEnabled,
            channel.renditions,
            channel.dvrRendition,
            hlsBaseUrl + "/" + channel.mediamtxPath + "/index.m3u8",
            state == null ? null : state.ready(),
            state == null || state.readers() == null ? null : state.readers().size(),
            channel.createdBy == null ? null : channel.createdBy.username,
            channel.createdAt
        );
    }
}
