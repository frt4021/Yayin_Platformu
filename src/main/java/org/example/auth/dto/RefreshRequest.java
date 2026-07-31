package org.example.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Access token'ı yenilemek için kullanılan refresh token. */
public record RefreshRequest(
    @NotBlank String refreshToken
) {
}
