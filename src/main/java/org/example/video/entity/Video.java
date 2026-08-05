package org.example.video.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
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
import org.example.user.entity.AppUser;
import org.example.video.VideoStatus;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code videos} tablosu — kütüphanedeki bir video.
 *
 * <p>Tablo aynı zamanda <b>kuyruktur</b>; kliplerdeki {@code Clip} ile aynı
 * desen. {@code FOR UPDATE SKIP LOCKED} sayesinde birden fazla işçi aynı işi
 * almadan güvenle çalışabilir.
 *
 * <p><b>İşçinin doldurduğu alanlara istemci karışmaz.</b> {@code sizeBytes},
 * {@code durationSeconds}, {@code width} ve {@code height} yalnızca dosya
 * okunarak belirlenir: imzalı yükleme adresine herhangi bir bayt dizisi
 * yazılabildiği için istemcinin bildirdiği değerler bir iddia, kanıt değil.
 */
@Entity
@Table(name = "videos")
public class Video extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, length = 200)
    public String title;

    @Column
    public String description;

    /** MinIO anahtarı. Sunucu üretir; istemciden asla alınmaz. */
    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    public String objectKey;

    @Column(name = "thumbnail_key", length = 512)
    public String thumbnailKey;

    /**
     * Elle seçilen kare anı (saniye). {@code null} ise küçük resim otomatik
     * seçilmiş ya da kullanıcı kendi görselini yüklemiştir.
     */
    /**
     * Izgarada fare kartın üzerine gelince oynayan kısa klip.
     *
     * <p>{@code null} olabilir: önizleme bir kolaylık, üretimi başarısız
     * olursa video yine izlenebilir kalır ve kart küçük resme düşer.
     */
    @Column(name = "preview_key", length = 512)
    public String previewKey;

    @Column(name = "thumbnail_at_seconds")
    public Integer thumbnailAtSeconds;

    /**
     * Küçük resmi kullanıcı mı yükledi.
     *
     * <p>{@link #thumbnailAtSeconds}'tan türetilemez: yüklenen görselde de o
     * alan boş kalır, yani "otomatik seçim" ile ayırt edilemezdi. İşçinin
     * kareyi yeniden üretirken kullanıcının görselini ezmemesi buna bağlı.
     */
    @Column(name = "thumbnail_is_upload", nullable = false)
    public boolean thumbnailIsUpload = false;

    /** İndirme adında kullanılır; {@link #objectKey} bir uuid olduğu için ona güvenilmez. */
    @Column(name = "original_filename", length = 255)
    public String originalFilename;

    @Column(name = "content_type", length = 100)
    public String contentType;

    @Column(name = "size_bytes")
    public Long sizeBytes;

    @Column(name = "duration_seconds")
    public Integer durationSeconds;

    @Column
    public Integer width;

    @Column
    public Integer height;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public VideoStatus status = VideoStatus.YUKLENIYOR;

    @Column
    public String error;

    @Column(nullable = false)
    public int attempts = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    public AppUser uploadedBy;

    /** Değeri veritabanı {@code default now()} ile üretir, uygulama yazmaz. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    public Instant completedAt;

    // ------------------------------------------------------------------

    /**
     * İşlenmeyi bekleyen bir sonraki işleri kilitleyerek alır.
     *
     * <p>{@code SKIP LOCKED}: başka bir işçinin kilitlediği satır beklenmez,
     * atlanır. Bu olmadan iki işçi aynı satırda sıraya girer ve paralellik
     * kaybolurdu.
     */
    public static List<Video> lockNextPending(int limit) {
        return find("status = ?1 order by createdAt", VideoStatus.ISLENIYOR)
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .page(0, limit)
            .list();
    }

    /**
     * Belirtilen andan önce açılmış ve hâlâ tamamlanmamış yüklemeler.
     *
     * <p>Süpürücü bunları MinIO'ya sorar: nesne varsa yükleme aslında bitmiş
     * ama "tamamlandı" bildirimi ulaşmamıştır (kullanıcı sekmeyi kapatmış,
     * ağ kopmuş); nesne yoksa yükleme gerçekten yarım kalmıştır.
     */
    public static List<Video> staleUploads(Instant before, int limit) {
        return find("status = ?1 and createdAt < ?2 order by createdAt",
            VideoStatus.YUKLENIYOR, before)
            .page(0, limit)
            .list();
    }

    public static long countByStatus(VideoStatus status) {
        return count("status", status);
    }

    /**
     * Kütüphane listesi. Arama boşsa tümü, doluysa başlıkta geçenler —
     * büyük/küçük harf duyarsız ({@code idx_videos_baslik} bu aramayı
     * karşılıyor).
     */
    public static List<Video> search(String query, int offset, int limit) {
        if (query == null || query.isBlank()) {
            return find("order by createdAt desc").page(offset / limit, limit).list();
        }
        return find("lower(title) like lower(:q) order by createdAt desc",
            Parameters.with("q", "%" + query.trim() + "%"))
            .page(offset / limit, limit)
            .list();
    }

    /** İzlenebilir mi — küçük resim ve metadata hazır mı. */
    public boolean isPlayable() {
        return status == VideoStatus.HAZIR;
    }
}
