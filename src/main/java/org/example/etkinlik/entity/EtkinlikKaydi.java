package org.example.etkinlik.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.example.etkinlik.EtkinlikTuru;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code etkinlik_kayitlari} — kullanıcı davranışı denetim izi (giriş/çıkış,
 * izleme/dinleme oturumu, admin/içerik eylemi, altyazı dil tercihi).
 *
 * <p>Tek, genel amaçlı tablo — dört ayrı olay kategorisi için dört ayrı tablo
 * yerine {@link EtkinlikTuru} ile ayrışıyor, {@code detay} JSON'u olay türüne
 * özgü alanları taşıyor (Subtitle.metinler ile aynı desen).
 */
@Entity
@Table(name = "etkinlik_kayitlari")
public class EtkinlikKaydi extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    /** Kullanıcı silinse bile iz kalsın diye nullable — bkz. {@link #kullaniciAdi}. */
    @Column(name = "kullanici_id")
    public UUID kullaniciId;

    /** Kullanıcı bulunamasa/silinse bile "kimdi" sorusunun tek cevabı. */
    @Column(name = "kullanici_adi", length = 64)
    public String kullaniciAdi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    public EtkinlikTuru tur;

    @Column(name = "hedef_turu", length = 32)
    public String hedefTuru;

    @Column(name = "hedef_id")
    public UUID hedefId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    public Map<String, Object> detay;

    @Generated(event = EventType.INSERT)
    @Column(name = "olusturma_zamani", insertable = false, updatable = false)
    public Instant olusturmaZamani;
}
