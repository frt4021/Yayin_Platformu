package org.example.clip;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.entity.Clip;
import org.example.dvr.DvrService;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bir klip işini üreten birim. Ne zaman çalışacağına {@link ClipConsumer}
 * karar verir; burada yalnızca "işi talep et" ve "işi yap" var.
 *
 * <p><b>Doğruluk kaynağı veritabanı.</b> Redis bildirim taşır ama işin
 * durumu, deneme sayısı ve sonucu {@code clips} tablosundadır. İşi talep
 * etmek de ({@code BEKLIYOR → ISLENIYOR}, {@code SKIP LOCKED}) burada
 * yapılır: Redis en-az-bir-kez teslim ettiği için aynı iş iki kez
 * bildirilebilir, tekilliği garanti eden şey bu talep adımıdır.
 */
@ApplicationScoped
public class
ClipWorker {

    private static final Logger LOG = Logger.getLogger(ClipWorker.class);

    /** Geçici hatalarda bu sayıya kadar yeniden denenir. */
    private static final int MAX_ATTEMPTS = 3;

    @Inject
    DvrService dvrService;

    @Inject
    ClipStorage storage;

    @ConfigProperty(name = "clips.concurrency")
    int concurrency;

    /**
     * Tek bir işi talep eder: Redis'ten gelen bildirimin karşılığı.
     *
     * @return iş bu çağrı tarafından alındıysa {@code true}; başkası almışsa,
     *         iş artık {@code BEKLIYOR} değilse veya eşzamanlılık sınırı
     *         dolduysa {@code false}
     */
    @Transactional
    boolean claim(UUID clipId) {
        if (atCapacity()) {
            // Sınır dolu: işi BEKLIYOR bırakıyoruz, süpürücü veya boşalan bir
            // işçi alacak. Sınırsız olsaydı onlarca klip aynı anda MediaMTX'ten
            // çekilir, disk ve ağ doyar, CANLI YAYIN etkilenirdi.
            return false;
        }
        Clip clip = Clip.findById(clipId);
        if (clip == null || clip.status != ClipStatus.BEKLIYOR) {
            return false;
        }
        markRunning(clip);
        return true;
    }

    /**
     * Bekleyen işleri toplu talep eder — Redis bildiriminin ulaşmadığı
     * durumlar için güvenlik ağı.
     *
     * <p>Kısa transaction: asıl uzun iş ({@link #process}) transaction dışında
     * yapılır. Aksi halde saatlerce süren bir indirme boyunca veritabanı
     * bağlantısı ve satır kilidi tutulurdu.
     */
    @Transactional
    List<UUID> claimBatch() {
        long running = Clip.count("status", ClipStatus.ISLENIYOR);
        int slots = (int) (concurrency - running);
        if (slots <= 0) {
            return List.of();
        }

        List<Clip> batch = Clip.lockNextPending(slots);
        batch.forEach(this::markRunning);
        return batch.stream().map(clip -> clip.id).toList();
    }

    private boolean atCapacity() {
        return Clip.count("status", ClipStatus.ISLENIYOR) >= concurrency;
    }

    private void markRunning(Clip clip) {
        clip.status = ClipStatus.ISLENIYOR;
        clip.startedAt = Instant.now();
        clip.attempts++;
    }

    /**
     * Klibi MediaMTX'ten çekip depolamaya yazar.
     *
     * <p>Akış halinde aktarılır — dosya belleğe veya geçici diske alınmaz.
     * 2 saatlik bir klip 6 Mbps'te ~5.4 GB eder; tamponlamak sunucuyu düşürürdü.
     */
    void process(UUID clipId) {
        ClipJob job = loadJob(clipId);
        if (job == null) {
            return;
        }
        try {
            Duration duration = Duration.between(job.start(), job.end());
            try (Response response = dvrService.stream(job.channelId(), job.start(), duration, "mp4");
                 InputStream body = response.readEntity(InputStream.class)) {

                long size = storage.put(job.objectKey(), body, "video/mp4");
                markReady(clipId, job.objectKey(), size);
                LOG.infof("Klip hazır: %s (%,d bayt)", clipId, size);
            }
        } catch (Exception e) {
            handleFailure(clipId, e);
        }
    }

    // ------------------------------------------------------------------

    /** İşin transaction dışında kullanılacak alanları — lazy proxy taşımamak için. */
    private record ClipJob(UUID channelId, Instant start, Instant end, String objectKey) {
    }

    @Transactional
    ClipJob loadJob(UUID clipId) {
        Clip clip = Clip.findById(clipId);
        if (clip == null) {
            return null;
        }
        // <kullanici>/<kanal>/<id>.mp4 -- icerik zaten kullaniciya ozel,
        // klasor duzeni de onu yansitiyor.
        String key = org.example.storage.StoragePaths.channelFile(
            clip.requestedBy, clip.channel.mediamtxPath, clip.id + ".mp4");
        clip.objectKey = key;
        return new ClipJob(clip.channel.id, clip.startAt, clip.endAt, key);
    }

    @Transactional
    void markReady(UUID clipId, String objectKey, long size) {
        Clip clip = Clip.findById(clipId);
        if (clip == null) {
            return;
        }
        clip.status = ClipStatus.HAZIR;
        clip.objectKey = objectKey;
        clip.sizeBytes = size;
        clip.completedAt = Instant.now();
        clip.error = null;
    }

    @Transactional
    void handleFailure(UUID clipId, Exception cause) {
        Clip clip = Clip.findById(clipId);
        if (clip == null) {
            return;
        }
        boolean permanent = isPermanent(cause) || clip.attempts >= MAX_ATTEMPTS;

        if (permanent) {
            clip.status = ClipStatus.HATA;
            clip.error = cause.getMessage();
            clip.completedAt = Instant.now();
            LOG.errorf(cause, "Klip üretilemedi (kalıcı): %s", clipId);
        } else {
            // Geçici hata: kuyruğa geri koy, bir sonraki turda yeniden denensin.
            clip.status = ClipStatus.BEKLIYOR;
            clip.startedAt = null;
            LOG.warnf(cause, "Klip üretimi başarısız, yeniden denenecek (%d/%d): %s",
                clip.attempts, MAX_ATTEMPTS, clipId);
        }
    }

    /**
     * Yeniden denemenin anlamsız olduğu hatalar. "Kayıt bulunamadı" tekrar
     * denemekle düzelmez; ağ hatası düzelebilir.
     */
    private boolean isPermanent(Exception cause) {
        return cause instanceof AppException app
            && app.getErrorCode() == org.example.exception.ErrorCode.NOT_FOUND;
    }
}
