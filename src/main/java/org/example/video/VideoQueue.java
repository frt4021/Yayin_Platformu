package org.example.video;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.list.ListCommands;
import io.quarkus.redis.datasource.list.Position;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Video işlerinin Redis kuyruğu.
 *
 * <p>Kliplerdeki {@code ClipQueue} ile aynı ilke: <b>Redis doğruluk kaynağı
 * değil, bildirim kanalıdır.</b> İşin kalıcı hali {@code videos} tablosunda
 * durur. Redis tamamen çökse bile hiçbir iş kaybolmaz; yalnızca gecikme
 * süpürücünün aralığına düşer.
 *
 * <p>Ayrı bir kuyruk anahtarı kullanılıyor: klip ve video işleri farklı
 * konteynerlerde tüketiliyor ve tek listede toplansalardı klip işçisi video
 * işini alıp "bu benim değil" diye geri koymak zorunda kalırdı.
 */
@ApplicationScoped
public class VideoQueue {

    private static final Logger LOG = Logger.getLogger(VideoQueue.class);

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "videos.queue.key")
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
     * Hata yutuluyor: Redis erişilemezse kayıt {@code ISLENIYOR} olarak
     * durmaya devam eder ve süpürücü onu bulur. İstisna fırlatmak,
     * kullanıcının yüklemesini Redis yüzünden reddetmek olurdu.
     */
    public void publish(UUID videoId) {
        try {
            list.lpush(pendingKey(), videoId.toString());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Video kuyruğa bildirilemedi, süpürücüye bırakılıyor: %s", videoId);
        }
    }

    /**
     * Bir iş bekler ve alır. {@code BLMOVE} ile tek adımda taşınıyor: düz bir
     * {@code BRPOP} kullanılsaydı, işçi işi aldıktan hemen sonra çökerse iş
     * hiçbir listede olmaz ve Redis tarafında iz bırakmadan kaybolurdu.
     *
     * @return alınan işin id'si, süre dolduysa {@code null}
     */
    public UUID take(Duration timeout) {
        try {
            String id = list.blmove(pendingKey(), processingKey(),
                Position.RIGHT, Position.LEFT, timeout);
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Kuyrukta geçersiz video id'si atlandı: %s", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOG.debugf("Kuyruktan okunamadı: %s", e.getMessage());
            return null;
        }
    }

    public void ack(UUID videoId) {
        try {
            list.lrem(processingKey(), 1, videoId.toString());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Video işleniyor listesinden silinemedi: %s", videoId);
        }
    }

    /**
     * İşleniyor listesinde takılı kalanları temizler. Süpürücü veritabanı
     * üzerinden zaten toparlıyor; bu yalnızca Redis tarafının sınırsız
     * büyümesini engelliyor.
     */
    public void clearProcessing() {
        try {
            List<String> stuck = list.lrange(processingKey(), 0, -1);
            if (!stuck.isEmpty()) {
                list.ltrim(processingKey(), 1, 0);
                LOG.infof("Video işleniyor listesinde asılı kalan %d giriş temizlendi.", stuck.size());
            }
        } catch (RuntimeException e) {
            LOG.debugf("İşleniyor listesi temizlenemedi: %s", e.getMessage());
        }
    }
}
