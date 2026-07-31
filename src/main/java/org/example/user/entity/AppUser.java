package org.example.user.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code users} tablosu — Keycloak kullanıcısının yerel karşılığı.
 *
 * <p>Bu tablo kimlik doğrulama için <b>kullanılmaz</b>; şifre burada tutulmaz.
 * Varlık sebebi, uygulamanın kendi verisinin (kanal, yayın, kayıt) bir
 * kullanıcıya foreign key ile bağlanabilmesidir. Keycloak'taki kullanıcı ile
 * bağ {@link #keycloakId} üzerinden kurulur; bu değer token'daki {@code sub}
 * claim'i ile birebir aynıdır.
 *
 * <p>Doğruluk kaynağı Keycloak'tır. Yönetici uygulama dışından (Keycloak
 * konsolu) kullanıcı açıp silebildiği için bu tablo kaymaya açıktır; kayma
 * {@code UserProvisioningService} (istek anında) ve {@code /api/admin/users/sync}
 * (toplu) ile kapatılır.
 */
@Entity
@Table(name = "users")
public class AppUser extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "keycloak_id", nullable = false, unique = true, length = 36)
    public String keycloakId;

    @Column(nullable = false, unique = true, length = 64)
    public String username;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    public Role role;

    /** Değeri veritabanı {@code default now()} ile üretir, uygulama yazmaz. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    public static AppUser byKeycloakId(String keycloakId) {
        return find("keycloakId", keycloakId).firstResult();
    }

    public static long deleteByKeycloakId(String keycloakId) {
        return delete("keycloakId", keycloakId);
    }
}
