package org.example.video.subtitle;

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
 * Video altyazı işlerinin Redis kuyruğu — {@code VideoQueue} ile birebir
 * aynı desen (Redis yalnızca bildirim, doğruluk kaynağı {@code videos}
 * tablosundaki {@code subtitle_status}), ayrı bir Redis anahtarında.
 *
 * <p>Ayrı kuyruk şart: video işleme ve altyazı üretimi farklı hızda ve
 * farklı kaynak profilinde (biri I/O-ağırlıklı ve saniyeler, diğeri
 * Triton/GPU-bağlı ve dakikalar) — tek listede toplansalardı biri diğerini
 * bloklardı.
 */
@ApplicationScoped
public class VideoSubtitleQueue {

    private static final Logger LOG = Logger.getLogger(VideoSubtitleQueue.class);

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "videos.subtitle-queue.key")
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

    public void publish(UUID videoId) {
        try {
            list.lpush(pendingKey(), videoId.toString());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Video altyazı işi kuyruğa bildirilemedi, süpürücüye bırakılıyor: %s", videoId);
        }
    }

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
            LOG.warnf(e, "Video altyazı işleniyor listesinden silinemedi: %s", videoId);
        }
    }

    public void clearProcessing() {
        try {
            List<String> stuck = list.lrange(processingKey(), 0, -1);
            if (!stuck.isEmpty()) {
                list.ltrim(processingKey(), 1, 0);
                LOG.infof("Video altyazı işleniyor listesinde asılı kalan %d giriş temizlendi.", stuck.size());
            }
        } catch (RuntimeException e) {
            LOG.debugf("İşleniyor listesi temizlenemedi: %s", e.getMessage());
        }
    }
}
