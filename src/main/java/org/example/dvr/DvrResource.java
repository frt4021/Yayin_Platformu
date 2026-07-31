package org.example.dvr;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.dvr.dto.TimelineSpan;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Geriye sarma uçları.
 *
 * <p>Okuma giriş yapmış herkese açık — izleyici de geçmişe gidebilmeli.
 * MediaMTX'in playback sunucusu dışarı kapalı; buradan geçmek zorunlu ve
 * yetki kontrolü burada yapılıyor.
 */
@Path("/api/channels/{channelId}/dvr")
@Authenticated
@Tag(name = "Geriye Sarma", description = "Kayıt zaman çizelgesi ve geçmiş oynatma")
public class DvrResource {

    @Inject
    DvrService dvrService;

    @GET
    @Path("/timeline")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Kayıt zaman çizelgesi",
        description = "Verilen aralıkta kayıt bulunan bölümler. Boşluklar ayrı aralık olarak döner.")
    public List<TimelineSpan> timeline(
        @PathParam("channelId") UUID channelId,
        @QueryParam("from") String from,
        @QueryParam("to") String to) {
        return dvrService.timeline(channelId, Instant.parse(from), Instant.parse(to));
    }

    @GET
    @Path("/stream")
    @Operation(summary = "Geçmişten oynat",
        description = "Belirtilen andan itibaren verilen süre kadar kaydı akış olarak döndürür.")
    public Response stream(
        @PathParam("channelId") UUID channelId,
        @QueryParam("start") String start,
        @QueryParam("duration") long durationSeconds) {

        Response upstream = dvrService.stream(
            channelId, Instant.parse(start), Duration.ofSeconds(durationSeconds), "fmp4");

        // Gövde akış halinde aktarılıyor; belleğe toplanmıyor.
        return Response.ok(upstream.getEntity())
            .type(upstream.getMediaType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_TYPE
                : upstream.getMediaType())
            .build();
    }
}
