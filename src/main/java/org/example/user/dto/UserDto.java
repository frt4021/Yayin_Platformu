package org.example.user.dto;

import java.time.Instant;

/**
 * Frontend'e dönen kullanıcı gösterimi. Keycloak'un UserRepresentation'ı çok
 * geniş ve iç alanlar (credentials, requiredActions, attributes) içeriyor;
 * dışarıya sadece bu alanlar açılır.
 *
 * @param id Keycloak kullanıcı id'si — token'daki {@code sub} ile aynı. Yerel
 *           {@code users.id} dışarıya hiç verilmez, tüm API'ler bu id ile çalışır.
 */
public record UserDto(
    String id,
    String username,
    String email,
    String firstName,
    String lastName,
    boolean enabled,
    String role,
    Instant createdAt
) {
}
