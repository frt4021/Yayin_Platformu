package org.example.clip;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.entity.Channel;
import org.example.clip.dto.ClipDto;
import org.example.clip.dto.CreateClipRequest;
import org.example.clip.dto.CreateScheduledRecordingRequest;
import org.example.clip.dto.ScheduledRecordingDto;
import org.example.clip.entity.ScheduledRecording;
import org.example.exception.AppException;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planlı kayıt: kullanıcının <b>önceden</b> verdiği kayıt emri.
 *
 * <h2>Neden yeni bir kayıt mekanizması yok</h2>
 * Klip hattı, MediaMTX'in diske yazdığı bir zaman aralığını MinIO'ya
 * kopyalıyor — arada ffmpeg bile yok, saf bayt aktarımı. Geleceğe dönük bir
 * emir için eksik olan tek şey, <b>o aralığın diske yazılmış olması</b>.
 * Dolayısıyla burada yapılan iş şu ikisiyle sınırlı:
 *
 * <ol>
 *   <li>aralık başlarken kanalın kaydettiğinden emin ol,</li>
 *   <li>aralık bitince mevcut klip işini aç.</li>
 * </ol>
 *
 * <p>Alternatif, aralık boyunca canlı yayını çeken bir ffmpeg süreci
 * başlatmaktı. Reddedildi: backend imajında ffmpeg yok, süreç yönetimi
 * (yeniden başlatma, zombi süreç, çıkış kodu) tamamen yeni bir hata yüzeyi
 * açardı ve MediaMTX zaten tam bu işi yapmak için orada duruyor.
 *
 * <h2>DVR kapalı kanallar</h2>
 * Emir verildiğinde kanalın geriye sarması kapalıysa, kayıt <b>o aralık
 * boyunca</b> açılıyor ve bitince geri kapatılıyor ({@code dvrBizden}).
 * Kullanıcıdan kanal ayarını önceden değiştirmesini istemek, "kayıt alamıyorum"
 * şikâyetinin ta kendisiydi.
 */
@ApplicationScoped
public class ScheduledRecordingService {

    private static final Logger LOG = Logger.getLogger(ScheduledRecordingService.class);

    @Inject
    ClipService clipService;

    @Inject
    ChannelRecordingGate gate;

    /** Kaydediciye "süren segmenti şimdi kapat" emri; bkz. {@code finish}. */
    @Inject
    jakarta.enterprise.event.Event<org.example.dvr.DvrSignalEvent> sinyal;

    @ConfigProperty(name = "clips.max-duration-minutes")
    int maxDurationMinutes;

    // ------------------------------------------------------------------
    // Emir verme
    // ------------------------------------------------------------------

    /**
     * Kayıt emri oluşturur.
     *
     * <p>Aralık tamamen geçmişteyse emir kuyruğa girmiyor: beklenecek bir şey
     * yok, klip doğrudan açılıyor. Bu ayrım kullanıcıya "geçmiş" ve "gelecek"
     * için iki ayrı ekran göstermemizi de gereksiz kılıyor — tek form, aralık
     * nereye düşerse düşsün.
     */
    @Transactional
    public ScheduledRecordingDto create(UUID channelId,
                                        CreateScheduledRecordingRequest req,
                                        String keycloakId) {
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        AppUser user = requireLocalUser(keycloakId);

        Duration length = Duration.between(req.baslangic(), req.bitis());
        if (length.isNegative() || length.isZero()) {
            throw AppException.badRequest("Bitiş zamanı başlangıçtan sonra olmalı.");
        }
        if (length.toMinutes() > maxDurationMinutes) {
            throw AppException.badRequest(
                "Kayıt en fazla " + maxDurationMinutes + " dakika olabilir. "
                    + "Seçilen aralık: " + length.toMinutes() + " dakika.");
        }
        if (ScheduledRecording.overlaps(channelId, user.id, req.baslangic(), req.bitis())) {
            throw AppException.conflict(
                "Bu kanalda aynı aralığa denk gelen bir kayıt emriniz zaten var.");
        }

        ScheduledRecording plan = new ScheduledRecording();
        plan.channel = channel;
        plan.user = user;
        plan.baslangic = req.baslangic();
        plan.bitis = req.bitis();

        // Aralik tamamen gecmisteyse beklemenin anlami yok: klibi hemen ac.
        if (req.bitis().isBefore(Instant.now())) {
            plan.durum = ScheduledStatus.KAYITTA;   // asagida hemen sonuclanacak
            plan.persist();
            finish(plan);
            return toDto(plan);
        }

        plan.persist();
        LOG.infof("Kayıt emri alındı: %s [%s → %s]",
            channel.name, req.baslangic(), req.bitis());
        return toDto(plan);
    }

    @Transactional
    public void cancel(UUID id, String keycloakId, boolean isAdmin) {
        ScheduledRecording plan = requireVisible(id, keycloakId, isAdmin);
        if (plan.durum != ScheduledStatus.BEKLIYOR && plan.durum != ScheduledStatus.KAYITTA) {
            throw AppException.badRequest("Bu emir zaten sonuçlanmış, iptal edilemez.");
        }
        plan.durum = ScheduledStatus.IPTAL;
        // Durum ONCE degisti: kapi "hala KAYITTA olan var mi" diye baktigi
        // icin bu emir sayimin disinda kaliyor.
        gate.release(plan.channel.id, plan.dvrBizden);
    }

    public List<ScheduledRecordingDto> list(String keycloakId, boolean isAdmin) {
        List<ScheduledRecording> rows =
            isAdmin ? ScheduledRecording.listAll() : ScheduledRecording.listFor(keycloakId);
        return rows.stream().map(this::toDto).toList();
    }

    // ------------------------------------------------------------------
    // Zamanlayıcının çağırdıkları
    // ------------------------------------------------------------------

    /**
     * Aralığı başlamış bir emri kayda alır.
     *
     * <p>Her emir <b>ayrı transaction'da</b> işleniyor ({@link
     * ScheduledRecordingScheduler}): biri MediaMTX hatası verdiğinde diğerleri
     * de geri alınmamalı.
     */
    @Transactional
    public void begin(UUID planId) {
        ScheduledRecording plan = ScheduledRecording.findById(planId);
        if (plan == null || plan.durum != ScheduledStatus.BEKLIYOR) {
            return;
        }

        try {
            plan.dvrBizden = gate.acquire(plan.channel);
        } catch (RuntimeException e) {
            // Kayit acilamadi -- emri KAYITTA'ya gecirip bitiste denemek
            // yanlis olurdu: aralik boyunca hicbir sey yazilmayacak.
            plan.durum = ScheduledStatus.BASARISIZ;
            plan.hata = "Kanalda kayıt açılamadı: " + e.getMessage();
            LOG.errorf(e, "Planlı kayıt başlatılamadı: %s", plan.id);
            return;
        }
        plan.durum = ScheduledStatus.KAYITTA;
    }

    /** Aralığı dolmuş bir emri kliple sonuçlandırır. */
    @Transactional
    public void complete(UUID planId) {
        ScheduledRecording plan = ScheduledRecording.findById(planId);
        if (plan == null || plan.durum != ScheduledStatus.KAYITTA) {
            return;
        }
        finish(plan);
    }

    // ------------------------------------------------------------------

    /** Klip işini açar ve gerekiyorsa kaydı geri kapatır. */
    private void finish(ScheduledRecording plan) {
        try {
            // Suren segmenti hemen kapattiriyoruz: kapanmadan cizelgeye satir
            // yazilmiyor ve emrin son saniyeleri kliple birlikte gitmezdi.
            sinyal.fire(org.example.dvr.DvrSignalEvent.kes(plan.channel.id));

            // KIRPMA BURADA YAPILMIYOR, isciye birakildi. Eskiden burada
            // clampToRecorded cagriliyor ve bos donunce emir BASARISIZ
            // isaretleniyordu; oysa cogu zaman kayit YOK degil HENUZ YAZILMAMIS
            // oluyordu. Isci kirpmayi kaydin cizelgeye dusmesini bekleyerek
            // yapiyor (ClipWorker.dvrBekle).
            ClipDto clip = clipService.create(
                plan.channel.id,
                new CreateClipRequest(plan.baslangic, plan.bitis),
                plan.user.keycloakId,
                ClipOrigin.MANUEL_KAYIT);
            plan.clip = org.example.clip.entity.Clip.findById(clip.id());
            plan.durum = ScheduledStatus.TAMAMLANDI;
            LOG.infof("Planlı kayıt tamamlandı: %s → klip %s", plan.id, clip.id());
        } catch (RuntimeException e) {
            // En sik sebep: kanal o aralikta yayinda degildi, dolayisiyla
            // araligin tamami kayitli degil. Emri BASARISIZ isaretleyip sebebi
            // saklamak, kullaniciya sessizce bos donmekten durust.
            plan.durum = ScheduledStatus.BASARISIZ;
            plan.hata = e.getMessage();
            LOG.warnf("Planlı kayıt kliplenemedi: %s (%s)", plan.id, e.getMessage());
        } finally {
            // Durum artık KAYITTA değil; kapı bu emri saymıyor ve kanalda
            // başka iş yoksa kaydı gerçekten kapatabiliyor.
            gate.release(plan.channel.id, plan.dvrBizden);
        }
    }

    private ScheduledRecording requireVisible(UUID id, String keycloakId, boolean isAdmin) {
        ScheduledRecording plan = ScheduledRecording.findById(id);
        if (plan == null) {
            throw AppException.notFound("Kayıt emri bulunamadı: " + id);
        }
        if (!isAdmin && !plan.user.keycloakId.equals(keycloakId)) {
            // 404: baskasinin emrinin VARLIGINI bile sizdirmiyoruz.
            throw AppException.notFound("Kayıt emri bulunamadı: " + id);
        }
        return plan;
    }

    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    private ScheduledRecordingDto toDto(ScheduledRecording plan) {
        return new ScheduledRecordingDto(
            plan.id,
            plan.channel.id,
            plan.channel.name,
            plan.baslangic,
            plan.bitis,
            Duration.between(plan.baslangic, plan.bitis).toSeconds(),
            plan.durum,
            plan.clip == null ? null : plan.clip.id,
            plan.hata,
            plan.dvrBizden,
            plan.user.username,
            plan.createdAt);
    }
}
