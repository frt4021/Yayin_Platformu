package org.example.storage;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.ClipService;
import org.example.clip.ClipStatus;
import org.example.clip.RecordingService;
import org.example.clip.entity.Clip;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Otomatik temizlik politikası.
 *
 * <p><b>Süreler ortam değişkeninden geliyor</b> ve gün ya da saat olarak
 * yazılabiliyor:
 * <pre>
 *   STORAGE_CLIP_RETENTION=P30D     30 gün
 *   STORAGE_CLIP_RETENTION=720h     aynı süre, saat cinsinden
 *   STORAGE_CLIP_RETENTION=0        kapalı — hiç silinmez
 * </pre>
 *
 * <p><b>Varsayılan olarak kullanıcı verisi silinmiyor.</b> Klip ve ekran
 * görüntüsü kullanıcının kendi arşivi; zamana bağlı silmek "arşivim duruyor"
 * beklentisini bozar. Baskıyı kota kursun, saat değil. Kurum istiyorsa
 * ayarla açılır.
 *
 * <p>Silinmesi varsayılan olan tek şey <b>başarısız</b> klipler: dosyaları
 * zaten yok, yalnızca kullanıcı sebebini görsün diye bekletiliyorlar.
 */
@ApplicationScoped
public class RetentionSweeper {

    private static final Logger LOG = Logger.getLogger(RetentionSweeper.class);

    /** Tek turda silinecek üst sınır; büyük birikimde tek işlem kilitlenmesin. */
    private static final int BATCH = 200;

    @Inject
    ClipService clipService;

    @Inject
    RecordingService recordingService;

    @Inject
    org.example.screenshot.ScreenshotService screenshotService;

    @Inject
    MeterRegistry registry;

    /**
     * Tamamlanmış kliplerin saklanma süresi. {@code 0} = süresiz.
     * Manuel kayıtlar da bu kapsamda — ikisi de aynı tabloda ve aynı üründe.
     */
    @ConfigProperty(name = "storage.clip-retention")
    Duration clipRetention;

    /** Başarısız kliplerin saklanma süresi. Dosyaları yok, yalnızca kayıt. */
    @ConfigProperty(name = "storage.failed-clip-retention")
    Duration failedClipRetention;

    /** Ekran görüntülerinin saklanma süresi. {@code 0} = süresiz. */
    @ConfigProperty(name = "storage.screenshot-retention")
    Duration screenshotRetention;

    @ConfigProperty(name = "storage.sweep-interval")
    String sweepInterval;

    void logPolicy(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        LOG.infof("Temizlik politikası — klip: %s, başarısız klip: %s, ekran görüntüsü: %s",
            aciklama(clipRetention), aciklama(failedClipRetention), aciklama(screenshotRetention));
    }

    private static String aciklama(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            return "süresiz";
        }
        return d.toHours() % 24 == 0
            ? (d.toDays() + " gün")
            : (d.toHours() + " saat");
    }

    /**
     * Süpürücü. Üç iş yapıyor:
     * <ol>
     *   <li>Üst sınırı aşan manuel kayıtları otomatik durdurur</li>
     *   <li>Süresi dolmuş başarısız klipleri siler</li>
     *   <li>Süresi dolmuş klip ve ekran görüntülerini siler (açıksa)</li>
     * </ol>
     */
    @Scheduled(every = "{storage.sweep-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        try {
            int stopped = recordingService.autoStopOverdue();
            if (stopped > 0) {
                LOG.infof("Süresi dolan %d kayıt otomatik durduruldu.", stopped);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Süresi dolan kayıtlar durdurulamadı.");
        }

        temizle("başarısız klip", "basarisiz_klip", failedClipRetention, this::deleteExpiredFailedClips);
        temizle("klip", "klip", clipRetention, this::deleteExpiredClips);
        temizle("ekran görüntüsü", "ekran_goruntusu", screenshotRetention,
            cutoff -> screenshotService.deleteOlderThan(cutoff, BATCH));
    }

    /**
     * @param turEtiketi Prometheus'ta {@code depolama_temizlik_silinen_toplam}
     *                   sayacının {@code tur} etiketi — admin panelin "Depolama
     *                   ve Temizlik" dashboard'u bu sayaçtan besleniyor.
     */
    private void temizle(String ad, String turEtiketi, Duration sure,
                         java.util.function.ToIntFunction<Instant> is) {
        if (sure == null || sure.isZero() || sure.isNegative()) {
            return; // Politika kapali.
        }
        try {
            int silinen = is.applyAsInt(Instant.now().minus(sure));
            if (silinen > 0) {
                LOG.infof("Temizlik: %d %s silindi (%s'ten eski).", silinen, ad, aciklama(sure));
                registry.counter("depolama_temizlik_silinen_toplam", "tur", turEtiketi)
                    .increment(silinen);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Temizlik başarısız: %s", ad);
        }
    }

    /**
     * Silme <b>tek tek</b> yapılıyor, toplu {@code delete} ile değil: her
     * kaydın nesne depolamasındaki dosyası da silinmeli ve bu yalnızca servis
     * katmanından geçerek olur. Toplu silme, MinIO'da yetim nesneler bırakırdı.
     */
    @Transactional
    int deleteExpiredClips(Instant cutoff) {
        List<Clip> expired = Clip.find(
            "status = ?1 and completedAt < ?2", ClipStatus.HAZIR, cutoff)
            .page(0, BATCH).list();
        return sil(expired);
    }

    @Transactional
    int deleteExpiredFailedClips(Instant cutoff) {
        List<Clip> expired = Clip.find(
            "status = ?1 and completedAt < ?2", ClipStatus.HATA, cutoff)
            .page(0, BATCH).list();
        return sil(expired);
    }

    private int sil(List<Clip> clips) {
        int n = 0;
        for (Clip clip : clips) {
            // isAdmin=true: supurucu bir kullanici adina degil, politika
            // geregi siliyor.
            clipService.delete(clip.id, null, true);
            n++;
        }
        return n;
    }
}
