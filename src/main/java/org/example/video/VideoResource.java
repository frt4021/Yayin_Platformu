package org.example.video;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.example.exception.AppException;
import org.example.user.Roles;
import org.example.video.dto.CreateVideoRequest;
import org.example.video.dto.UpdateVideoRequest;
import org.example.video.dto.UploadTicket;
import org.example.video.dto.VideoDto;
import org.example.video.dto.VideoLinks;

import java.util.List;
import java.util.UUID;

/**
 * Video kütüphanesi.
 *
 * <p>Yetki kanallar ve radyolarla aynı çizgide: okuma giriş yapmış herkese
 * açık (kütüphane paylaşılmak için var), değiştirme yönetici ve moderatöre.
 * Kliplerden farklı olarak kayıt sahibine özel değil — klipler kayıt içeriği
 * barındırdığı için kapalıydı, kütüphane ise kurumsal bir arşiv.
 *
 * <p><b>Yükleme iki adımlı.</b> Dosya bu uçlardan geçmiyor: {@code POST}
 * imzalı bir adres verir, tarayıcı doğrudan nesne depolamasına yazar,
 * {@code /tamamlandi} bunu bildirir.
 */
@Path("/api/videos")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Videolar", description = "Video kütüphanesi")
public class VideoResource {

    @Inject
    JsonWebToken jwt;

    @Inject
    VideoService videoService;

    @GET
    @Operation(summary = "Videoları listele",
        description = "q ile başlıkta arama yapılır (büyük/küçük harf duyarsız).")
    public List<VideoDto> list(
        @QueryParam("q") String query,
        @QueryParam("offset") @DefaultValue("0") int offset,
        @QueryParam("limit") @DefaultValue("50") int limit) {
        // Ust sinir: limit=100000 gibi bir istek tum tabloyu bellege alirdi.
        return videoService.list(query, Math.max(0, offset), Math.clamp(limit, 1, 200));
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Video detayı")
    public VideoDto get(@PathParam("id") UUID id) {
        return videoService.get(id);
    }

    @GET
    @Path("/{id}/links")
    @Operation(summary = "İzleme, indirme ve küçük resim adresleri",
        description = "Süreli imzalı adresler döner. Yönlendirme yerine JSON: "
            + "tarayıcı CORS nedeniyle yönlendirme yanıtındaki Location başlığını okuyamıyor.")
    public VideoLinks links(@PathParam("id") UUID id) {
        return videoService.links(id);
    }

    @POST
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Yükleme başlat",
        description = "Kaydı YUKLENIYOR olarak açar ve imzalı bir PUT adresi döner. "
            + "Dosya bu uçtan geçmez; tarayıcı doğrudan nesne depolamasına yazar.")
    public Response startUpload(@Valid CreateVideoRequest request, @Context UriInfo uriInfo) {
        UploadTicket ticket = videoService.startUpload(request, jwt.getSubject());
        return Response
            .created(uriInfo.getAbsolutePathBuilder().path(ticket.videoId().toString()).build())
            .entity(ticket)
            .build();
    }

    @POST
    @Path("/{id}/tamamlandi")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Yüklemenin bittiğini bildir",
        description = "Nesne depolamada doğrulanır ve kayıt işlenmeye alınır. "
            + "Bu çağrı hiç gelmezse süpürücü aynı işi yapar; bildirim bir "
            + "hızlandırmadır, doğruluk kaynağı değil.")
    public VideoDto completeUpload(@PathParam("id") UUID id) {
        return videoService.completeUpload(id);
    }

    @POST
    @Path("/{id}/kucukresim")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Küçük resim olarak görsel yükle",
        description = "Videodan yakalanan hiçbir karenin uygun olmadığı durumlar için. "
            + "Görsel backend üzerinden geçer — video dosyasının aksine boyutu küçük.")
    public VideoDto uploadThumbnail(@PathParam("id") UUID id, @RestForm("dosya") FileUpload dosya) {
        if (dosya == null) {
            throw AppException.badRequest("Görsel dosyası gönderilmedi ('dosya' alanı).");
        }
        return videoService.uploadThumbnail(id, dosya.filePath(), dosya.contentType(), dosya.size());
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Videoyu güncelle",
        description = "Başlık, açıklama ve küçük resim anı. Küçük resim anı "
            + "değişirse kayıt yeniden işlenmeye alınır.")
    public VideoDto update(@PathParam("id") UUID id, @Valid UpdateVideoRequest request) {
        return videoService.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @Operation(summary = "Videoyu sil",
        description = "Kayıt, video dosyası ve küçük resim birlikte silinir.")
    public Response delete(@PathParam("id") UUID id) {
        videoService.delete(id);
        return Response.noContent().build();
    }
}
