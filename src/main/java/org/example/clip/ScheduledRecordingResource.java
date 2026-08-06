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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.clip.dto.ScheduledRecordingDto;

import java.util.List;
import java.util.UUID;

/**
 * Planlı kayıt emirleri.
 *
 * <p>Oluşturma ayrı bir sınıfta ({@link ChannelScheduledRecordingResource});
 * listeleme ve iptal kanaldan bağımsız çünkü kullanıcı "hangi emirlerim var"
 * sorusunu kanal kanal dolaşmadan sorabilmeli.
 */
@Path("/api/planli-kayitlar")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Planlı kayıt", description = "Önceden verilen kayıt emirleri")
public class ScheduledRecordingResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity identity;

    @Inject
    ScheduledRecordingService service;

    @GET
    @Operation(summary = "Kayıt emirlerim",
        description = "Yönetici hepsini, diğerleri yalnızca kendi emirlerini görür.")
    public List<ScheduledRecordingDto> list() {
        return service.list(jwt.getSubject(), isAdmin());
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Kayıt emrini iptal et",
        description = "Yalnızca henüz sonuçlanmamış emirler iptal edilebilir.")
    public Response cancel(@PathParam("id") UUID id) {
        service.cancel(id, jwt.getSubject(), isAdmin());
        return Response.noContent().build();
    }

    /** Rol adı tek yerde: {@link ClipService} ile aynı kural geçerli. */
    private boolean isAdmin() {
        return ClipService.isAdmin(identity.getRoles());
    }
}
