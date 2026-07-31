package org.example.channel;

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
import org.example.channel.dto.ChannelDto;
import org.example.channel.dto.CreateChannelRequest;
import org.example.channel.dto.UpdateChannelRequest;
import org.example.user.Roles;

import java.util.List;
import java.util.UUID;

/**
 * Kanal CRUD.
 *
 * <p>Yetki iki kademeli: okuma giriş yapmış herkese açık (izleyici de kanal
 * listesini görüp yayını açabilmeli), değiştirme yönetici ve moderatöre.
 * Sınıf düzeyinde {@code @Authenticated}, değiştiren uçlarda ayrıca
 * {@code @RolesAllowed} var — metot düzeyindeki anotasyon sınıftakini ezer.
 */
@Path("/api/channels")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Kanallar", description = "Canlı yayın kanalları")
public class ChannelResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    ChannelService channelService;

    @GET
    @Operation(summary = "Kanalları listele",
        description = "streaming ve viewers alanları MediaMTX'ten anlık okunur.")
    public List<ChannelDto> list() {
        return channelService.list();
    }

    @GET
    @jakarta.ws.rs.Path("/capacity")
    @Operation(summary = "Kanal kapasitesi",
        description = "Yayında olan kanal sayısı ve üst sınır.")
    public ChannelService.Capacity capacity() {
        return channelService.capacity();
    }

    @GET
    @jakarta.ws.rs.Path("/{id}")
    @Operation(summary = "Kanal detayı")
    public ChannelDto get(@PathParam("id") UUID id) {
        return channelService.get(id);
    }

    @POST
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Kanal ekle",
        description = "active=true ise kanal hemen MediaMTX'e yazılır ve yayın çekilmeye başlar.")
    public Response create(@Valid CreateChannelRequest request, @Context UriInfo uriInfo) {
        ChannelDto created = channelService.create(request, jwt.getSubject());
        return Response
            .created(uriInfo.getAbsolutePathBuilder().path(created.id().toString()).build())
            .entity(created)
            .build();
    }

    @PUT
    @jakarta.ws.rs.Path("/{id}")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Kanalı güncelle")
    public ChannelDto update(@PathParam("id") UUID id, @Valid UpdateChannelRequest request) {
        return channelService.update(id, request);
    }

    @DELETE
    @jakarta.ws.rs.Path("/{id}")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Kanalı sil",
        description = "Kayıt silinir ve MediaMTX'teki path kaldırılır.")
    public Response delete(@PathParam("id") UUID id) {
        channelService.delete(id);
        return Response.noContent().build();
    }

    @POST
    @jakarta.ws.rs.Path("/restore")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Aktif kanalları MediaMTX'e yeniden yaz",
        description = "Açılışta otomatik çalışır; MediaMTX bağımsız yeniden başlatıldığında elle tetiklenir.")
    public RestoreResult restore() {
        return new RestoreResult(channelService.restoreActiveChannels());
    }

    /** @param restored MediaMTX'e başarıyla yazılan kanal sayısı */
    public record RestoreResult(int restored) {
    }
}
