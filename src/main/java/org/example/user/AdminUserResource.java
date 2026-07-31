package org.example.user;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.user.dto.CreateUserRequest;
import org.example.user.dto.ResetPasswordRequest;
import org.example.user.dto.SyncResultDto;
import org.example.user.dto.UpdateRoleRequest;
import org.example.user.dto.UserDto;

import java.util.List;

/**
 * Yönetici kullanıcı yönetimi. Sınıf düzeyindeki {@code @RolesAllowed} tüm
 * uçları kapsar — yeni bir uç eklendiğinde yetkilendirmeyi unutmak mümkün olmaz.
 *
 * <p>Yol parametresi olarak <b>Keycloak kullanıcı id'si</b> beklenir (token'daki
 * {@code sub}); yerel {@code users.id} dışarıya hiç açılmaz.
 */
@Path("/api/admin/users")
@RolesAllowed(Roles.YONETICI)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Kullanıcı Yönetimi", description = "Yöneticiye özel kullanıcı işlemleri")
public class


AdminUserResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    UserService userService;

    @GET
    @Operation(summary = "Kullanıcıları listele",
        description = "search verilirse kullanıcı adı, ad, soyad ve e-postada aranır.")
    public List<UserDto> list(
        @QueryParam("search") String search,
        @QueryParam("first") @DefaultValue("0") @Min(0) int first,
        // Rol bilgisi kullanıcı başına ek bir Keycloak isteği gerektiriyor,
        // bu yüzden sayfa boyutu üstten sınırlı.
        @QueryParam("max") @DefaultValue("20") @Min(1) @Max(100) int max) {
        return userService.list(search, first, max);
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Kullanıcı detayı")
    public UserDto get(@PathParam("id") String id) {
        return userService.get(id);
    }

    @POST
    @Operation(summary = "Kullanıcı ekle", description = "Şifre ve rol ile birlikte oluşturur.")
    public Response create(@Valid CreateUserRequest request, @jakarta.ws.rs.core.Context UriInfo uriInfo) {
        UserDto created = userService.create(request);
        return Response
            .created(uriInfo.getAbsolutePathBuilder().path(created.id()).build())
            .entity(created)
            .build();
    }

    @PUT
    @Path("/{id}/role")
    @Operation(summary = "Rol ata", description = "Kullanıcının tek rolünü değiştirir.")
    public UserDto changeRole(@PathParam("id") String id, @Valid UpdateRoleRequest request) {
        return userService.changeRole(id, request.role(), jwt.getSubject());
    }

    @PUT
    @Path("/{id}/password")
    @Operation(summary = "Şifre sıfırla",
        description = "temporary=true ise kullanıcı ilk girişte şifresini değiştirmek zorunda kalır.")
    public Response resetPassword(@PathParam("id") String id, @Valid ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Kullanıcı sil")
    public Response delete(@PathParam("id") String id) {
        userService.delete(id, jwt.getSubject());
        return Response.noContent().build();
    }

    @POST
    @Path("/sync")
    @Operation(summary = "Keycloak ile eşitle",
        description = "Keycloak konsolundan yapılan değişiklikleri yerel tabloya yansıtır.")

    public SyncResultDto sync() {
        return userService.sync();
    }
}
