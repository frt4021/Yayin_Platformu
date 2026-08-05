package org.example.radio.entity;

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
import org.example.radio.RadioSourceKind;
import org.example.user.entity.AppUser;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code radios} tablosu — bir radyo yayınının kalıcı tanımı.
 *
 * <p>Kanallarla aynı altyapıyı (MediaMTX) kullanıyor ama ayrı tabloda:
 * çözünürlük merdiveni ve DVR alanlarının radyoda karşılığı yok.
 *
 * <p>Kanallarda olduğu gibi kalıcı doğruluk kaynağı bu tablodur; MediaMTX
 * path'leri bellekte tutuyor ve yeniden başlayınca kaybediyor.
 */
@Entity
@Table(name = "radios")
public class Radio extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 128)
    public String name;

    @Column(name = "source_url", nullable = false, length = 512)
    public String sourceUrl;

    /** Kaynağın MediaMTX'e nasıl bağlanacağı; kullanıcı seçer. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 16)
    public RadioSourceKind sourceKind = RadioSourceKind.KOPRU;

    @Column(name = "mediamtx_path", nullable = false, unique = true, length = 128)
    public String mediamtxPath;

    /**
     * {@link RadioSourceKind#KOPRU} modunda ffmpeg'in üreteceği AAC bit hızı.
     * {@code DOGRUDAN} modda kullanılmaz — orada akışa hiç dokunulmuyor.
     */
    @Column(nullable = false, length = 16)
    public String bitrate = "128k";

    /**
     * Yayında olması isteniyor mu. Pasif radyonun MediaMTX'te path'i bulunmaz;
     * kayıt silinmediği için tanım korunur ve tek alanla geri açılabilir.
     */
    @Column(nullable = false)
    public boolean active = true;

    @Column(name = "logo_url", length = 512)
    public String logoUrl;

    /** Listede gösterim sırası; eşitlerde ada göre sıralanır. */
    @Column(name = "sort_order", nullable = false)
    public int sortOrder = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    public AppUser createdBy;

    /** Değeri veritabanı {@code default now()} ile üretir, uygulama yazmaz. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    // ------------------------------------------------------------------

    public static List<Radio> listAllSorted() {
        return list("order by sortOrder, name");
    }

    public static List<Radio> listActive() {
        return list("active = true order by sortOrder, name");
    }

    public static long countActive(UUID exceptId) {
        return exceptId == null
            ? count("active = true")
            : count("active = true and id <> ?1", exceptId);
    }

    public static boolean nameTaken(String name, UUID exceptId) {
        return exceptId == null
            ? count("lower(name) = lower(?1)", name) > 0
            : count("lower(name) = lower(?1) and id <> ?2", name, exceptId) > 0;
    }

    public static boolean pathTaken(String path, UUID exceptId) {
        return exceptId == null
            ? count("mediamtxPath = ?1", path) > 0
            : count("mediamtxPath = ?1 and id <> ?2", path, exceptId) > 0;
    }
}
