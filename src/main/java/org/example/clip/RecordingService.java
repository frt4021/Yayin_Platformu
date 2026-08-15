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
import org.example.etkinlik.EtkinlikService;
import org.example.etkinlik.EtkinlikTuru;
import org.example.exception.AppException;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    @Inject
    ChannelRecordingGate gate;

    @Inject
    EtkinlikService etkinlikService;

    /** Kaydediciye "süren segmenti şimdi kapat" emri; bkz. {@link #stop}. */
    @Inject
    jakarta.enterprise.event.Event<org.example.dvr.DvrSignalEvent> sinyal;

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
     * <p><b>DVR kapalı kanallarda da çalışır.</b> Önceden reddediliyordu ve bu
     * tutarsızdı: kayda başlamak için önce kanal ayarını değiştirmek gerekiyor,
     * o sırada kaydedilmek istenen an çoktan geçiyordu. Artık kayıt bu kayıt
     * boyunca açılıyor ve durdurulunca geri kapatılıyor
     * ({@link ChannelRecordingGate}).
     */
    @Transactional
    public ActiveRecordingDto start(UUID channelId, String keycloakId) {
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        if (ActiveRecording.find(channelId, keycloakId) != null) {
            throw AppException.conflict("Bu kanalda zaten devam eden bir kaydınız var.");
        }

        ActiveRecording recording = new ActiveRecording();
        recording.channel = channel;
        recording.user = requireLocalUser(keycloakId);
        // Kaydi ONCE aciyoruz: acilamazsa satir hic olusmasin, yoksa kullanici
        // bos bir kaydi durdurmaya calisir ve elinde hicbir sey kalmazdi.
        recording.dvrBizden = gate.acquire(channel);
        recording.persist();

        LOG.infof("Kayıt başladı: %s (%s)", channel.name, keycloakId);
        etkinlikService.kaydet(EtkinlikTuru.KAYIT_BASLADI, keycloakId, "kanal", channelId, Map.of());
        return toDto(recording);
    }

    /**
     * Durdurma sonucu.
     *
     * @param clip  üretilebildiyse klip işi, aksi halde {@code null}
     * @param error klip açılamadıysa sebebi. <b>Kayıt yine de durdurulmuştur.</b>
     */
    public record StopResult(Instant start, Instant end, ClipDto clip, String error) {
    }

    /**
     * Kaydı durdurur ve klip işini açar.
     *
     * <p><b>Durdurma her koşulda başarılı olur.</b> Kayıt satırının silinmesi
     * ile klip üretimi <b>ayrı transaction'larda</b>: aynı işlemde olduklarında
     * klip doğrulaması başarısız olunca rollback tetikleniyor, satır geri
     * geliyor ve kullanıcı kaydı <b>bir daha hiç durduramıyordu</b>. Yaşandı:
     * sunucu yeniden başlayınca DVR çizelgesinde boşluk oluşuyor, doğrulama
     * reddediyor ve kayıt sonsuza kadar açık kalıyordu.
     *
     * <p>Bitiş anı <b>sunucuda</b> belirleniyor; istemcinin bildirdiği bir
     * zamana güvenilseydi geçmişe ya da geleceğe uzanan aralıklar istenebilirdi.
     */
    public StopResult stop(UUID channelId, String keycloakId) {
        // Ayri transaction: bundan sonrasi ne olursa olsun kayit durur.
        record Durdurulan(Instant start, UUID kanalId, boolean dvrBizden) { }
        Durdurulan durdurulan = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> {
                ActiveRecording recording = ActiveRecording.find(channelId, keycloakId);
                if (recording == null) {
                    throw AppException.notFound("Bu kanalda devam eden bir kaydınız yok.");
                }
                // Kimlik: proxy'nin id'sine erismek onu yuklemiyor ve
                // transaction disina tasinmasi guvenli.
                Durdurulan d = new Durdurulan(
                    recording.startedAt, recording.channel.id, recording.dvrBizden);
                recording.delete();
                return d;
            });
        Instant start = durdurulan.start();

        Instant end = Instant.now();
        Duration length = Duration.between(start, end);
        LOG.infof("Kayıt durduruldu: %s (%d sn)", channelId, length.toSeconds());
        etkinlikService.kaydet(EtkinlikTuru.KAYIT_DURDU, keycloakId, "kanal", channelId,
            Map.of("sureSn", length.toSeconds()));

        // Suren segmenti HEMEN kapattiriyoruz. Segment kapanmadan cizelgeye
        // satir yazilmiyor; 30 saniyelik segmentte 8 saniyelik bir kaydin
        // hicbir izi olmuyordu.
        sinyal.fire(org.example.dvr.DvrSignalEvent.kes(durdurulan.kanalId()));

        // Kaydi ONCE kapatiyoruz, klipten once: burada olmasi hangi yoldan
        // cikilirsa cikilsin calismasini garanti ediyor -- finally'ye gerek
        // kalmiyor.
        gate.release(durdurulan.kanalId(), durdurulan.dvrBizden());

        if (length.toSeconds() < 1) {
            return new StopResult(start, end, null,
                "Kayıt bir saniyeden kısa, klip üretilmedi.");
        }

        // KIRPMA BURADA YAPILMIYOR, isciye birakiliyor.
        //
        // Eskiden burada clampToRecorded cagriliyordu ve bos donunce kullanici
        // ELI BOS kaliyordu: "Bu aralikta diske yazilmis kayit yok". Sebep
        // kaydin olmamasi degil, HENUZ YAZILMAMIS olmasiydi -- kaydi durduran
        // kisi, kaydettigi segmentin kapanmasindan once soruyor. Senkron
        // dogrulama yanlis yerdeydi: klip zaten asenkron uretiliyor ve
        // beklenecek yer isci.
        try {
            ClipDto clip = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> clipService.create(channelId, new CreateClipRequest(start, end),
                    keycloakId, ClipOrigin.MANUEL_KAYIT));
            return new StopResult(start, end, clip, null);
        } catch (RuntimeException e) {
            // Kayit zaten durdu; klip acilamadi. Kullaniciya sebebi
            // soyleniyor ama islem basarisiz sayilmiyor.
            LOG.warnf(e, "Kayıt durduruldu ama klip açılamadı: %s", channelId);
            return new StopResult(start, end, null, e.getMessage());
        }
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
            boolean dvrBizden = recording.dvrBizden;
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
            } finally {
                gate.release(channelId, dvrBizden);
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
