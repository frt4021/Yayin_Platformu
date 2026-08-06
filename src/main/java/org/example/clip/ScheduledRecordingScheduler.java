package org.example.clip;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.example.clip.entity.ScheduledRecording;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planlı kayıt emirlerini zamanı gelince işleyen tik.
 *
 * <p><b>Neden yoklama, neden zamanlayıcıya iş koymuyoruz:</b> her emir için bir
 * {@code ScheduledExecutorService} görevi açılsaydı, sunucu yeniden başladığında
 * hepsi kaybolurdu — kullanıcının günler öncesinden verdiği emir sessizce
 * düşerdi. Veritabanını yoklamak, açılışta geçmiş aralıkları da otomatik
 * toplamayı bedava veriyor.
 *
 * <p>Tik aralığı kayıt <b>doğruluğunu</b> etkilemiyor: kayıt MediaMTX'te
 * segment bazlı sürüyor, biz yalnızca aralığı işaretliyoruz. Geç kalmanın tek
 * bedeli klibin birkaç saniye geç açılması.
 */
@ApplicationScoped
public class ScheduledRecordingScheduler {

    private static final Logger LOG = Logger.getLogger(ScheduledRecordingScheduler.class);

    /**
     * Kayıt, aralığın başından bu kadar önce açılıyor.
     *
     * <p>MediaMTX kaydı segment sınırında başlatıyor; tam anında açılırsa ilk
     * saniyeler eksik kalabiliyor. Erken açmak birkaç saniyelik segment
     * demek — geç açmanın telafisi yok.
     */
    private static final Duration ON_PAY = Duration.ofSeconds(15);

    @Inject
    ScheduledRecordingService service;

    /**
     * Her yarım dakikada bir sırası gelenleri işler.
     *
     * <p>{@code SKIP} eşzamanlılık politikası: bir tik uzarsa (çok sayıda emir,
     * yavaş MediaMTX) ikincisi başlamıyor. Aksi halde aynı emir iki kez
     * başlatılmaya çalışılırdı — durum denetimleri bunu zaten yakalar ama
     * MediaMTX'e gereksiz yük binerdi.
     */
    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tik() {
        Instant now = Instant.now();

        // Sira onemli: once baslat, sonra bitir. Ayni tikta hem baslayip hem
        // biten cok kisa bir emir varsa ters sirada henuz KAYITTA olmadigi
        // icin bitirilemez ve bir sonraki tike sarkardi.
        islet("başlat", ScheduledRecording.dueToStart(now.plus(ON_PAY)),
            service::begin);
        islet("bitir", ScheduledRecording.dueToFinish(Instant.now()),
            service::complete);
    }

    private void islet(String ad, List<ScheduledRecording> plans,
                       java.util.function.Consumer<UUID> islem) {
        if (plans.isEmpty()) {
            return;
        }
        LOG.infof("Planlı kayıt %s: %d emir", ad, plans.size());
        for (ScheduledRecording plan : plans) {
            // Kimligi burada okuyoruz: islem ayri bir transaction acacak ve
            // bu liste elemanlari orada baglantisiz (detached) kalir.
            UUID id = plan.id;
            try {
                islem.accept(id);
            } catch (RuntimeException e) {
                // Tek bir emrin hatasi digerlerini durdurmamali.
                LOG.errorf(e, "Planlı kayıt işlenemedi (%s): %s", ad, id);
            }
        }
    }
}
