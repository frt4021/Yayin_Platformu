package org.example.viewer;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bir yayının (kanal ya da radyo) o an kaç FARKLI sekme tarafından
 * izlendiğini tutar.
 *
 * <h2>Neden MediaMTX'in reader sayısı yeterli değildi</h2>
 * MediaMTX her BAĞLANTIYI ayrı sayıyor. Aynı tarayıcı sekmesi bir hata
 * kurtarmada ya da kanal değişiminde yeniden bağlandığında
 * ({@code HlsPlayer.tsx}, {@code PersistentRadio.tsx} — eski oynatıcı
 * yok edilip yenisi kuruluyor) MediaMTX'te YENİ bir oturum açılıyor;
 * "izleyici sayısı" bu yüzden tek bir kişinin birkaç kez saymasıyla
 * şişiyordu.
 *
 * <p>Burada anahtar tarayıcının kendisinin ürettiği, sekme ömrü boyunca
 * SABİT bir kimlik ({@code tabId}, bkz. frontend {@code sekmeKimligi()}).
 * Aynı sekme kaç kez yeniden bağlanırsa bağlansın haritada TEK kayıt
 * kalıyor — {@link Map#put} aynı anahtarın üstüne yazıyor.
 *
 * <h2>Anlık düşüş + çökme toleransı birlikte</h2>
 * İstemci sekmeyi kapatırken açıkça {@link #ayril} çağırıyor — bu ANINDA
 * düşüyor. Sekme çökerse/ağ koparsa bu çağrı hiç gelmez; bu durumda
 * periyodik {@link #nabiz} çağrıları da kesilir ve {@link #supur} onu
 * TTL sonunda temizler. İki mekanizma birlikte: temiz kapanışta anlık,
 * çökmede birkaç saniye içinde.
 *
 * <p>Kalıcı DEĞİL, bilerek — {@code SubtitleBroadcaster.sessions} ile aynı
 * desen: bellekte, süreç boyunca. Sunucu yeniden başlarsa sıfırlanır,
 * sorun değil — istemciler birkaç saniye içinde tekrar nabız atıp doldurur.
 */
@ApplicationScoped
public class ViewerPresence {

    /**
     * Son nabızdan bu kadar süre geçtiyse sekme "gitmiş" sayılır.
     *
     * <p>İstemci her 15 sn'de bir nabız atıyor (bkz. frontend
     * {@code usePresence}); 40 sn ~2,5 kaçırılan nabza tolerans veriyor —
     * kısa bir ağ takılmasında sayı gereksiz yere düşmesin diye.
     */
    private static final Duration TTL = Duration.ofSeconds(40);

    private final Map<UUID, Map<String, Instant>> sekmeler = new ConcurrentHashMap<>();

    /** Bir sekmenin izlemeye başladığını/hâlâ izlemekte olduğunu bildirir. */
    public void nabiz(UUID yayinId, String tabId) {
        sekmeler.computeIfAbsent(yayinId, id -> new ConcurrentHashMap<>())
            .put(tabId, Instant.now());
    }

    /** Bir sekmenin izlemeyi bıraktığını bildirir — anında düşer. */
    public void ayril(UUID yayinId, String tabId) {
        Map<String, Instant> map = sekmeler.get(yayinId);
        if (map != null) {
            map.remove(tabId);
        }
    }

    /** O an bu yayını izleyen FARKLI sekme sayısı. */
    public int sayisi(UUID yayinId) {
        Map<String, Instant> map = sekmeler.get(yayinId);
        return map == null ? 0 : map.size();
    }

    /**
     * Nabzı kesilmiş (sekme kapanırken {@link #ayril} hiç gelmemiş —
     * tarayıcı çökmesi, ağ kaybı) sekmeleri temizler.
     */
    @Scheduled(every = "10s")
    void supur() {
        Instant sinir = Instant.now().minus(TTL);
        for (Map<String, Instant> map : sekmeler.values()) {
            map.values().removeIf(t -> t.isBefore(sinir));
        }
    }
}
