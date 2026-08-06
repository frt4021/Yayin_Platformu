package org.example.clip;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.clip.dto.CreateScheduledRecordingRequest;
import org.example.clip.dto.ScheduledRecordingDto;

import java.util.UUID;

/**
 * Bir kanala kayıt emri verme.
 *
 * <p><b>Neden ayrı sınıf:</b> JAX-RS isteği <b>en iyi eşleşen kaynak
 * sınıfına</b> yönlendirip yalnızca o sınıfın metotlarına bakıyor. Bu uç
 * {@code @Path("/api")} altında dursaydı, {@code /api/channels/{id}/planli-kayitlar}
 * isteği daha uzun eşleşen {@code ChannelResource}'a ({@code /api/channels})
 * düşer ve orada karşılığı olmadığı için "eşleşen metot yok" hatası verirdi.
 * Yaşandı: uç HTTP 500 döndü. Tam yolu sınıf düzeyinde yazmak çakışmayı
 * ortadan kaldırıyor — {@link ChannelClipResource} da aynı sebeple ayrı.
 */
@Path("/api/channels/{channelId}/planli-kayitlar")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Planlı kayıt", description = "Önceden verilen kayıt emirleri")
public class ChannelScheduledRecordingResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    ScheduledRecordingService service;

    @POST
    @Operation(summary = "Kayıt emri ver",
        description = "Aralık geçmişte, şu anda ya da gelecekte olabilir. "
            + "Gelecekteyse emir beklemeye alınır; kanalın geriye sarması "
            + "kapalıysa yalnızca o aralık boyunca açılır.")
    public Response create(@PathParam("channelId") UUID channelId,
                           @Valid CreateScheduledRecordingRequest request) {
        ScheduledRecordingDto plan = service.create(channelId, request, jwt.getSubject());
        // 202: emir kabul edildi, sonucu (klip) daha sonra olusacak.
        return Response.accepted(plan).build();
    }
}
