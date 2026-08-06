package org.example.screenshot;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.exception.AppException;
import org.example.screenshot.dto.ScreenshotDto;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Ekran görüntüsü galerisi.
 *
 * <p>Kare <b>tarayıcıda</b> yakalanıp buraya yükleniyor; sunucu tarafı
 * yakalama yok. Gerekçesi {@link ScreenshotService} javadoc'unda: ffmpeg
 * yalnızca işçi konteynerinde ve HLS gecikmesi yüzünden sunucudan yakalanan
 * kare kullanıcının gördüğü kare olmazdı.
 *
 * <p>Giriş yapmış herkes kendi adına kare yakalar ve yalnızca kendi
 * karelerini görür; yönetici tümünü görür.
 */
@Path("/api/screenshots")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Ekran görüntüleri", description = "Canlı yayından kare yakalama ve galeri")
public class ScreenshotResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity identity;

    @Inject
    ScreenshotService screenshotService;

    private boolean isAdmin() {
        return ScreenshotService.isAdmin(identity.getRoles());
    }

    @GET
    @Operation(summary = "Galeri",
        description = "Kronolojik, yeniden eskiye. Yönetici tümünü, diğerleri "
            + "yalnızca kendi karelerini görür.")
    public List<ScreenshotDto> gallery(
        @QueryParam("channelId") UUID channelId,
        @QueryParam("offset") @DefaultValue("0") int offset,
        @QueryParam("limit") @DefaultValue("60") int limit) {
        // Ust sinir: limit=100000 gibi bir istek tum tabloyu bellege alirdi.
        return screenshotService.gallery(channelId, Math.max(0, offset),
            Math.clamp(limit, 1, 200), jwt.getSubject(), isAdmin());
    }

    @POST
    @Path("/{channelId}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Kare yakala",
        description = "Tarayıcıda yakalanmış görseli kaydeder. capturedAt, kullanıcının "
            + "izlediği ANI bildirir — HLS gecikmesi nedeniyle bu 'şu an'dan farklıdır.")
    public Response capture(
        @PathParam("channelId") UUID channelId,
        @RestForm("dosya") FileUpload dosya,
        @RestForm("capturedAt") String capturedAt,
        @RestForm("note") String note,
        @RestForm("width") Integer width,
        @RestForm("height") Integer height) {

        if (dosya == null) {
            throw AppException.badRequest("Görsel gönderilmedi ('dosya' alanı).");
        }
        ScreenshotDto shot = screenshotService.capture(
            channelId, parseInstant(capturedAt), note,
            dosya.filePath(), dosya.contentType(), dosya.size(),
            width, height, jwt.getSubject());
        return Response.status(Response.Status.CREATED).entity(shot).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Ekran görüntüsünü sil")
    public Response delete(@PathParam("id") UUID id) {
        screenshotService.delete(id, jwt.getSubject(), isAdmin());
        return Response.noContent().build();
    }

    /**
     * Bozuk zaman istemcinin hatası; kaydı tamamen reddetmek yerine sunucu
     * saatine düşülüyor — kare zaten yakalanmış, kaybetmek daha kötü.
     */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
