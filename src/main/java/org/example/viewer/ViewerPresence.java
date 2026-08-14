package org.example.viewer;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kanal/radyo başına, SEKME bazlı izleyici/dinleyici sayacı.
 *
 * <p>MediaMTX'in kendi "reader" sayısı doğruluk kaynağı OLAMAZ: bir tarayıcı
 * sekmesi kalite değişiminde, ağ kesintisi sonrası otomatik yeniden
 * bağlanmada ya da hls.js'in paralel segment isteklerinde birden fazla
 * reader gibi görünebiliyor — tek sekme birden çok kez sayılıyordu
 * (bildirilen belirti: "tek oturum açık ama üç izleyici görünüyor").
 *
 * <p>Doğruluk kaynağı burada tarayıcının kendi bildirdiği "hâlâ buradayım"
 * sinyali (heartbeat) — kimlik IP/oturum değil <b>sekme</b>
 * ({@code tabId}): aynı kişi aynı kanalı birden fazla sekmede açabilmeli ve
 * her biri ayrı sayılmalı, ama TEK bir sekmenin periyodik heartbeat'i asla
 * birden fazla saymamalı.
 *
 * <p>Bellekte, kalıcı değil — {@code SubtitleBroadcaster.sessions} ile aynı
 * desen. Süreç yeniden başlarsa sayaç sıfırlanır, izleyicilerin bir sonraki
 * heartbeat'iyle (en geç {@code usePresence}'ın periyodu kadar sürede)
 * kendiliğinden toparlanır.
 */
@ApplicationScoped
public class ViewerPresence {

    /** Kanal/radyo id -> (sekme id -> son heartbeat anı). */
    private final Map<UUID, Map<String, Instant>> sekmeler = new ConcurrentHashMap<>();

    /**
     * Sekme kaç saniye sessiz kalırsa "gitti" sayılır.
     *
     * <p>Frontend 15 sn'de bir heartbeat atıyor ({@code usePresence}); 40 sn
     * iki-üç kaçırılan heartbeat'e (ağ dalgalanması, sekme arka planda
     * kısılmış) tolerans tanıyor ama gerçekten kapanmış bir sekmeyi de makul
     * sürede düşürüyor. Sekme kapanırken zaten açıkça {@link #ayril}
     * çağrılıyor (`keepalive` fetch ile) — bu TTL yalnızca o çağrının
     * ulaşamadığı durumlar (çökme, ağ kaybı) için bir güvenlik ağı.
     */
    private static final Duration TTL = Duration.ofSeconds(40);

    public void nabiz(UUID id, String tabId) {
        sekmeler.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(tabId, Instant.now());
    }

    public void ayril(UUID id, String tabId) {
        Map<String, Instant> tabs = sekmeler.get(id);
        if (tabs != null) {
            tabs.remove(tabId);
        }
    }

    public int sayisi(UUID id) {
        Map<String, Instant> tabs = sekmeler.get(id);
        return tabs == null ? 0 : tabs.size();
    }

    /** Kapanan sekmeler her zaman {@link #ayril} çağırmayabilir — süpürücü bu durumu yakalar. */
    @Scheduled(every = "10s")
    void supur() {
        Instant esik = Instant.now().minus(TTL);
        for (Map<String, Instant> tabs : sekmeler.values()) {
            tabs.values().removeIf(son -> son.isBefore(esik));
        }
    }
}
