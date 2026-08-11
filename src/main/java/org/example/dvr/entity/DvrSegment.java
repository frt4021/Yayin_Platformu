package org.example.dvr.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.channel.entity.Channel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bir DVR kayıt parçasının zaman çizelgesindeki yeri.
 *
 * <p>Parçanın kendisi MinIO'da; bu satır yalnızca <b>hangi anın hangi
 * nesnede</b> olduğunu söylüyor. Eskiden böyle bir kayıt yoktu: zaman
 * çizelgesi her istekte MediaMTX'in playback sunucusuna sorularak
 * üretiliyordu. Kayıt MediaMTX'ten alınınca o kaynak da ortadan kalktı.
 */
@Entity
@Table(name = "dvr_segments")
public class DvrSegment extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    public Channel channel;

    /** Segmentin kapsadığı yayın aralığının başlangıcı (UTC). */
    @Column(nullable = false)
    public Instant basladi;

    @Column(nullable = false)
    public Instant bitti;

    /** MinIO anahtarı: {@code <kanal>/<YYYY>/<AA>/<GG>/<SS>/<zaman>.ts} */
    @Column(name = "nesne_anahtari", nullable = false, length = 512)
    public String nesneAnahtari;

    @Column(name = "boyut_bayt", nullable = false)
    public long boyutBayt;

    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;

    /**
     * Aralığa <b>değen</b> segmentler, zaman sırasında.
     *
     * <p>Kesişim koşulu bilerek geniş: tamamen aralığın içinde kalanlar
     * yetmez. İstenen aralık bir segmentin ortasından başlayıp bir
     * diğerinin ortasında bitiyorsa uçtaki iki segment de gerekiyor --
     * kırpma sonradan yapılıyor.
     */
    public static List<DvrSegment> covering(UUID channelId, Instant from, Instant to) {
        return find("channel.id = ?1 and basladi < ?2 and bitti > ?3 order by basladi",
            channelId, to, from).list();
    }

    /** Bir kanalın tüm segmentleri, zaman sırasında — zaman çizelgesi için. */
    public static List<DvrSegment> timeline(UUID channelId, Instant from, Instant to) {
        return covering(channelId, from, to);
    }
}
