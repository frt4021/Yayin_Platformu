package org.example.user.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code roles} tablosu. Kayıtlar V1 migration'ında seed edilir
 * (ADMIN / MODERATOR / VIEWER), uygulama çalışırken yeni rol eklenmez.
 */
@Entity
@Table(name = "roles")
public class Role extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(nullable = false, unique = true, length = 32)
    public String name;

    public static Role byName(String name) {
        return find("name", name).firstResult();
    }
}
