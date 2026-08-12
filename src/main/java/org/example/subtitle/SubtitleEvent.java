package org.example.subtitle;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Canlı altyazı olayı — Redis üzerinden taşınıyor.
 *
 * <p><b>Neden Redis:</b> altyazıyı üreten süreç ({@code video-worker}) ile
 * tarayıcıya gönderen süreç ({@code backend}) <b>ayrı konteynerler</b>.
 * Doğrudan çağrı yapılamaz; ortak bir bildirim kanalı gerekiyor ve Redis
 * zaten klip kuyruğu için var.
 *
 * <p>Veritabanı yine de yazılıyor: Redis <b>doğruluk kaynağı değil</b>.
 * Sonradan bağlanan bir izleyici geçmişi REST'ten alıyor, canlı akışı
 * WebSocket'ten.
 */
public record SubtitleEvent(
    UUID channelId,
    Instant baslangic,
    Instant bitis,
    String kaynakDil,
    Map<String, String> metinler,
    boolean kesik
) {

    /** Redis kanal adı — yayınlayan taraf kanal başına ayrı kanala yazıyor. */
    public static String kanalAdi(UUID channelId) {
        return "altyazi:" + channelId;
    }

    /**
     * Dinleyen tarafın kullandığı desen: <b>hepsi tek abonelikte</b>.
     *
     * <p>Yayınlama kanal başına ayrı kalıyor (bir gün başka bir tüketici tek
     * kanalı dinlemek isterse diye), ama abone olan taraf hepsini tek
     * abonelikle alıp süzüyor. Gerekçe {@code SubtitleBroadcaster} içinde.
     */
    public static final String KANAL_DESENI = "altyazi:*";
}
