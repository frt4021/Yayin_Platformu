package org.example.video;

import java.util.UUID;

/**
 * "Yeni video işi hazır" bildirimi.
 *
 * <p>Kliplerdeki {@code ClipQueuedEvent} ile aynı rol: dinleyici
 * {@code AFTER_SUCCESS} ile bağlanır, böylece bildirim ancak transaction
 * commit edildikten sonra gider. Commit'ten önce gönderilseydi işçi, henüz
 * görünmeyen bir satırı okumaya çalışırdı.
 */
public record VideoQueuedEvent(UUID videoId) {
}
