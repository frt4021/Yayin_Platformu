package org.example.auth;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.auth.dto.LoginRequest;
import org.example.auth.dto.LogoutRequest;
import org.example.auth.dto.RefreshRequest;
import org.example.auth.dto.TokenResponse;

/**
 * Oturum uçları. Üçü de {@code @PermitAll} — kimlik doğrulamanın kendisi
 * burada yapıldığı için token istenemez.
 *
 * <p>Çıkışın da açık olması bilinçli: access token'ın süresi dolduktan sonra
 * kullanıcı çıkış yapamasaydı, refresh token Keycloak'ta geçerli kalmaya devam
 * ederdi. İşlemin anahtarı zaten refresh token'ın kendisi — onu bilmeyen bir
 * oturumu kapatamaz.
 */
@Path("/api/auth")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Oturum", description = "Giriş, token yenileme ve çıkış")
public class AuthResource {

    @Inject
    AuthService authService;

    @POST
    @Path("/login")
    @Operation(summary = "Giriş yap",
        description = "Kullanıcı adı ve şifre ile access + refresh token alır.")
    public TokenResponse login(@Valid LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @POST
    @Path("/refresh")
    @Operation(summary = "Token yenile",
        description = "Refresh token ile yeni bir access token alır.")
    public TokenResponse refresh(@Valid RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @POST
    @Path("/logout")
    @Operation(summary = "Çıkış yap",
        description = "Keycloak oturumunu sonlandırır ve refresh token'ı geçersiz kılar. "
            + "Access token, kalan ömrü boyunca geçerli olmaya devam eder.")
    public Response logout(@Valid LogoutRequest request) {
        authService.logout(request.refreshToken());
        return Response.noContent().build();
    }
}
