package org.example.video.subtitle;

import java.util.UUID;

/**
 * "Yeni video altyazı işi hazır" bildirimi — {@code VideoQueuedEvent} ile
 * aynı rol, ayrı bir kuyruk için (bkz. paket dokümantasyonu).
 */
public record VideoSubtitleQueuedEvent(UUID videoId) {
}
