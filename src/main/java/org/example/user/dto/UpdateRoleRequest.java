package org.example.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Kullanıcının rolünü değiştirir. Bir kullanıcının tek rolü olduğu için bu
 * işlem <b>değiştirme</b>dir: eski rol Keycloak'tan kaldırılır, yenisi atanır.
 *
 * @param role ADMIN | MODERATOR | VIEWER
 */
public record UpdateRoleRequest(
    @NotBlank String role
) {
}
