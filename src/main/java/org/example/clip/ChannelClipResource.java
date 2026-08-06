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
import org.example.clip.dto.ActiveRecordingDto;
import org.example.clip.dto.ClipDto;
import org.example.clip.dto.CreateClipRequest;

import java.util.UUID;

/**
 * Bir kanaldan klip çıkarma.
 *
 * <p>Ayrı bir sınıf olmasının sebebi JAX-RS'in yönlendirme kuralı: istek
 * <b>en iyi eşleşen kaynak sınıfına</b> gider ve yalnızca o sınıfın
 * metotlarına bakılır. Bu uç {@code @Path("/api")} altındaki bir sınıfta
 * dursaydı, {@code /api/channels/{id}/clips} isteği daha uzun eşleşen
 * {@code ChannelResource}'a ({@code /api/channels}) düşer ve orada karşılığı
 * olmadığı için "eşleşen metot yok" hatası verirdi. Tam yolu sınıf düzeyinde
 * yazmak bu çakışmayı ortadan kaldırıyor.
 */
@Path("/api/channels/{channelId}/clips")
// Giris yapmis herkes KENDI ADINA klip alabilir. Rol kisiti kaldirildi
// cunku tutarsizdi: izleyici geriye sarmayla ayni kayit icerigini zaten
// izleyebiliyordu ama ondan klip alamiyordu -- kapi kilitli, pencere acikti.
// Uretilen klip sahibine ozel kalir; goruntuleme kurali ClipService'te.
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Klipler", description = "Kayıttan klip çıkarma")
public class ChannelClipResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    ClipService clipService;

    @Inject
    RecordingService recordingService;

    @POST
    @Operation(summary = "Klip oluştur",
        description = "İş kuyruğa alınır ve arka planda üretilir; yanıt hemen döner.")
    public Response create(@PathParam("channelId") UUID channelId,
                           @Valid CreateClipRequest request) {
        ClipDto clip = clipService.create(channelId, request, jwt.getSubject());
        // 202: kabul edildi ama tamamlanmadı. 201 yanıltıcı olurdu — dosya henüz yok.
        return Response.accepted(clip).build();
    }

    @POST
    @jakarta.ws.rs.Path("/kayit")
    @Operation(summary = "Kayda başla",
        description = "Başlangıç anı sunucuda saklanır. Yeni bir kayıt mekanizması "
            + "değil: DVR zaten kaydediyor, burada yalnızca aralığın başı işaretleniyor.")
    public ActiveRecordingDto startRecording(@PathParam("channelId") UUID channelId) {
        return recordingService.start(channelId, jwt.getSubject());
    }

    @jakarta.ws.rs.DELETE
    @jakarta.ws.rs.Path("/kayit")
    @Operation(summary = "Kaydı durdur",
        description = "Bitiş anı sunucuda belirlenir ve aralık için klip işi açılır. "
            + "Yanıt 202: iş kuyruğa alındı, dosya henüz yok.")
    public Response stopRecording(@PathParam("channelId") UUID channelId) {
        return Response.accepted(recordingService.stop(channelId, jwt.getSubject())).build();
    }
}
