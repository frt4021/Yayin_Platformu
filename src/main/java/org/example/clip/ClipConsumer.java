package org.example.clip;

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
 * Klip kuyruğunu tüketen arka plan işçileri.
 *
 * <p>İki tetikleyici var ve ikisi de gerekli:
 *
 * <ol>
 *   <li><b>Redis bildirimi</b> — normal yol. {@code BLMOVE} ile bloklanarak
 *       beklenir, iş gelir gelmez alınır. Gecikme milisaniye mertebesinde.</li>
 *   <li><b>Veritabanı süpürücüsü</b> — güvenlik ağı. Redis çökmüşse, bildirim
 *       kaybolmuşsa ya da iş Redis ayaktayken oluşturulmadıysa {@code BEKLIYOR}
 *       satırları burada bulunur. Bu olmadan Redis'in her arızası kalıcı iş
 *       kaybına dönüşürdü.</li>
 * </ol>
 *
 * <p>Her iki yol da işi <b>veritabanından</b> talep ediyor
 * ({@code BEKLIYOR → ISLENIYOR}, {@code SKIP LOCKED} ile). Redis en-az-bir-kez
 * teslim ettiği için aynı iş iki kez bildirilebilir; tekilliği garanti eden
 * şey Redis değil, bu veritabanı talebi.
 */
@ApplicationScoped
public class ClipConsumer {

    private static final Logger LOG = Logger.getLogger(ClipConsumer.class);

    /** BLMOVE bu süre kadar bekler, sonra döngü kapanma bayrağını kontrol eder. */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    @Inject
    ClipQueue queue;

    @Inject
    ClipWorker worker;

    @ConfigProperty(name = "clips.concurrency")
    int concurrency;

    /**
     * Klip işçisi bu süreçte çalışsın mı.
     *
     * <p>Aynı jar artık iki konteynerde çalışıyor (backend ve video işçisi).
     * Bayrak olmasaydı klip tüketicisi ve süpürücüsü <b>ikisinde birden</b>
     * ayağa kalkardı: {@code SKIP LOCKED} veri bozulmasını engeller ama iki
     * kat boşa iş ve iki kat log üretirdi.
     */
    @ConfigProperty(name = "clips.worker.enabled")
    boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService pool;

    void start(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.debug("Klip işçisi bu süreçte kapalı (clips.worker.enabled=false).");
            return;
        }
        // Önceki çalışmadan asılı kalmış girişler; işin kendisi veritabanında
        // BEKLIYOR olarak duruyor ve süpürücü tarafından alınacak.
        queue.clearProcessing();

        pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r);
            t.setName("klip-isci-" + t.threadId());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < concurrency; i++) {
            pool.submit(this::consumeLoop);
        }
        LOG.infof("Klip tüketicisi başlatıldı: %d işçi", concurrency);
    }

    void stop(@Observes ShutdownEvent event) {
        running.set(false);
        if (pool != null) {
            pool.shutdown();
            try {
                // BLMOVE zaman aşımı kadar bekleyip kapanıyoruz; devam eden bir
                // klip varsa yarıda kalır ve ISLENIYOR'da kalır — süpürücü
                // bir sonraki açılışta toparlar.
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
                // Talep edilemese bile ack: iş ya başkası tarafından alınmış
                // ya da artık BEKLIYOR değil. Listede bırakmak birikme yapardı.
            } catch (RuntimeException e) {
                LOG.errorf(e, "Klip tüketici döngüsünde hata: %s", id);
            } finally {
                if (id != null) {
                    queue.ack(id);
                }
            }
        }
    }

    /**
     * Güvenlik ağı: Redis'in kaçırdığı işleri veritabanından alır.
     *
     * <p>Aralık uzun tutuldu — normal yolda işler Redis'ten anında geliyor,
     * bu tarama yalnızca arıza durumları için. Kısa tutmak veritabanını
     * boşuna yorardı.
     */
    @Scheduled(every = "{clips.sweep-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        if (!enabled) {
            return;
        }
        List<UUID> forgotten = worker.claimBatch();
        if (forgotten.isEmpty()) {
            return;
        }
        LOG.infof("Süpürücü %d bekleyen klip buldu (Redis bildirimi ulaşmamış).", forgotten.size());
        for (UUID id : forgotten) {
            worker.process(id);
        }
    }

    /**
     * Yeni iş bildirimi. {@code AFTER_SUCCESS}: transaction commit edilmeden
     * Redis'e haber gitmez, aksi halde işçi henüz görünmeyen bir satırı arardı.
     */
    void onQueued(@Observes(during = TransactionPhase.AFTER_SUCCESS) ClipQueuedEvent event) {
        queue.publish(event.clipId());
    }
}
