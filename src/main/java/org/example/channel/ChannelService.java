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
        channel.createdBy = requireLocalUser(keycloakId);
        channel.persist();

        if (channel.active) {
            mediaMtx.applyPath(channel.mediamtxPath, channel.sourceUrl, channel.dvrEnabled);
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
        boolean wasActive = channel.active;

        channel.name = req.name();
        channel.sourceUrl = req.sourceUrl();
        channel.mediamtxPath = req.mediamtxPath();
        channel.active = req.active();
        channel.dvrEnabled = req.dvrEnabled();

        // Path adı değiştiyse eski path artık hiçbir kanala ait değil; kaldırılmazsa
        // MediaMTX'te sahipsiz bir yayın olarak akmaya devam eder.
        if (!previousPath.equals(channel.mediamtxPath) && wasActive) {
            mediaMtx.removePath(previousPath);
        }

        if (channel.active) {
            mediaMtx.applyPath(channel.mediamtxPath, channel.sourceUrl, channel.dvrEnabled);
        } else if (wasActive) {
            mediaMtx.removePath(channel.mediamtxPath);
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
        channel.delete();
        mediaMtx.removePath(path);
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
                mediaMtx.applyPath(channel.mediamtxPath, channel.sourceUrl, channel.dvrEnabled);
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
            hlsBaseUrl + "/" + channel.mediamtxPath + "/index.m3u8",
            state == null ? null : state.ready(),
            state == null || state.readers() == null ? null : state.readers().size(),
            channel.createdBy == null ? null : channel.createdBy.username,
            channel.createdAt
        );
    }
}
