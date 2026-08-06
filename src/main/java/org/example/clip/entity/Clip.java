package org.example.clip.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.channel.entity.Channel;
import org.example.clip.ClipOrigin;
import org.example.clip.ClipStatus;
import org.example.user.entity.AppUser;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code clips} tablosu — bir klip çıkarma işi.
 *
 * <p>Tablo aynı zamanda <b>kuyruktur</b>. Ayrı bir mesaj kuyruğu yerine
 * veritabanı kullanılıyor çünkü iş zaten burada kalıcı olmak zorunda: iki
 * yere birden yazmak, biri başarılı diğeri başarısız olduğunda ya kaybolan
 * ya iki kez işlenen işler üretirdi. {@code FOR UPDATE SKIP LOCKED} ile
 * birden fazla işçi aynı işi almadan güvenle çalışabilir.
 */
@Entity
@Table(name = "clips")
public class Clip extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    public Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    public AppUser requestedBy;

    @Column(name = "start_at", nullable = false)
    public Instant startAt;

    @Column(name = "end_at", nullable = false)
    public Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public ClipStatus status = ClipStatus.BEKLIYOR;

    /**
     * Klibin nasıl istendiği. Ürün ve yaşam döngüsü aynı olduğu için manuel
     * kayıtlar da bu tabloda; arayüz listeleri bu alana göre ayırıyor.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public ClipOrigin origin = ClipOrigin.ARALIK;

    @Column(name = "object_key", length = 512)
    public String objectKey;

    @Column(name = "size_bytes")
    public Long sizeBytes;

    @Column
    public String error;

    @Column(nullable = false)
    public int attempts = 0;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    /**
     * Kuyruktan bir sonraki işi alır.
     *
     * <p>{@code SKIP LOCKED}: başka bir işçinin kilitlediği satır beklenmez,
     * atlanır. Bu olmadan iki işçi aynı satırda sıraya girer ve
     * paralellik kaybolurdu.
     */
    public static List<Clip> lockNextPending(int limit) {
        return find("status = ?1 order by createdAt", ClipStatus.BEKLIYOR)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .page(0, limit)
            .list();
    }

    /** Kullanıcının kliplerinin toplam boyutu — kota hesabı için. */
    public static long totalBytesOf(String keycloakId) {
        return find("requestedBy.keycloakId = ?1 and sizeBytes is not null", keycloakId)
            .project(SizeOnly.class).stream()
            .mapToLong(SizeOnly::sizeBytes).sum();
    }

    /** Yalnızca boyut sütununu çeken izdüşüm; tüm satırı belleğe almamak için. */
    public record SizeOnly(long sizeBytes) {
    }

    public static long countActive() {
        return count("status in (?1, ?2)", ClipStatus.BEKLIYOR, ClipStatus.ISLENIYOR);
    }

    public java.time.Duration duration() {
        return java.time.Duration.between(startAt, endAt);
    }
}
