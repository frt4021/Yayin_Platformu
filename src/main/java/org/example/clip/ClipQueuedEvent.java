package org.example.clip;

import java.util.UUID;

/**
 * Yeni bir klip işi veritabanına yazıldığında fırlatılır.
 *
 * <p>Dinleyici {@code TransactionPhase.AFTER_SUCCESS} ile bağlanır: bildirim
 * yalnızca transaction gerçekten commit edildikten sonra Redis'e gider.
 * Transaction içinde gönderilseydi, işçi haberi alıp veritabanına baktığında
 * satırı henüz göremez ve iş kaybolmuş gibi görünürdü — commit'in geri
 * alındığı durumda ise var olmayan bir işi işlemeye çalışırdı.
 */
public record ClipQueuedEvent(UUID clipId) {
}
