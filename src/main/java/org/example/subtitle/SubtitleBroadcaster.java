package org.example.subtitle;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canlı altyazıyı bağlı tarayıcılara dağıtan katman.
 *
 * <h2>Neden Redis araya giriyor</h2>
 * Altyazıyı üreten süreç {@code video-worker}, tarayıcıya gönderen süreç
 * {@code backend} — <b>ayrı konteynerler</b>. Doğrudan çağrı yapılamaz.
 *
 * <h2>Neden kanal başına abonelik</h2>
 * Tek bir kanala abone olup mesajları süzmek de mümkündü ama 20 kanal
 * çalışırken tek izleyicinin açtığı bir karo yüzünden 20 kanalın tüm
 * altyazısı bu sürece akardı. Kanal başına abonelik, yalnızca izlenen
 * kanalların trafiğini getiriyor.
 *
 * <p>Abonelik <b>ilk izleyiciyle açılıyor, son izleyici gidince
 * kapanıyor</b>: kimse izlemiyorken Redis'ten veri çekmenin anlamı yok.
 */
@ApplicationScoped
public class SubtitleBroadcaster {

    private static final Logger LOG = Logger.getLogger(SubtitleBroadcaster.class);

    @Inject
    RedisDataSource redis;

    @Inject
    ReactiveRedisDataSource reactiveRedis;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    /** Bu süreçte üretim yapılıyorsa yayınlama açık olmalı. */
    @ConfigProperty(name = "vad.enabled")
    boolean producer;

    private PubSubCommands<String> pubsub;

    /** Kanal başına bağlı oturumlar. */
    private final Map<UUID, Set<Session>> sessions = new ConcurrentHashMap<>();

    /** Kanal başına açık abonelik — kapatabilmek için tutuluyor. */
    private final Map<UUID, PubSubCommands.RedisSubscriber> subscriptions =
        new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        pubsub = redis.pubsub(String.class);
    }

    // ------------------------------------------------------------------
    // Üretici taraf (video-worker)
    // ------------------------------------------------------------------

    /**
     * Altyazıyı yayınlar.
     *
     * <p>Hata <b>yutuluyor</b>: altyazı veritabanına zaten yazıldı ve
     * geçmişten okunabiliyor. Redis erişilemediğinde canlı akış kesilir ama
     * veri kaybolmaz — bunun için isteği düşürmek yanlış olurdu.
     */
    public void publish(SubtitleEvent event) {
        try {
            pubsub.publish(SubtitleEvent.kanalAdi(event.channelId()),
                json.writeValueAsString(event));
        } catch (Exception e) {
            LOG.debugf("Altyazı yayınlanamadı (canlı akış etkilenir, veri değil): %s",
                e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Tüketici taraf (backend)
    // ------------------------------------------------------------------

    /** Bir izleyici bağlandı. */
    public void join(UUID channelId, Session session) {
        sessions.computeIfAbsent(channelId, id -> ConcurrentHashMap.newKeySet()).add(session);
        subscribe(channelId);
        LOG.debugf("Altyazı izleyicisi bağlandı: %s (toplam %d)",
            channelId, sessions.get(channelId).size());
    }

    /** Bir izleyici ayrıldı. */
    public void leave(UUID channelId, Session session) {
        Set<Session> set = sessions.get(channelId);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            sessions.remove(channelId);
            unsubscribe(channelId);
        }
    }

    private void subscribe(UUID channelId) {
        subscriptions.computeIfAbsent(channelId, id -> {
            LOG.infof("Altyazı aboneliği açıldı: %s", id);
            return pubsub.subscribe(SubtitleEvent.kanalAdi(id), mesaj -> yayinla(id, mesaj));
        });
    }

    private void unsubscribe(UUID channelId) {
        PubSubCommands.RedisSubscriber sub = subscriptions.remove(channelId);
        if (sub != null) {
            try {
                sub.unsubscribe();
                LOG.infof("Altyazı aboneliği kapandı: %s", channelId);
            } catch (RuntimeException e) {
                LOG.debugf("Abonelik kapatılamadı: %s", e.getMessage());
            }
        }
    }

    /**
     * Gelen mesajı o kanalın izleyicilerine gönderir.
     *
     * <p>Gönderim <b>asenkron</b>: yavaş bir istemci diğerlerini
     * bekletmemeli. Kapanmış oturumlar bu sırada temizleniyor — {@code onClose}
     * her zaman tetiklenmiyor (ağ koptuğunda tarayıcı haber vermez).
     */
    private void yayinla(UUID channelId, String mesaj) {
        Set<Session> set = sessions.get(channelId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (Session session : set) {
            if (!session.isOpen()) {
                set.remove(session);
                continue;
            }
            session.getAsyncRemote().sendText(mesaj, sonuc -> {
                if (sonuc.getException() != null) {
                    set.remove(session);
                }
            });
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        subscriptions.keySet().forEach(this::unsubscribe);
        sessions.clear();
    }

    /** Bağlı izleyici sayısı — sağlık ve teşhis için. */
    public int listenerCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }
}
