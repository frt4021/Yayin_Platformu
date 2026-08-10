package org.example.subtitle.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code altyazilar} — bir konuşma bölütünün çözümlenmiş ve çevrilmiş hali.
 *
 * <p><b>Zaman damgaları mutlak</b>, videoya göreli değil. İzleyici canlı
 * yayında 6-12 saniye geride; altyazının doğru kareye oturması ancak
 * {@code PROGRAM-DATE-TIME} üzerinden eşleyerek mümkün.
 */
@Entity
@Table(name = "altyazilar")
public class Subtitle extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    public Channel channel;

    @Column(nullable = false)
    public Instant baslangic;

    @Column(nullable = false)
    public Instant bitis;

    /** Whisper'ın tespit ettiği kaynak dil — çeviri için değil, bilgi için. */
    @Column(name = "kaynak_dil", length = 8)
    public String kaynakDil;

    public Float guven;

    /**
     * Dil kodundan metne: {@code {"en": "...", "tr": "...", ...}}.
     *
     * <p>Dil başına ayrı satır yerine tek JSON: bir bölütün tüm dilleri
     * <b>birlikte</b> üretiliyor ve birlikte okunuyor. Ayrı satırlar her
     * sorguda dört kat birleştirme ve tutarsız kalma riski getirirdi.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    public Map<String, String> metinler;

    /** Bölüt üst sınır aşıldığı için mi kesildi — cümle ortasında olabilir. */
    @Column(nullable = false)
    public boolean kesik = false;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    public Instant createdAt;

    // ------------------------------------------------------------------

    /**
     * Bir kanalın verilen aralıktaki altyazıları.
     *
     * <p>Aralıkla <b>kesişen</b> her bölüt dönüyor, yalnızca tamamen içinde
     * kalanlar değil: oynatıcı 3 saniyelik bir pencere soruyor ve o pencereye
     * taşan bir cümle de gösterilmeli.
     */
    public static List<Subtitle> between(UUID channelId, Instant from, Instant to) {
        return list("channel.id = ?1 and baslangic < ?3 and bitis > ?2 order by baslangic",
            channelId, from, to);
    }

    /** Aynı bölüt iki kez yazılmasın — STT yeniden denenirse çift kayıt olurdu. */
    public static boolean exists(UUID channelId, Instant baslangic) {
        return count("channel.id = ?1 and baslangic = ?2", channelId, baslangic) > 0;
    }
}
