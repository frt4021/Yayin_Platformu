package org.example.radio;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Uygulama açılışında aktif radyoları MediaMTX'e yeniden yazar.
 *
 * <p>{@code ChannelRestorer} ile aynı gerekçe: MediaMTX path yapılandırmasını
 * yalnızca bellekte tutuyor, konteyner yeniden başladığında hepsi kayboluyor.
 * Radyolarda kayıp daha da görünür — {@code KOPRU} modundaki ffmpeg köprüsü de
 * path ile birlikte gittiği için yayın tamamen susar.
 *
 * <p>Hata durumunda uygulama <b>yine de açılır</b>: radyoların yayında
 * olmaması, backend'in tamamen çökmesine yeğdir.
 */
@ApplicationScoped
public class RadioRestorer {

    private static final Logger LOG = Logger.getLogger(RadioRestorer.class);

    @Inject
    RadioService radioService;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        try {
            radioService.restoreActiveRadios();
        } catch (RuntimeException e) {
            LOG.error("Açılışta radyolar geri yüklenemedi; uygulama yine de başlatılıyor. "
                + "POST /api/radios/restore ile yeniden deneyebilirsiniz.", e);
        }
    }
}
