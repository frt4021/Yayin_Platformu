package org.example.dvr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.function.Consumer;

/**
 * {@link DvrSignalEvent}'i konteynerler arasında taşıyan kanal.
 *
 * <p>Taşıyıcı Redis pub/sub — altyazıda ({@code SubtitleBroadcaster}) ve klip
 * kuyruğunda ({@code ClipQueue}) zaten kullanılan yol. Yeni altyapı
 * gerekmiyor.
 *
 * <h2>Kaybolursa ne olur</h2>
 * <b>Hiçbir şey bozulmaz, yalnızca yavaşlar.</b> Sinyal bir hızlandırma;
 * doğruluk kaynağı hâlâ veritabanı ve kaydedici {@code dvr.sync-interval}
 * yoklamasını sürdürüyor. Bu yüzden yayınlama hatası yutuluyor: Redis'e
 * ulaşılamadı diye kaydı reddetmek, çalışan bir yolu kapatmak olurdu.
 */
@ApplicationScoped
public class DvrSignal {

    private static final Logger LOG = Logger.getLogger(DvrSignal.class);

    /** Tek kanal: emir sayısı saniyede birkaç, kanal başına ayırmaya değmez. */
    static final String KANAL = "dvr:sinyal";

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper json;

    private PubSubCommands<String> pubsub;

    @PostConstruct
    void init() {
        pubsub = redis.pubsub(String.class);
    }

    /**
     * Emri yayınlar.
     *
     * <p>{@code AFTER_SUCCESS}: emir, sebebini yazan transaction commit
     * edilmeden gitmiyor. Bkz. {@link DvrSignalEvent}.
     *
     * <p>Transaction yoksa CDI dinleyiciyi <b>anında</b> çağırıyor — kaydı
     * durdurma yolu ({@code RecordingService.stop}) transaction dışında
     * çalışıyor ve orada bekleme olmaması isteniyor.
     */
    void onEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) DvrSignalEvent event) {
        try {
            pubsub.publish(KANAL, json.writeValueAsString(event));
            LOG.debugf("DVR sinyali yayınlandı: %s %s", event.tur(), event.channelId());
        } catch (Exception e) {
            LOG.warnf("DVR sinyali yayınlanamadı (%s %s): %s — kaydedici yoklamayla devam edecek",
                event.tur(), event.channelId(), e.getMessage());
        }
    }

    /**
     * Emirleri dinlemeye başlar.
     *
     * <p>Yalnızca kaydedicinin çalıştığı süreçte çağrılıyor; abone olan taraf
     * {@link DvrRecorder}.
     *
     * @return abonelik — kapanışta {@code unsubscribe()} edilmeli
     */
    PubSubCommands.RedisSubscriber dinle(Consumer<DvrSignalEvent> alici) {
        return pubsub.subscribe(KANAL, mesaj -> {
            try {
                alici.accept(json.readValue(mesaj, DvrSignalEvent.class));
            } catch (Exception e) {
                LOG.warnf("DVR sinyali okunamadı: %s (%s)", mesaj, e.getMessage());
            }
        });
    }
}
