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
import org.example.radio.entity.Radio;
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

    @Inject
    SourceProbe sourceProbe;

    @Inject
    org.example.viewer.ViewerPresence viewerPresence;

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
        applySourceProbe(channel);
        channel.createdBy = requireLocalUser(keycloakId);
        channel.persist();

        if (channel.active) {
            mediaMtx.applyPath(channel.mediamtxPath, channel.effectiveSourceUrl(),
                channel.renditions);
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
        applySourceProbe(channel);

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
            mediaMtx.applyPath(channel.mediamtxPath, channel.effectiveSourceUrl(),
                channel.renditions);
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
                mediaMtx.applyPath(channel.mediamtxPath, channel.effectiveSourceUrl(),
                    channel.renditions);
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

    /**
     * Kaynağı inceler, MediaMTX'e verilecek adresi belirler ve merdiveni
     * kaynağın çözünürlüğüne karşı doğrular.
     *
     * <p>İki ayrı sorunu kapatıyor:
     *
     * <ol>
     *   <li><b>Master playlist tuzağı.</b> Master verildiğinde MediaMTX en
     *       yüksek varyantı seçiyor; segmentleri ~4 MB'ı aşarsa yayın hiç
     *       başlamıyor ve kullanıcı hata görmüyor. Artık varyantı uygulama
     *       seçip {@code resolvedSourceUrl}'e yazıyor.</li>
     *   <li><b>Merdiven kaynağın üstüne çıkamaz.</b> Kaynağın vermediği
     *       ayrıntı üretilemez; büyütme yalnızca bant genişliği ve GPU harcar.
     *       Çözünürlük tespit edilemediyse doğrulama atlanıyor — bilinmezlik
     *       yüzünden kaydetmeyi engellemek, düzeltmeyi imkânsızlaştırırdı.</li>
     * </ol>
     */
    private void applySourceProbe(Channel channel) {
        SourceProbe.Result result = sourceProbe.probe(channel.sourceUrl);

        channel.sourceWidth = result.width();
        channel.sourceHeight = result.height();
        channel.resolvedSourceUrl =
            result.effectiveUrl().equals(channel.sourceUrl) ? null : result.effectiveUrl();

        if (result.note() != null) {
            LOG.infof("%s: %s", channel.name, result.note());
        }

        requireLadderWithinSource(channel);
    }

    /** Merdivendeki hiçbir hedef kaynağın çözünürlüğünü aşamaz. */
    private void requireLadderWithinSource(Channel channel) {
        if (channel.sourceHeight == null) {
            return;
        }
        for (Rendition rendition : Rendition.parse(channel.renditions)) {
            if (rendition.height() > channel.sourceHeight) {
                throw AppException.badRequest(String.format(
                    "'%s' rendition'ı kaynaktan yüksek: kaynak %dx%d, istenen %dx%d. "
                        + "Kaynağın vermediği ayrıntı üretilemez; büyütme yalnızca bant "
                        + "genişliği ve GPU harcar.",
                    rendition.suffix(), channel.sourceWidth, channel.sourceHeight,
                    rendition.width(), rendition.height()));
            }
        }
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

    /**
     * Path hem kanallarda hem radyolarda benzersiz olmalı.
     *
     * <p>İkisi MediaMTX'te <b>aynı isim alanını</b> paylaşıyor. Yalnızca kendi
     * tablosuna bakan bir kontrol, aynı path'i kullanan bir kanal ile radyonun
     * birbirinin yayınını ezmesine izin verirdi; iki ayrı unique kısıt bunu
     * yakalayamaz.
     */
    private void requirePathFree(String path, UUID exceptId) {
        if (Channel.pathTaken(path, exceptId)) {
            throw AppException.conflict("Bu MediaMTX path'i zaten kullanılıyor: " + path);
        }
        if (Radio.pathTaken(path, null)) {
            throw AppException.conflict(
                "Bu MediaMTX path'i bir radyo tarafından kullanılıyor: " + path);
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
            channel.resolvedSourceUrl,
            channel.sourceWidth,
            channel.sourceHeight,
            hlsBaseUrl + "/" + channel.mediamtxPath + "/index.m3u8",
            state == null ? null : state.ready(),
            // MediaMTX'in reader sayisi DEGIL -- sekme bazli, bkz. ViewerPresence.
            viewerPresence.sayisi(channel.id),
            channel.createdBy == null ? null : channel.createdBy.username,
            channel.createdAt
        );
    }
}
