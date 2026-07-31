package org.example.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Kullanıcının kendi şifresini değiştirmesi. Mevcut şifre zorunludur —
 * çalınan bir oturum çerezi/token'ı ile şifre ele geçirilmesin diye.
 */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank @Size(min = 8, max = 128) String newPassword
) {
}
