package org.example.screenshot.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.channel.entity.Channel;
import org.example.user.entity.AppUser;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code screenshots} — canlı yayından yakalanmış bir kare.
 *
 * <p>Kliplerin aksine <b>durum makinesi yok</b>: kare tarayıcıda yakalanıp
 * hazır bir görsel olarak geliyor, arka planda üretilecek bir şey kalmıyor.
 */
@Entity
@Table(name = "screenshots")
public class Screenshot extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    public Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "captured_by", nullable = false)
    public AppUser capturedBy;

    /**
     * Karenin ait olduğu <b>yayın</b> anı — kaydın oluşturulduğu an değil.
     * Geriye sarmadan yakalananlarda ikisi saatlerce farklı olabiliyor.
     */
    @Column(name = "captured_at", nullable = false)
    public Instant capturedAt;

    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    public String objectKey;

    @Column
    public Integer width;

    @Column
    public Integer height;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(length = 200)
    public String note;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    // ------------------------------------------------------------------

    /**
     * Kronolojik galeri.
     *
     * @param ownerKeycloakId {@code null} ise tümü (yönetici); doluysa yalnızca
     *                        o kullanıcının kareleri
     * @param channelId       {@code null} ise tüm kanallar
     */
    public static List<Screenshot> gallery(String ownerKeycloakId, UUID channelId,
                                           int offset, int limit) {
        StringBuilder ql = new StringBuilder();
        Parameters params = new Parameters();

        if (ownerKeycloakId != null) {
            ql.append("capturedBy.keycloakId = :sahip");
            params.and("sahip", ownerKeycloakId);
        }
        if (channelId != null) {
            if (!ql.isEmpty()) {
                ql.append(" and ");
            }
            ql.append("channel.id = :kanal");
            params.and("kanal", channelId);
        }
        ql.append(ql.isEmpty() ? "order by capturedAt desc" : " order by capturedAt desc");

        return find(ql.toString(), params).page(offset / limit, limit).list();
    }

    /** Kullanıcının ekran görüntülerinin toplam boyutu — kota hesabı için. */
    public static long totalBytesOf(String keycloakId) {
        return find("capturedBy.keycloakId = ?1", keycloakId)
            .project(SizeOnly.class).stream()
            .mapToLong(SizeOnly::sizeBytes).sum();
    }

    /** Temizlik süpürücüsü için: verilen andan eski kayıtlar. */
    public static List<Screenshot> olderThan(Instant cutoff, int limit) {
        return find("createdAt < ?1 order by createdAt", cutoff).page(0, limit).list();
    }

    /** Yalnızca boyut sütununu çeken izdüşüm; tüm satırı belleğe almamak için. */
    public record SizeOnly(long sizeBytes) {
    }
}
