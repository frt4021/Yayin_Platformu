package org.example.subtitle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Canlı altyazı akışı.
 *
 * <pre>
 * ws://&lt;host&gt;/ws/altyazi/{channelId}
 * </pre>
 *
 * <p>Her mesaj bir {@link SubtitleEvent}: mutlak zaman damgaları ve dil
 * kodundan metne bir harita. Oynatıcı kendi {@code playingDate()} değeriyle
 * eşleştiriyor — mesajın <b>geldiği an</b> değil, taşıdığı zaman damgası
 * belirleyici.
 *
 * <h2>Neden yoklamanın yerini aldı</h2>
 * Yoklama saniyede bir istek demekti ve altyazı yine de bir tik geç
 * görünüyordu. WebSocket'te altyazı üretilir üretilmez gidiyor.
 *
 * <p>Geçmiş yine REST'ten alınıyor: sonradan bağlanan bir izleyici bağlantı
 * öncesindeki altyazıları WebSocket'ten göremez.
 *
 * <h2>Bilinen eksik</h2>
 * <b>Kimlik doğrulaması yok.</b> Adresi bilen bağlanabilir. HLS yayınında da
 * aynı durum var (bkz. `notlar.md`) — altyazı o yayının türevi olduğu için
 * korumayı tek başına buraya koymak yanıltıcı bir güvenlik hissi verirdi.
 * İkisi birlikte çözülmeli.
 */
@ServerEndpoint("/ws/altyazi/{channelId}")
@ApplicationScoped
public class SubtitleSocket {

    private static final Logger LOG = Logger.getLogger(SubtitleSocket.class);

    @Inject
    SubtitleBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session, @PathParam("channelId") String channelId) {
        UUID id = parse(channelId);
        if (id == null) {
            // Bozuk kimlikle acilan baglantiyi acik birakmak, izleyicinin
            // sessizce hicbir sey almamasi demek olurdu.
            kapat(session, "Geçersiz kanal kimliği");
            return;
        }
        session.getUserProperties().put("channelId", id);
        broadcaster.join(id, session);
    }

    @OnClose
    public void onClose(Session session) {
        ayril(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        // Ag kopmasi olagan; gurultu yapmadan temizliyoruz.
        LOG.debugf("Altyazı soketi hatası: %s", error.getMessage());
        ayril(session);
    }

    private void ayril(Session session) {
        Object id = session.getUserProperties().get("channelId");
        if (id instanceof UUID channelId) {
            broadcaster.leave(channelId, session);
        }
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void kapat(Session session, String sebep) {
        try {
            session.close(new jakarta.websocket.CloseReason(
                jakarta.websocket.CloseReason.CloseCodes.CANNOT_ACCEPT, sebep));
        } catch (Exception e) {
            LOG.debugf("Soket kapatılamadı: %s", e.getMessage());
        }
    }
}
