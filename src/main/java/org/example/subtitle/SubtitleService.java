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
     * Bir bölütün bir veya birden fazla dilini kaydeder/birleştirir.
     *
     * <p>Triton'a geçişle pivot (İngilizce) ve her çeviri AYRI AYRI, kendi
     * hazır olduğu anda gelir ("anında yayınla" deseni — bir dil geç kalırsa
     * diğerleri onu beklemez). Aynı {@code (channelId, baslangic)} için ilk
     * çağrı satırı OLUŞTURUR, sonrakiler VAR OLAN satırın {@code metinler}
     * haritasına EKLER — üzerine yazmaz, birleştirir.
     *
     * @return o an için GÜNCEL (birleşmiş) metinler haritası — çağıran taraf
     *         bunu olduğu gibi yayınlar, böylece izleyici o ana kadar hazır
     *         olan TÜM dilleri görür.
     */
    @Transactional
    public Map<String, String> kaydetVeyaBirlestir(UUID channelId, Instant baslangic, Instant bitis,
                                                    String kaynakDil, Float guven,
                                                    Map<String, String> yeniMetinler, boolean kesik) {
        Subtitle s = Subtitle.bul(channelId, baslangic);
        if (s == null) {
            Channel channel = Channel.findById(channelId);
            if (channel == null) {
                // Kanal silinmis: altyazinin tutunacagi bir sey yok.
                return yeniMetinler;
            }
            s = new Subtitle();
            s.channel = channel;
            s.baslangic = baslangic;
            s.bitis = bitis;
            s.kaynakDil = kaynakDil;
            s.guven = guven;
            s.metinler = new java.util.HashMap<>(yeniMetinler);
            s.kesik = kesik;
            s.persist();
            LOG.debugf("Altyazı oluşturuldu: %s [%s] +%s", channel.name, baslangic, yeniMetinler.keySet());
            return s.metinler;
        }

        // Var olan satira YENI dilleri ekle -- yonetilen entity'nin alanina
        // YENI bir Map atamak sart, mevcut Map'i yerinde degistirmek
        // Hibernate'in JSON kolon icin dirty-check'ini tetiklemeyebilir.
        Map<String, String> guncel = new java.util.HashMap<>(s.metinler);
        guncel.putAll(yeniMetinler);
        s.metinler = guncel;
        LOG.debugf("Altyazı güncellendi: %s [%s] +%s", s.channel.name, baslangic, yeniMetinler.keySet());
        return s.metinler;
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
