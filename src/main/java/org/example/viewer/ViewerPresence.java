package org.example.viewer;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.example.etkinlik.EtkinlikService;
import org.example.etkinlik.EtkinlikTuru;

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

    @Inject
    EtkinlikService etkinlikService;

    /**
     * Bir sekmenin oturumu. {@code baslangic} yalnızca ilk {@link #nabiz}
     * çağrısında set edilir ve sonraki heartbeat'lerde korunur — süre
     * hesaplamak (etkinlik kaydı için) için oturumun ne zaman başladığını
     * bilmek gerekiyor, eski {@code Instant}-only yapı bunu tutmuyordu.
     */
    private record Sekme(Instant baslangic, Instant sonNabiz, String kullaniciId, String hedefTuru) {
    }

    /** Kanal/radyo id -> (sekme id -> oturum). */
    private final Map<UUID, Map<String, Sekme>> sekmeler = new ConcurrentHashMap<>();

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

    /**
     * @return {@code true} ise bu (id, tabId) için YENİ bir oturum başladı —
     * çağıran bunu bir "izleme/dinleme başladı" etkinliğine çevirebilir.
     */
    public boolean nabiz(UUID id, String tabId, String kullaniciId, String hedefTuru) {
        Map<String, Sekme> tabs = sekmeler.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        Sekme onceki = tabs.get(tabId);
        Instant baslangic = onceki == null ? Instant.now() : onceki.baslangic();
        tabs.put(tabId, new Sekme(baslangic, Instant.now(), kullaniciId, hedefTuru));
        return onceki == null;
    }

    /**
     * @return kapanan oturumun başlangıç anı, sekme zaten düşmüşse
     * {@code null} (idempotent — çağıran çift etkinlik kaydı atmaz).
     */
    public Instant ayril(UUID id, String tabId) {
        Map<String, Sekme> tabs = sekmeler.get(id);
        Sekme kapanan = tabs == null ? null : tabs.remove(tabId);
        return kapanan == null ? null : kapanan.baslangic();
    }

    public int sayisi(UUID id) {
        Map<String, Sekme> tabs = sekmeler.get(id);
        return tabs == null ? 0 : tabs.size();
    }

    /** Tüm kanallar/radyolar genelinde toplam eşzamanlı sekme sayısı — admin analitik özeti için. */
    public long toplamSayisi(String hedefTuru) {
        return sekmeler.values().stream()
            .flatMap(tabs -> tabs.values().stream())
            .filter(sekme -> hedefTuru.equals(sekme.hedefTuru()))
            .count();
    }

    /**
     * Kapanan sekmeler her zaman {@link #ayril} çağırmayabilir — süpürücü bu
     * durumu yakalar. Bu, süresi dolan oturumlar için TEK atıf kaynağıdır:
     * arka plan tetiklemesi olduğu için istek/JWT yok, kullanıcı/hedef bilgisi
     * yalnızca süpürülen {@link Sekme}'nin kendisinde var.
     */
    @Scheduled(every = "10s")
    void supur() {
        Instant esik = Instant.now().minus(TTL);
        for (Map.Entry<UUID, Map<String, Sekme>> girdi : sekmeler.entrySet()) {
            UUID hedefId = girdi.getKey();
            girdi.getValue().values().removeIf(sekme -> {
                if (sekme.sonNabiz().isBefore(esik)) {
                    EtkinlikTuru tur = "radyo".equals(sekme.hedefTuru())
                        ? EtkinlikTuru.DINLEME_BITTI : EtkinlikTuru.IZLEME_BITTI;
                    long sureMs = Duration.between(sekme.baslangic(), sekme.sonNabiz()).toMillis();
                    etkinlikService.kaydet(tur, sekme.kullaniciId(), sekme.hedefTuru(), hedefId,
                        Map.of("sureMs", sureMs, "sebep", "sure_asimi"));
                    return true;
                }
                return false;
            });
        }
    }
}
