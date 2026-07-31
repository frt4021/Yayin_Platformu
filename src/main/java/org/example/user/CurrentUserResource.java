package org.example.user;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.user.dto.ChangePasswordRequest;
import org.example.user.dto.UserDto;

/**
 * Kullanıcının kendi hesabı. Rol gerektirmez — giriş yapmış olmak yeterli.
 * Hedef kullanıcı her zaman token'ın sahibidir; istekten kullanıcı id'si
 * alınmaz, böylece başkasının hesabına dokunmak yapısal olarak imkânsızdır.
 */
@Path("/api/users/me")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Profil", description = "Kullanıcının kendi hesabı")
public class CurrentUserResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    UserService userService;

    @GET
    @Operation(summary = "Kendi profilini görüntüle")
    public UserDto profile() {
        return userService.get(jwt.getSubject());
    }

    @PUT
    @Path("/password")
    @Operation(summary = "Kendi şifresini değiştir",
        description = "Mevcut şifre doğrulanmadan değişiklik yapılmaz.")
    public Response changePassword(@Valid ChangePasswordRequest request) {
        userService.changeOwnPassword(jwt.getSubject(), TokenClaims.username(jwt), request);
        return Response.noContent().build();
    }
}
