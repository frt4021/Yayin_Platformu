package org.example.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Keycloak'un token yanıtı. Alan adları OAuth2'nin snake_case standardında
 * geldiği için açıkça eşleniyor; dışarıya da aynı adlarla veriliyor ki
 * frontend standart bir OIDC istemcisiyle çalışabilsin.
 *
 * <p>Keycloak bunların dışında {@code session_state}, {@code scope},
 * {@code not-before-policy} gibi alanlar da döner; frontend'in işine
 * yaramadıkları için taşınmıyor.
 *
 * @param expiresIn        access token'ın saniye cinsinden ömrü
 * @param refreshExpiresIn refresh token'ın saniye cinsinden ömrü; bu süre
 *                         dolduğunda kullanıcı yeniden giriş yapmalı
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("expires_in") long expiresIn,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("refresh_expires_in") long refreshExpiresIn,
    @JsonProperty("token_type") String tokenType
) {
}
