package org.example.playback;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Oynatma hatası/takılma sayaçları — Prometheus/Grafana tarafı.
 *
 * <p>{@code SubtitleLagMetrics}'in aksine {@code temizle()} yok: buradaki
 * her şey monoton bir toplam ({@code RetentionSweeper}'ın
 * {@code depolama_temizlik_silinen_toplam} sayacıyla aynı desen), Gauge gibi
 * canlı bir değeri sonsuza kadar tutmuyor. Silinen/yeniden adlandırılan bir
 * kanalın sayacı yalnızca artmayı bırakır; Prometheus kendi staleness
 * mekanizmasıyla birkaç dakika sonra sorgulardan düşürür.
 */
@ApplicationScoped
public class PlaybackHealthMetrics {

    @Inject
    MeterRegistry registry;

    public void bildir(String kaynakTuru, String ad, int hataSayisi, int takilmaSayisi) {
        if (hataSayisi > 0) {
            registry.counter("oynatma_hata_toplam", "kaynak", kaynakTuru, "ad", ad)
                .increment(hataSayisi);
        }
        if (takilmaSayisi > 0) {
            registry.counter("oynatma_takilma_toplam", "kaynak", kaynakTuru, "ad", ad)
                .increment(takilmaSayisi);
        }
    }
}
