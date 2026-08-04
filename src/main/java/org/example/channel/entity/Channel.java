package org.example.channel.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.example.user.entity.AppUser;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code channels} tablosu — bir kanalın kalıcı tanımı.
 *
 * <p>MediaMTX path'leri bellekte tutuluyor ve süreç yeniden başladığında
 * kayboluyor; kalıcı doğruluk kaynağı bu tablodur. Uygulama açılışta buradaki
 * aktif kanalları okuyup MediaMTX'e yeniden yazar (bkz. ChannelRestorer).
 */
@Entity
@Table(name = "channels")
public class Channel extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 128)
    public String name;

    @Column(name = "source_url", nullable = false, length = 512)
    public String sourceUrl;

    @Column(name = "mediamtx_path", nullable = false, unique = true, length = 128)
    public String mediamtxPath;

    /**
     * Kanalın yayında olması isteniyor mu. Pasif kanalın MediaMTX'te path'i
     * bulunmaz; kayıt silinmediği için tanım korunur ve tek alan değişikliğiyle
     * geri açılabilir.
     */
    @Column(nullable = false)
    public boolean active = true;

    /**
     * Geriye sarma (DVR) kaydı açık mı.
     *
     * <p>Ayrı bir bayrak: bir kanal yayında olup kaydedilmiyor olabilir.
     * 6 Mbps'lik bir kanal 7 günde ~454 GB yazdığı için kayıt, yayında
     * olmaktan bağımsız ve bilinçli bir karar olmalı.
     */
    @Column(name = "dvr_enabled", nullable = false)
    public boolean dvrEnabled = false;

    /**
     * Çözünürlük merdiveni: {@code 720p|1280x720|1500k,480p|854x480|800k}.
     * Boş ise transcode yapılmaz, kaynak olduğu gibi dağıtılır.
     *
     * <p>Kanal bazında: her kaynağın bit hızı farklı ve merdivendeki hedefler
     * kaynağınkinin altında kalmalı. Global tek bir ayar, düşük bit hızlı bir
     * kaynakta çözünürlüğü düşürüp bant genişliğini artırırdı.
     */
    @Column(nullable = false, length = 512)
    public String renditions = "";

    /**
     * DVR kaydının alınacağı rendition adı; boş ise kaynak çözünürlüğü.
     *
     * <p>Varsayılan 720p: ölçümde kaynak 2.33 Mbps, 720p 1.65 Mbps çıktı —
     * diskte %29 tasarruf. Kayıt her zaman kaynaktan alınsaydı 7 günlük DVR
     * gereksiz yere büyürdü.
     */
    @Column(name = "dvr_rendition", nullable = false, length = 32)
    public String dvrRendition = "";

    /**
     * Kaydın gerçekte yazıldığı MediaMTX path'i.
     *
     * <p>Geriye sarma ve klip çıkarma bu path üzerinden yapılmalı; kaynak
     * path'ine bakılırsa kayıt bulunamaz.
     */
    public String recordingPath() {
        return dvrRendition.isBlank() ? mediamtxPath : mediamtxPath + "_" + dvrRendition;
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    public AppUser createdBy;

    /** Değeri veritabanı {@code default now()} ile üretir, uygulama yazmaz. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    public static Channel byMediamtxPath(String path) {
        return find("mediamtxPath", path).firstResult();
    }

    public static List<Channel> listActive() {
        return list("active", true);
    }

    /**
     * Yayında olan kanal sayısı. Güncellemede kanalın kendisi sayıma dahil
     * edilmemeli — zaten aktifse kendi yerini işgal ediyor sayılır ve
     * kapasite dolu görünürdü.
     */
    public static long countActive(UUID exceptId) {
        return exceptId == null
            ? count("active", true)
            : count("active = true and id <> ?1", exceptId);
    }

    public static boolean nameTaken(String name, UUID exceptId) {
        return exceptId == null
            ? count("name", name) > 0
            : count("name = ?1 and id <> ?2", name, exceptId) > 0;
    }

    public static boolean pathTaken(String path, UUID exceptId) {
        return exceptId == null
            ? count("mediamtxPath", path) > 0
            : count("mediamtxPath = ?1 and id <> ?2", path, exceptId) > 0;
    }
}
