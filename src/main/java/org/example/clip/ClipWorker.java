package org.example.clip;

import io.quarkus.scheduler.Scheduled;
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
 * Klip kuyruğunu işleyen arka plan işçisi.
 *
 * <p>Kuyruk veritabanının kendisi. Ayrı bir mesaj kuyruğu yerine bunu
 * seçmenin sebebi: iş zaten {@code clips} tablosunda kalıcı olmak zorunda,
 * iki yere birden yazmak biri başarısız olduğunda kaybolan ya da iki kez
 * işlenen işler üretirdi. {@code SKIP LOCKED} ile birden fazla backend
 * kopyası aynı işi almadan paralel çalışabilir.
 *
 * <p>Yoklama aralığı kadar gecikme oluşur (varsayılan 5 sn). Klip üretimi
 * zaten dakikalar sürdüğü için bu gecikme önemsiz.
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
     * Sırayla en fazla {@code clips.concurrency} kadar iş alır.
     *
     * <p>Sınırsız olsaydı aynı anda onlarca klip MediaMTX'ten çekilir, disk
     * ve ağ doyar, <b>canlı yayın etkilenirdi</b>. Klip üretimi hiçbir zaman
     * canlı yayının önüne geçmemeli.
     */
    @Scheduled(every = "{clips.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollQueue() {
        List<UUID> taken = claimBatch();
        for (UUID id : taken) {
            process(id);
        }
    }

    /**
     * Bekleyen işleri ISLENIYOR'a çeker ve id'lerini döndürür.
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
        for (Clip clip : batch) {
            clip.status = ClipStatus.ISLENIYOR;
            clip.startedAt = Instant.now();
            clip.attempts++;
        }
        return batch.stream().map(clip -> clip.id).toList();
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
        String key = "clips/" + clip.channel.mediamtxPath + "/" + clip.id + ".mp4";
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
