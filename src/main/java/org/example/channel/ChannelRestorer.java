package org.example.channel;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Uygulama açılışında aktif kanalları MediaMTX'e yeniden yazar.
 *
 * <p>MediaMTX path yapılandırmasını yalnızca bellekte tutuyor: konteyner
 * yeniden başladığında bütün kanallar kaybolur. Kalıcı tanım
 * {@code channels} tablosunda olduğu için açılışta oradan geri kuruluyor.
 * Bu, "sistem yeniden başladığında açık kanallar kendiliğinden ayağa kalksın"
 * gereksiniminin karşılığıdır.
 *
 * <p>Hata durumunda uygulama <b>yine de açılır</b>: MediaMTX henüz hazır
 * değilse ya da bir kaynak erişilemezse, backend'in tamamen çökmesi yerine
 * kanalların yayında olmaması tercih edilir. Yönetici durumu kanal
 * listesindeki {@code streaming} alanından görür ve
 * {@code POST /api/channels/restore} ile yeniden dener.
 */
@ApplicationScoped
public class ChannelRestorer {

    private static final Logger LOG = Logger.getLogger(ChannelRestorer.class);

    @Inject
    ChannelService channelService;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        try {
            channelService.restoreActiveChannels();
        } catch (RuntimeException e) {
            LOG.error("Açılışta kanallar geri yüklenemedi; uygulama yine de başlatılıyor. "
                + "POST /api/channels/restore ile yeniden deneyebilirsiniz.", e);
        }
    }
}
