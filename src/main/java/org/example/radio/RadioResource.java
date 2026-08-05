package org.example.radio;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.radio.dto.CreateRadioRequest;
import org.example.radio.dto.RadioDto;
import org.example.radio.dto.UpdateRadioRequest;
import org.example.user.Roles;

import java.util.List;
import java.util.UUID;

/**
 * Radyo CRUD.
 *
 * <p>Yetkilendirme kanallardaki çizgiyle aynı: okuma giriş yapmış herkese
 * açık (izleyici de radyo dinleyebilmeli), değiştirme yönetici ve moderatöre.
 */
@Path("/api/radios")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Radyolar", description = "Radyo yayınları")
public class RadioResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    RadioService radioService;

    @GET
    @Operation(summary = "Radyoları listele",
        description = "streaming ve listeners alanları MediaMTX'ten anlık okunur.")
    public List<RadioDto> list() {
        return radioService.list();
    }

    @GET
    @jakarta.ws.rs.Path("/capacity")
    @Operation(summary = "Radyo kapasitesi",
        description = "Yayında olan radyo sayısı ve üst sınır. Kanal kapasitesinden ayrıdır.")
    public RadioService.Capacity capacity() {
        return radioService.capacity();
    }

    @GET
    @jakarta.ws.rs.Path("/{id}")
    @Operation(summary = "Radyo detayı")
    public RadioDto get(@PathParam("id") UUID id) {
        return radioService.get(id);
    }

    @POST
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Radyo ekle",
        description = "sourceKind=KOPRU ise MediaMTX içinde bir ffmpeg köprüsü başlatılır; "
            + "DOGRUDAN ise adres MediaMTX'e kaynak olarak verilir.")
    public Response create(@Valid CreateRadioRequest request, @Context UriInfo uriInfo) {
        RadioDto created = radioService.create(request, jwt.getSubject());
        return Response
            .created(uriInfo.getAbsolutePathBuilder().path(created.id().toString()).build())
            .entity(created)
            .build();
    }

    @PUT
    @jakarta.ws.rs.Path("/{id}")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Radyoyu güncelle")
    public RadioDto update(@PathParam("id") UUID id, @Valid UpdateRadioRequest request) {
        return radioService.update(id, request);
    }

    @DELETE
    @jakarta.ws.rs.Path("/{id}")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Radyoyu sil",
        description = "Kayıt silinir, MediaMTX'teki path kaldırılır ve varsa ffmpeg köprüsü durur.")
    public Response delete(@PathParam("id") UUID id) {
        radioService.delete(id);
        return Response.noContent().build();
    }

    @POST
    @jakarta.ws.rs.Path("/restore")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Aktif radyoları MediaMTX'e yeniden yaz",
        description = "Açılışta otomatik çalışır; MediaMTX bağımsız yeniden başlatıldığında elle tetiklenir.")
    public RestoreResult restore() {
        return new RestoreResult(radioService.restoreActiveRadios());
    }

    /** @param restored MediaMTX'e başarıyla yazılan radyo sayısı */
    public record RestoreResult(int restored) {
    }
}
