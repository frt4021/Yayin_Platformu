package org.example.video;

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
 * Video kuyruğunu tüketen arka plan işçileri.
 *
 * <p><b>Yalnızca işçi konteynerinde açık.</b> Backend imajında ffmpeg yok;
 * orada da çalışsaydı her iş "ffmpeg çalıştırılamadı" ile başarısız olur ve
 * kayıtlar denemeler tükenene kadar {@code HATA}'ya düşerdi. Bayrak bunun
 * için var — aynı jar iki konteynerde çalışıyor, sorumlulukları ayrı.
 *
 * <p>Kliplerdeki iki tetikleyici deseni burada da geçerli: Redis bildirimi
 * normal yol, veritabanı süpürücüsü güvenlik ağı. Her iki yol da işi
 * veritabanından talep ediyor; tekilliği garanti eden şey Redis değil,
 * o talep adımı.
 */
@ApplicationScoped
public class VideoConsumer {

    private static final Logger LOG = Logger.getLogger(VideoConsumer.class);

    /** BLMOVE bu süre kadar bekler, sonra döngü kapanma bayrağını kontrol eder. */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    @Inject
    VideoQueue queue;

    @Inject
    VideoWorker worker;

    @Inject
    VideoService videoService;

    @ConfigProperty(name = "videos.worker.enabled")
    boolean enabled;

    @ConfigProperty(name = "videos.concurrency")
    int concurrency;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService pool;

    void start(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.debug("Video işçisi bu süreçte kapalı (videos.worker.enabled=false).");
            return;
        }
        // Onceki calismadan asili kalmis girisler; isin kendisi veritabaninda
        // ISLENIYOR olarak duruyor ve supurucu tarafindan alinacak.
        queue.clearProcessing();

        pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r);
            t.setName("video-isci-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < concurrency; i++) {
            pool.submit(this::consumeLoop);
        }
        LOG.infof("Video tüketicisi başlatıldı: %d işçi", concurrency);
    }

    void stop(@Observes ShutdownEvent event) {
        running.set(false);
        if (pool != null) {
            pool.shutdown();
            try {
                // Devam eden bir is varsa yarida kalir ve ISLENIYOR'da kalir;
                // supurucu bir sonraki aciliste toparlar.
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
                // Talep edilemese bile ack: is ya baskasi tarafindan alinmis
                // ya da artik ISLENIYOR degil. Listede birakmak birikme yapardi.
            } catch (RuntimeException e) {
                LOG.errorf(e, "Video tüketici döngüsünde hata: %s", id);
            } finally {
                if (id != null) {
                    queue.ack(id);
                }
            }
        }
    }

    /**
     * Güvenlik ağı. İki iş birden yapıyor:
     * <ol>
     *   <li>Yarım kalmış <b>yüklemeleri</b> düzeltir — tarayıcı "tamamlandı"
     *       demeden kapanmışsa kayıt sonsuza kadar {@code YUKLENIYOR}'da kalırdı.</li>
     *   <li>Redis'in kaçırdığı <b>işleme</b> işlerini veritabanından alır.</li>
     * </ol>
     */
    @Scheduled(every = "{videos.sweep-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int reconciled = videoService.reconcileStaleUploads();
            if (reconciled > 0) {
                LOG.infof("Süpürücü %d yarım yüklemeyi ele aldı.", reconciled);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Yarım yüklemeler taranamadı.");
        }

        List<UUID> forgotten = worker.claimBatch();
        if (forgotten.isEmpty()) {
            return;
        }
        LOG.infof("Süpürücü %d bekleyen video buldu (Redis bildirimi ulaşmamış).", forgotten.size());
        for (UUID id : forgotten) {
            worker.process(id);
        }
    }

    /**
     * Yeni iş bildirimi. {@code AFTER_SUCCESS}: transaction commit edilmeden
     * Redis'e haber gitmez, aksi halde işçi henüz görünmeyen bir satırı arardı.
     *
     * <p>Bu dinleyici bayraktan <b>bağımsız</b>: bildirimi üreten backend,
     * tüketen worker. Backend'de kapatılsaydı iş Redis'e hiç düşmez ve
     * yalnızca süpürücü aralığında (dakikalar) alınırdı.
     */
    void onQueued(@Observes(during = TransactionPhase.AFTER_SUCCESS) VideoQueuedEvent event) {
        queue.publish(event.videoId());
    }
}
