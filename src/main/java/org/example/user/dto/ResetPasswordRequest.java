package org.example.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yöneticinin bir kullanıcının şifresini sıfırlaması.
 * Mevcut şifre istenmez; yetki zaten admin rolünden gelir.
 */
public record ResetPasswordRequest(
    @NotBlank @Size(min = 8, max = 128) String newPassword,
    boolean temporary
) {
}
