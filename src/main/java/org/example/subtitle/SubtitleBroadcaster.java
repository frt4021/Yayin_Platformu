package org.example.subtitle;

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
 * <h2>Abonelik neden TEK ve neden ayrı istemcide</h2>
 * Önce kanal başına abone olunuyor, son izleyici gidince bırakılıyordu ve
 * hepsi klip kuyruğuyla aynı Redis istemcisini paylaşıyordu. <b>Altyazı
 * tarayıcıya hiç ulaşmıyordu</b> — üretim, çeviri ve Redis'in üçü de
 * sapasağlam olduğu, arayüzde de hiçbir belirti çıkmadığı için sorun uzun
 * süre görünmedi.
 *
 * <p>Ölçülen: aboneliğin açılması <b>41 saniye</b> bloke kaldı ve dönene
 * kadar tek bir mesaj bile dağıtılmadı. Log'daki tek iz:
 *
 * <pre>
 * 11:36:59.227  WARN io.vertx...RedisStandaloneConnection
 *               No handler waiting for message: [psubscribe, altyazi:*, 1]
 * 11:37:40.211  INFO Altyazı aboneliği açıldı          ← 41 sn sonra
 * </pre>
 *
 * <p>İki değişiklik birlikte gerekiyordu:
 *
 * <ol>
 *   <li><b>Ayrı Redis istemcisi</b> ({@code quarkus.redis.pubsub.*}).
 *       Abonelik bir bağlantıyı sürekli tutuyor; klip kuyruğunun
 *       {@code BLMOVE}'u da her işçiyi 2 saniyeye kadar tutuyor. İkisini
 *       aynı havuza koymak baştan yanlıştı.</li>
 *   <li><b>Desenle tek abonelik</b> ({@code altyazi:*}), ilk izleyicide
 *       açılıp süreç kapanana kadar duruyor. Her izleyici gidişinde
 *       bırakılıp yeniden açılması, aynı hatayı tekrar tekrar tetikleyen
 *       şeydi.</li>
 * </ol>
 *
 * <p><b>Bedeli:</b> izlenmeyen kanalların altyazısı da bu sürece geliyor.
 * Ölçek küçük: bölüt başına ~500 bayt, kanal başına birkaç saniyede bir —
 * 300 kanalda bile ~50 KB/sn. Kaybedilen doğruluğun yanında önemsiz.
 */
@ApplicationScoped
public class SubtitleBroadcaster {

    private static final Logger LOG = Logger.getLogger(SubtitleBroadcaster.class);

    /**
     * Pub/sub'a AYRILMIS istemci ({@code quarkus.redis.pubsub.*}).
     *
     * <p>Varsayilan istemci klip kuyrugunun {@code BLMOVE}'uyla paylasiliyor
     * ve abonelik oradan baglanti alamiyordu; olculdu: abonelik acilmasi 41
     * saniye bloke kaldi. Abonelik bir baglantiyi surekli tutuyor, komut
     * trafigiyle ayni havuzda olmamali.
     */
    @Inject
    @io.quarkus.redis.client.RedisClientName("pubsub")
    RedisDataSource redis;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    /** Bu süreçte üretim yapılıyorsa yayınlama açık olmalı. */
    @ConfigProperty(name = "vad.enabled")
    boolean producer;

    private PubSubCommands<String> pubsub;

    /** Kanal başına bağlı oturumlar. */
    private final Map<UUID, Set<Session>> sessions = new ConcurrentHashMap<>();

    /**
     * Süreçteki <b>tek</b> abonelik. Bir kez açılıyor, kapanışta bırakılıyor.
     *
     * <p>Kanal başına açıp kapatmanın neden bozuk olduğu sınıf açıklamasında.
     */
    private final java.util.concurrent.atomic.AtomicReference<PubSubCommands.RedisSubscriber>
        abonelik = new java.util.concurrent.atomic.AtomicReference<>();

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
        abonelikAc();
        LOG.debugf("Altyazı izleyicisi bağlandı: %s (toplam %d)",
            channelId, sessions.get(channelId).size());
    }

    /**
     * Bir izleyici ayrıldı.
     *
     * <p><b>Abonelik BIRAKILMIYOR.</b> Bırakıp yeniden açmak istemcide
     * handler'ı kaybettiriyor ve altyazı bir daha hiç akmıyor; bkz. sınıf
     * açıklaması. Boşta duran bir abonelik yalnızca birkaç KB/sn trafik
     * demek — bozuk altyazının yanında bedeli yok.
     */
    public void leave(UUID channelId, Session session) {
        Set<Session> set = sessions.get(channelId);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            sessions.remove(channelId);
        }
    }

    /**
     * Tek aboneliği açar — ilk izleyicide, bir kez.
     *
     * <p>Açılış {@code @PostConstruct}'ta değil ilk izleyicide: aynı jar
     * video işçisinde de çalışıyor ve orada hiç WebSocket oturumu olmuyor,
     * abone olmasının anlamı yok.
     */
    private void abonelikAc() {
        if (abonelik.get() != null) {
            return;
        }
        synchronized (this) {
            if (abonelik.get() != null) {
                return;
            }
            abonelik.set(pubsub.subscribeToPattern(SubtitleEvent.KANAL_DESENI, this::dagit));
            LOG.infof("Altyazı aboneliği açıldı: %s (tüm kanallar, tek abonelik)",
                SubtitleEvent.KANAL_DESENI);
        }
    }

    /**
     * Gelen mesajı ait olduğu kanalın izleyicilerine yönlendirir.
     *
     * <p>Kanal adı desen aboneliğinde taşınmadığı için kimlik <b>gövdeden</b>
     * okunuyor. Zaten orada: {@link SubtitleEvent#channelId()}.
     */
    private void dagit(String mesaj) {
        UUID channelId = kanalIdOku(mesaj);
        if (channelId == null) {
            return;
        }
        // Izlenmeyen kanallarin mesaji da geliyor; oturumu olmayan kanal
        // sessizce dusuruluyor.
        yayinla(channelId, mesaj);
    }

    private UUID kanalIdOku(String mesaj) {
        try {
            var alan = json.readTree(mesaj).get("channelId");
            return alan == null ? null : UUID.fromString(alan.asText());
        } catch (Exception e) {
            LOG.debugf("Altyazı mesajının kanalı okunamadı: %s", e.getMessage());
            return null;
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
        PubSubCommands.RedisSubscriber sub = abonelik.getAndSet(null);
        if (sub != null) {
            try {
                sub.unsubscribe();
            } catch (RuntimeException e) {
                LOG.debugf("Abonelik kapatılamadı: %s", e.getMessage());
            }
        }
        sessions.clear();
    }

    /** Bağlı izleyici sayısı — sağlık ve teşhis için. */
    public int listenerCount() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }
}
