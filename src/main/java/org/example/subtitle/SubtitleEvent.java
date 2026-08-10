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

    /** Redis kanal adı — kanal başına ayrı, gereksiz yayın olmasın. */
    public static String kanalAdi(UUID channelId) {
        return "altyazi:" + channelId;
    }
}
