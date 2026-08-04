package org.example.clip;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.list.ListCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Klip işlerinin Redis kuyruğu.
 *
 * <p><b>Redis burada doğruluk kaynağı değil, bildirim kanalıdır.</b> İşin
 * kalıcı hali {@code clips} tablosunda durur; Redis yalnızca "yeni iş var"
 * haberini taşır. Kuyruğu tamamen Redis'e taşımak iki yere birden yazmak
 * demek olurdu: biri başarılı diğeri başarısız olduğunda ya kaybolan ya iki
 * kez işlenen işler çıkardı. Bu ayrım sayesinde Redis tamamen çökse bile
 * hiçbir iş kaybolmaz — yalnızca gecikme, süpürücünün aralığına düşer.
 *
 * <p>İki liste kullanılıyor:
 * <ul>
 *   <li>{@code bekleyen} — yeni işler buraya itilir</li>
 *   <li>{@code isleniyor} — bir işçi iş aldığında atomik olarak buraya taşınır</li>
 * </ul>
 * Taşıma {@code BLMOVE} ile tek adımda yapılır. Basit bir {@code BRPOP}
 * kullanılsaydı, işçi işi aldıktan hemen sonra çökerse iş hiçbir listede
 * olmaz ve Redis tarafında iz bırakmadan kaybolurdu.
 */
@ApplicationScoped
public class ClipQueue {

    private static final Logger LOG = Logger.getLogger(ClipQueue.class);

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "clips.queue.key")
    String queueKey;

    private ListCommands<String, String> list;

    @PostConstruct
    void init() {
        list = redis.list(String.class);
    }

    private String pendingKey() {
        return queueKey + ":bekleyen";
    }

    private String processingKey() {
        return queueKey + ":isleniyor";
    }

    /**
     * Yeni işi kuyruğa iter.
     *
     * <p>Hata yutuluyor: Redis erişilemezse iş {@code clips} tablosunda
     * {@code BEKLIYOR} olarak durmaya devam eder ve süpürücü onu bulur.
     * Burada istisna fırlatmak, kullanıcının klip isteğini Redis yüzünden
     * reddetmek olurdu — oysa iş kaydedilmiş durumda.
     */
    public void publish(UUID clipId) {
        try {
            list.lpush(pendingKey(), clipId.toString());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Klip kuyruğa bildirilemedi, süpürücüye bırakılıyor: %s", clipId);
        }
    }

    /**
     * Bir iş bekler ve alır. Kuyruk boşsa {@code timeout} kadar bloklar.
     *
     * @return alınan işin id'si, süre dolduysa {@code null}
     */
    public UUID take(Duration timeout) {
        try {
            String id = list.blmove(pendingKey(), processingKey(),
                io.quarkus.redis.datasource.list.Position.RIGHT,
                io.quarkus.redis.datasource.list.Position.LEFT,
                timeout);
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // Kuyruğa bozuk bir değer girmiş; tekrar tekrar denememek için yut.
            LOG.warnf("Kuyrukta geçersiz klip id'si atlandı: %s", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOG.debugf("Kuyruktan okunamadı: %s", e.getMessage());
            return null;
        }
    }

    /** İş bitti; işleniyor listesinden düşür. */
    public void ack(UUID clipId) {
        try {
            list.lrem(processingKey(), 1, clipId.toString());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Klip işleniyor listesinden silinemedi: %s", clipId);
        }
    }

    /**
     * İşleniyor listesinde takılı kalanları temizler.
     *
     * <p>Bir işçi süreç ortasında ölürse iş burada asılı kalır. Süpürücü
     * veritabanı üzerinden zaten toparlıyor; bu yalnızca Redis tarafının
     * sınırsız büyümesini engelliyor.
     */
    public void clearProcessing() {
        try {
            List<String> stuck = list.lrange(processingKey(), 0, -1);
            if (!stuck.isEmpty()) {
                list.ltrim(processingKey(), 1, 0);
                LOG.infof("İşleniyor listesinde asılı kalan %d giriş temizlendi.", stuck.size());
            }
        } catch (RuntimeException e) {
            LOG.debugf("İşleniyor listesi temizlenemedi: %s", e.getMessage());
        }
    }
}
