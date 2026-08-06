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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.channel.entity.Channel;
import org.example.clip.ScheduledStatus;
import org.example.user.entity.AppUser;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code planli_kayitlar} — önceden verilmiş bir kayıt emri.
 *
 * <p><b>Manuel kayıttan farkı</b> ({@link ActiveRecording}): bitiş anı baştan
 * belli. Kullanıcı "durdur"a basmıyor, aralık geçince iş kendiliğinden
 * kliplenıyor. Sunucu o sırada kapalıysa bile emir kaybolmuyor — zamanlayıcı
 * açılışta geçmiş aralıkları da topluyor.
 */
@Entity
@Table(name = "planli_kayitlar")
public class ScheduledRecording extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    public Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public AppUser user;

    @Column(nullable = false)
    public Instant baslangic;

    @Column(nullable = false)
    public Instant bitis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ScheduledStatus durum = ScheduledStatus.BEKLIYOR;

    /** Üretilen klip; henüz üretilmediyse ya da silindiyse {@code null}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clip_id")
    public Clip clip;

    public String hata;

    /**
     * Kanalın DVR'ı kapalıyken bu plan için mi açıldı.
     *
     * <p>Bitişte geri kapatabilmek için tutuluyor. Tutulmasaydı, kullanıcının
     * hiç istemediği bir kanal tek bir planlı kayıt yüzünden sonsuza kadar
     * diske yazmaya devam ederdi.
     */
    @Column(name = "dvr_bizden", nullable = false)
    public boolean dvrBizden = false;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;

    // ------------------------------------------------------------------

    /** Başlangıcı gelmiş, hâlâ beklemede olan emirler. */
    public static List<ScheduledRecording> dueToStart(Instant now) {
        return list("durum = ?1 and baslangic <= ?2",
            ScheduledStatus.BEKLIYOR, now);
    }

    /** Aralığı dolmuş, kayıtta olan emirler. */
    public static List<ScheduledRecording> dueToFinish(Instant now) {
        return list("durum = ?1 and bitis <= ?2",
            ScheduledStatus.KAYITTA, now);
    }

    /** Bu kanalda kaydı kendisi açmış, hâlâ süren plan var mı. */
    public static boolean anyTemporaryOn(UUID channelId) {
        return count("channel.id = ?1 and durum = ?2 and dvrBizden = true",
            channelId, ScheduledStatus.KAYITTA) > 0;
    }

    public static List<ScheduledRecording> listFor(String keycloakId) {
        return list("user.keycloakId = ?1 order by baslangic desc", keycloakId);
    }

    public static List<ScheduledRecording> listAll() {
        return list("order by baslangic desc");
    }

    /** Çakışma denetimi: aynı kullanıcı aynı kanalda üst üste binen emir vermesin. */
    public static boolean overlaps(UUID channelId, UUID userId, Instant start, Instant end) {
        return count("channel.id = ?1 and user.id = ?2 and durum in ?3 "
                + "and baslangic < ?5 and bitis > ?4",
            channelId, userId,
            List.of(ScheduledStatus.BEKLIYOR, ScheduledStatus.KAYITTA),
            start, end) > 0;
    }
}
