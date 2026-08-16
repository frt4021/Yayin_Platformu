package org.example.video.subtitle;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Video altyazı kuyruğunu tüketen arka plan işçileri — {@code VideoConsumer}
 * ile aynı desen (Redis bildirimi normal yol, veritabanı süpürücüsü
 * güvenlik ağı), ayrı bir bayrak ({@code videos.subtitle-enabled}) ve ayrı
 * bir küçük havuzla.
 *
 * <p>Yalnızca işçi konteynerinde açık — ffmpeg ve Triton erişimi gerekiyor,
 * ikisi de yalnızca {@code video-worker}'da var.
 */
@ApplicationScoped
public class VideoSubtitleConsumer {

    private static final Logger LOG = Logger.getLogger(VideoSubtitleConsumer.class);

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    @Inject
    VideoSubtitleQueue queue;

    @Inject
    VideoSubtitleWorker worker;

    @ConfigProperty(name = "videos.subtitle-enabled")
    boolean enabled;

    @ConfigProperty(name = "videos.subtitle-concurrency")
    int concurrency;

    @ConfigProperty(name = "videos.sweep-interval")
    String sweepInterval;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService pool;

    void start(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.debug("Video altyazı işçisi bu süreçte kapalı (videos.subtitle-enabled=false).");
            return;
        }
        queue.clearProcessing();

        pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r);
            t.setName("video-altyazi-isci-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < concurrency; i++) {
            pool.submit(this::consumeLoop);
        }
        LOG.infof("Video altyazı tüketicisi başlatıldı: %d işçi", concurrency);
    }

    void stop(@Observes ShutdownEvent event) {
        running.set(false);
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(BLOCK_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            UUID id = null;
            try {
                id = queue.take(BLOCK_TIMEOUT);
                if (id == null) {
                    continue;
                }
                if (worker.claim(id)) {
                    worker.process(id);
                }
            } catch (RuntimeException e) {
                LOG.errorf(e, "Video altyazı tüketici döngüsünde hata: %s", id);
            } finally {
                if (id != null) {
                    queue.ack(id);
                }
            }
        }
    }

    /** Güvenlik ağı — Redis'in kaçırdığı işleri veritabanından alır. */
    @Scheduled(every = "{videos.sweep-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        if (!enabled) {
            return;
        }
        List<UUID> forgotten = worker.claimBatch(concurrency);
        if (forgotten.isEmpty()) {
            return;
        }
        LOG.infof("Süpürücü %d bekleyen video altyazı işi buldu (Redis bildirimi ulaşmamış).",
            forgotten.size());
        for (UUID id : forgotten) {
            worker.process(id);
        }
    }

    /**
     * Yeni iş bildirimi. {@code AFTER_SUCCESS}: transaction commit edilmeden
     * Redis'e haber gitmez.
     */
    void onQueued(@Observes(during = TransactionPhase.AFTER_SUCCESS) VideoSubtitleQueuedEvent event) {
        queue.publish(event.videoId());
    }
}
