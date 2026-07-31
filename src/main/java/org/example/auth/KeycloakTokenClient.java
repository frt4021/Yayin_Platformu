package org.example.auth;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.example.auth.dto.TokenResponse;

/**
 * Keycloak'un OpenID Connect token uçları.
 *
 * <p>Uygulama kendi kullanıcı/şifre deposunu tutmuyor; giriş, yenileme ve
 * çıkış işlemlerinin tamamı Keycloak'a devrediliyor. Bu arayüz o üç ucun
 * karşılığı. Çalışması için Keycloak client'ında <b>Direct access grants</b>
 * açık olmalıdır.
 */
@Path("/realms")
@RegisterRestClient(configKey = "keycloak-token")
public interface KeycloakTokenClient {

    /** Kullanıcı adı + şifre ile token alır (OAuth2 "password" grant). */
    @POST
    @Path("/{realm}/protocol/openid-connect/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    TokenResponse passwordGrant(
        @PathParam("realm") String realm,
        @FormParam("grant_type") String grantType,
        @FormParam("client_id") String clientId,
        @FormParam("client_secret") String clientSecret,
        @FormParam("username") String username,
        @FormParam("password") String password);

    /** Refresh token ile yeni access token alır. */
    @POST
    @Path("/{realm}/protocol/openid-connect/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    TokenResponse refreshGrant(
        @PathParam("realm") String realm,
        @FormParam("grant_type") String grantType,
        @FormParam("client_id") String clientId,
        @FormParam("client_secret") String clientSecret,
        @FormParam("refresh_token") String refreshToken);

    /**
     * Oturumu Keycloak tarafında sonlandırır ve refresh token'ı geçersiz kılar.
     * Access token'lar imzalı ve durumsuz olduğu için kendi süreleri dolana
     * kadar geçerli kalmaya devam eder — çıkışın anlamı, yenilenememesidir.
     */
    @POST
    @Path("/{realm}/protocol/openid-connect/logout")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    void logout(
        @PathParam("realm") String realm,
        @FormParam("client_id") String clientId,
        @FormParam("client_secret") String clientSecret,
        @FormParam("refresh_token") String refreshToken);
}
