package org.example.clip.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.channel.entity.Channel;
import org.example.user.entity.AppUser;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code active_recordings} — devam eden bir manuel kayıt.
 *
 * <p><b>Neden tablo, neden bellek değil:</b> sunucu yeniden başlarsa devam
 * eden kayıt kaybolmamalı. Bellekte tutulsaydı kullanıcı "durdur"a bastığında
 * başlangıç anı yok olur ve kayıt hiç üretilemezdi.
 *
 * <p>Bileşik anahtar (kanal, kullanıcı): aynı kanalda birden fazla kullanıcı
 * ayrı ayrı kayıt alabilir, ama bir kullanıcı aynı kanalda ikinci bir kayıt
 * başlatamaz.
 */
@Entity
@Table(name = "active_recordings")
@IdClass(ActiveRecording.Key.class)
public class ActiveRecording extends PanacheEntityBase {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    public Channel channel;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public AppUser user;

    /** Değeri veritabanı {@code default now()} ile üretir. */
    @Generated(event = EventType.INSERT)
    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    public Instant startedAt;

    // ------------------------------------------------------------------

    public static ActiveRecording find(UUID channelId, String keycloakId) {
        return find("channel.id = ?1 and user.keycloakId = ?2", channelId, keycloakId)
            .firstResult();
    }

    /** Kullanıcının devam eden tüm kayıtları — arayüz birden fazlasını gösterebilsin. */
    public static List<ActiveRecording> listFor(String keycloakId) {
        return list("user.keycloakId = ?1 order by startedAt", keycloakId);
    }

    /**
     * Belirtilen andan önce başlamış kayıtlar.
     *
     * <p>Süpürücü bunları otomatik durduruyor: kullanıcı sekmeyi kapatırsa
     * kayıt sonsuza kadar açık kalır ve durdurulduğunda üst sınırı çoktan
     * aşmış bir aralık istenmiş olurdu.
     */
    public static List<ActiveRecording> startedBefore(Instant cutoff) {
        return list("startedAt < ?1 order by startedAt", cutoff);
    }

    /** {@code @IdClass} için bileşik anahtar; alan adları entity ile birebir aynı olmalı. */
    public static class Key implements Serializable {
        public UUID channel;
        public UUID user;

        public Key() {
        }

        public Key(UUID channel, UUID user) {
            this.channel = channel;
            this.user = user;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                && Objects.equals(channel, k.channel)
                && Objects.equals(user, k.user);
        }

        @Override
        public int hashCode() {
            return Objects.hash(channel, user);
        }
    }
}
