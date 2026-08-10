package org.example.subtitle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.example.channel.entity.Channel;
import org.example.exception.AppException;
import org.example.subtitle.dto.SubtitleDto;
import org.example.subtitle.entity.Subtitle;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Altyazı yazma ve okuma. */
@ApplicationScoped
public class SubtitleService {

    private static final Logger LOG = Logger.getLogger(SubtitleService.class);

    /**
     * Çözümlenmiş bir bölütü kaydeder.
     *
     * <p>Aynı bölüt iki kez gelirse <b>sessizce atlanıyor</b>: STT yeniden
     * denenirse ya da işçi iki kez açılırsa çift kayıt oluşur ve arayüz
     * altyazıyı çift gösterirdi.
     */
    @Transactional
    public void kaydet(UUID channelId, Instant baslangic, Instant bitis,
                       String kaynakDil, Float guven,
                       Map<String, String> metinler, boolean kesik) {
        if (Subtitle.exists(channelId, baslangic)) {
            return;
        }
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            // Kanal silinmis: altyazinin tutunacagi bir sey yok.
            return;
        }
        Subtitle s = new Subtitle();
        s.channel = channel;
        s.baslangic = baslangic;
        s.bitis = bitis;
        s.kaynakDil = kaynakDil;
        s.guven = guven;
        s.metinler = metinler;
        s.kesik = kesik;
        s.persist();
        LOG.debugf("Altyazı kaydedildi: %s [%s]", channel.name, baslangic);
    }

    /**
     * Bir kanalın verilen aralıktaki altyazıları.
     *
     * <p>Aralık {@code from} dahil, {@code to} hariç mantığıyla değil
     * <b>kesişim</b> mantığıyla çalışıyor: pencereye taşan bir cümle de
     * gösterilmeli.
     */
    public List<SubtitleDto> araliktakiler(UUID channelId, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw AppException.badRequest("Bitiş zamanı başlangıçtan sonra olmalı.");
        }
        return Subtitle.between(channelId, from, to).stream()
            .map(s -> new SubtitleDto(s.id, s.baslangic, s.bitis,
                s.kaynakDil, s.guven, s.metinler, s.kesik))
            .toList();
    }
}
