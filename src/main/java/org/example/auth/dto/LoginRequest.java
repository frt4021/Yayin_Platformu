package org.example.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Giriş isteği. Şifre uzunluk doğrulamasına tabi tutulmuyor: burada bir
 * kayıt değil, var olan bir kimlik bilgisinin sınanması söz konusu ve
 * politika Keycloak'ta.
 */
public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {
}
