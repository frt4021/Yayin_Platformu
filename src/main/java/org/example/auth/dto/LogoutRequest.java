package org.example.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Çıkışta geçersiz kılınacak refresh token. */
public record LogoutRequest(
    @NotBlank String refreshToken
) {
}
