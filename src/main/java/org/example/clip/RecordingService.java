package org.example.clip;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.entity.Channel;
import org.example.clip.dto.ActiveRecordingDto;
import org.example.clip.dto.ClipDto;
import org.example.clip.dto.CreateClipRequest;
import org.example.clip.entity.ActiveRecording;
import org.example.exception.AppException;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manuel kayıt: "kayda başla" / "durdur".
 *
 * <p><b>Yeni bir kayıt mekanizması yok.</b> DVR zaten sürekli kaydediyor;
 * burada yapılan tek şey başlangıç anını saklamak ve durdurulduğunda o aralık
 * için bir klip işi açmak. Böylece kuyruk, yeniden deneme, süpürücü ve imzalı
 * indirme hattının tamamı olduğu gibi kullanılıyor.
 */
@ApplicationScoped
public class RecordingService {

    private static final Logger LOG = Logger.getLogger(RecordingService.class);

    @Inject
    ClipService clipService;

    /**
     * Kayıt bu süreyi aşarsa süpürücü otomatik durdurur.
     *
     * <p>Klip üst sınırıyla aynı olmalı: daha uzun bir kayıt durdurulduğunda
     * klip isteği zaten reddedilir ve kullanıcı hiçbir şey elde edemezdi.
     */
    @ConfigProperty(name = "clips.max-duration-minutes")
    int maxDurationMinutes;

    /**
     * Kaydı başlatır.
     *
     * <p>DVR kapalı kanalda kayıt alınamaz: geçmiş yok, durdurulduğunda
     * çekilecek bir şey olmazdı. Baştan reddetmek, kullanıcıyı boşuna
     * bekletmekten dürüst.
     */
    @Transactional
    public ActiveRecordingDto start(UUID channelId, String keycloakId) {
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        if (!channel.dvrEnabled) {
            throw AppException.badRequest(
                "Bu kanalda geriye sarma kapalı, kayıt alınamaz: " + channel.name);
        }
        if (ActiveRecording.find(channelId, keycloakId) != null) {
            throw AppException.conflict("Bu kanalda zaten devam eden bir kaydınız var.");
        }

        ActiveRecording recording = new ActiveRecording();
        recording.channel = channel;
        recording.user = requireLocalUser(keycloakId);
        recording.persist();

        LOG.infof("Kayıt başladı: %s (%s)", channel.name, keycloakId);
        return toDto(recording);
    }

    /**
     * Kaydı durdurur ve klip işini açar.
     *
     * <p>Bitiş anı <b>sunucuda</b> belirleniyor; istemcinin bildirdiği bir
     * zamana güvenilseydi geçmişe ya da geleceğe uzanan aralıklar istenebilirdi.
     */
    @Transactional
    public ClipDto stop(UUID channelId, String keycloakId) {
        ActiveRecording recording = ActiveRecording.find(channelId, keycloakId);
        if (recording == null) {
            throw AppException.notFound("Bu kanalda devam eden bir kaydınız yok.");
        }

        Instant start = recording.startedAt;
        Instant end = Instant.now();
        recording.delete();

        Duration length = Duration.between(start, end);
        if (length.toSeconds() < 1) {
            throw AppException.badRequest("Kayıt bir saniyeden kısa, klip üretilmedi.");
        }

        // Uretimi ClipService yapiyor: aralik dogrulamasi, kapasite ve kuyruk
        // mantigi orada. Burada yalnizca "hangi aralik" sorusu cevaplaniyor.
        ClipDto clip = clipService.create(
            channelId, new CreateClipRequest(start, end), keycloakId, ClipOrigin.MANUEL_KAYIT);

        LOG.infof("Kayıt durduruldu: %s (%d sn)", channelId, length.toSeconds());
        return clip;
    }

    /** Kullanıcının devam eden kayıtları — arayüz düğmeyi doğru durumda çizsin. */
    public List<ActiveRecordingDto> listActive(String keycloakId) {
        return ActiveRecording.listFor(keycloakId).stream().map(this::toDto).toList();
    }

    /**
     * Üst sınırı aşan kayıtları otomatik durdurur.
     *
     * <p>Kullanıcı sekmeyi kapatırsa kayıt sonsuza kadar açık kalır. Sınıra
     * gelince <b>durduruluyor</b>, reddedilmiyor: kullanıcı en azından
     * sınıra kadar olan kaydı elde ediyor. Reddetseydik saatlerce açık kalmış
     * bir kayıttan hiçbir şey çıkmazdı.
     *
     * @return durdurulan kayıt sayısı
     */
    @Transactional
    public int autoStopOverdue() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(maxDurationMinutes));
        List<ActiveRecording> overdue = ActiveRecording.startedBefore(cutoff);
        int stopped = 0;

        for (ActiveRecording recording : overdue) {
            UUID channelId = recording.channel.id;
            String keycloakId = recording.user.keycloakId;
            Instant start = recording.startedAt;
            recording.delete();

            try {
                clipService.create(channelId,
                    new CreateClipRequest(start, start.plus(Duration.ofMinutes(maxDurationMinutes))),
                    keycloakId, ClipOrigin.MANUEL_KAYIT);
                LOG.infof("Üst sınırı aşan kayıt otomatik durduruldu: %s", channelId);
            } catch (RuntimeException e) {
                // Klip acilamadi (ornegin aralik tam kayitli degil). Kaydi yine
                // de kapatiyoruz -- acik birakmak sonsuza kadar buyuyen bir
                // istek uretirdi.
                LOG.warnf(e, "Süresi dolan kayıt kapatıldı ama klip açılamadı: %s", channelId);
            }
            stopped++;
        }
        return stopped;
    }

    // ------------------------------------------------------------------

    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    private ActiveRecordingDto toDto(ActiveRecording recording) {
        return new ActiveRecordingDto(
            recording.channel.id,
            recording.channel.name,
            recording.startedAt,
            maxDurationMinutes);
    }
}
