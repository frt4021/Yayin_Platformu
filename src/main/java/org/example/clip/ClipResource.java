package org.example.clip;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.clip.dto.ActiveRecordingDto;
import org.example.clip.dto.ClipDto;

import java.util.List;
import java.util.UUID;

/**
 * Klip listeleme, indirme ve silme.
 *
 * <p>Oluşturma ayrı bir sınıfta ({@code ChannelClipResource}): JAX-RS
 * isteği <b>en iyi eşleşen kaynak sınıfına</b> yönlendirip yalnızca onun
 * metotlarına bakar. Bu sınıf {@code /api} altında olsaydı
 * {@code /api/channels/{id}/clips} isteği {@code ChannelResource}'a düşer
 * ve "eşleşen metot yok" hatası verirdi.
 *
 * <p>Giriş yapmış herkes erişir ama <b>yalnızca kendi kliplerini</b> görür;
 * klipler kayıt içeriği barındırdığı için varsayılan kapalı.
 */
@Path("/api/clips")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Klipler", description = "Kayıttan klip çıkarma")
public class ClipResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity identity;

    @Inject
    ClipService clipService;

    @Inject
    RecordingService recordingService;

    @GET
        @Operation(summary = "Klipleri listele",
        description = "Yönetici tümünü, diğerleri yalnızca kendi kliplerini görür.")
    public List<ClipDto> list(@QueryParam("channelId") UUID channelId,
                              @QueryParam("origin") ClipOrigin origin) {
        return clipService.list(channelId, origin, jwt.getSubject(), isAdmin());
    }

    @GET
    @Path("/kayitlar/devam-eden")
    @Operation(summary = "Devam eden kayıtlarım",
        description = "Arayüz kayıt düğmesini doğru durumda çizsin ve geçen süreyi "
            + "gösterebilsin diye.")
    public List<ActiveRecordingDto> activeRecordings() {
        return recordingService.listActive(jwt.getSubject());
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Klip durumu")
    public ClipDto get(@PathParam("id") UUID id) {
        return clipService.get(id, jwt.getSubject(), isAdmin());
    }

    @GET
    @Path("/{id}/links")
    @Operation(summary = "İzleme ve indirme adresleri",
        description = "Süreli imzalı adresler döner. Yönlendirme yerine JSON: "
            + "tarayıcı CORS nedeniyle yönlendirme yanıtındaki Location başlığını "
            + "okuyamıyor, adresi gövdede vermek tek güvenilir yol.")
    public ClipService.ClipLinks links(@PathParam("id") UUID id) {
        return clipService.links(id, jwt.getSubject(), isAdmin());
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Klip sil")
    public Response delete(@PathParam("id") UUID id) {
        clipService.delete(id, jwt.getSubject(), isAdmin());
        return Response.noContent().build();
    }

    private boolean isAdmin() {
        return ClipService.isAdmin(identity.getRoles());
    }
}
