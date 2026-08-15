package org.example.video;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import org.example.user.Roles;
import io.quarkus.security.identity.SecurityIdentity;
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
import org.example.etkinlik.EtkinlikService;
import org.example.etkinlik.EtkinlikTuru;
import org.example.exception.AppException;
import org.example.video.dto.CreateVideoRequest;
import org.example.video.dto.UpdateVideoRequest;
import org.example.video.dto.UploadTicket;
import org.example.video.dto.VideoDto;
import org.example.video.dto.VideoLinks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Video kütüphanesi.
 *
 * <p><b>Kütüphane kişiseldir.</b> Kullanıcı yalnızca kendi yüklediği
 * videoları görür, açar, düzenler ve siler; <b>yönetici tümünü</b> görür ve
 * her kaydın kimin yüklediğini ({@code uploadedBy}) okuyabilir. Kliplerdeki
 * kuralın aynısı.
 *
 * <p><b>İzleyici video yükleyemez</b> — yalnızca görür. Okuma uçları giriş
 * yapmış herkese açık; yükleme, düzenleme ve silme {@code Yönetici} ile
 * {@code Moderatör}'e kısıtlı. Sahiplik kuralı bunun üstünde ayrıca geçerli:
 * yetkili roller de yalnızca kendi videolarını görür, yönetici tümünü.
 *
 * <p>Moderatör yönetici sayılmıyor: kanal ve radyo yönetebilir ama başkasının
 * kütüphanesini göremez.
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
    SecurityIdentity identity;

    @Inject
    VideoService videoService;

    @Inject
    EtkinlikService etkinlikService;

    /** Yönetici tüm kütüphaneyi görür; moderatör dahil diğerleri kendininkini. */
    private boolean isAdmin() {
        return VideoService.isAdmin(identity.getRoles());
    }

    @GET
    @Operation(summary = "Videoları listele",
        description = "Yönetici tümünü, diğerleri yalnızca kendi yüklediklerini görür. "
            + "q ile başlıkta arama yapılır (büyük/küçük harf duyarsız).")
    public List<VideoDto> list(
        @QueryParam("q") String query,
        @QueryParam("offset") @DefaultValue("0") int offset,
        @QueryParam("limit") @DefaultValue("50") int limit) {
        // Ust sinir: limit=100000 gibi bir istek tum tabloyu bellege alirdi.
        return videoService.list(query, Math.max(0, offset), Math.clamp(limit, 1, 200),
            jwt.getSubject(), isAdmin());
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Video detayı")
    public VideoDto get(@PathParam("id") UUID id) {
        return videoService.get(id, jwt.getSubject(), isAdmin());
    }

    @GET
    @Path("/{id}/links")
    @Operation(summary = "İzleme, indirme ve küçük resim adresleri",
        description = "Süreli imzalı adresler döner. Yönlendirme yerine JSON: "
            + "tarayıcı CORS nedeniyle yönlendirme yanıtındaki Location başlığını okuyamıyor.")
    public VideoLinks links(@PathParam("id") UUID id) {
        return videoService.links(id, jwt.getSubject(), isAdmin());
    }

    @POST
    @Path("/{id}/izleme-basladi")
    @Operation(summary = "Gerçek oynatma başlangıcını bildir",
        description = "viewCount /links çağrıldığında (izleme NİYETİ) artıyor; bu, "
            + "tarayıcının video elementinin gerçekten oynatmaya başladığı andır. "
            + "Kullanıcı davranışı denetim izi içindir.")
    public void izlemeBasladi(@PathParam("id") UUID id) {
        etkinlikService.kaydet(EtkinlikTuru.VIDEO_IZLEME_BASLADI, jwt.getSubject(), "video", id, Map.of());
    }

    @POST
    @Path("/{id}/izleme-ozeti")
    @Operation(summary = "İzleme oturumu özetini bildir",
        description = "Tamamlanma oranı ve kaba (10 dilim) tekrar-izleme ısı haritası için. "
            + "İstemci yalnızca kendi oturumunun özetini bildirir; oynatıcının akışını etkilemez.")
    public void izlemeOzeti(@PathParam("id") UUID id, IzlemeOzetiRequest request) {
        List<Integer> dilimler = request.ziyaretEdilenDilimler() == null ? List.of()
            : request.ziyaretEdilenDilimler().stream()
                .filter(d -> d != null && d >= 0 && d <= 9)
                .distinct()
                .sorted()
                .toList();
        etkinlikService.kaydet(EtkinlikTuru.VIDEO_IZLEME_BITTI, jwt.getSubject(), "video", id, Map.of(
            "ziyaretEdilenDilimler", dilimler,
            "tamamlandi", request.tamamlandi(),
            "duraklatmaSayisi", Math.max(0, request.duraklatmaSayisi()),
            "sarmaSayisi", Math.max(0, request.sarmaSayisi()),
            "sureMs", Math.max(0, request.sureMs())));
    }

    public record IzlemeOzetiRequest(List<Integer> ziyaretEdilenDilimler, boolean tamamlandi,
                                      int duraklatmaSayisi, int sarmaSayisi, long sureMs) {
    }

    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @POST
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

    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @POST
    @Path("/{id}/tamamlandi")
    @Operation(summary = "Yüklemenin bittiğini bildir",
        description = "Nesne depolamada doğrulanır ve kayıt işlenmeye alınır. "
            + "Bu çağrı hiç gelmezse süpürücü aynı işi yapar; bildirim bir "
            + "hızlandırmadır, doğruluk kaynağı değil.")
    public VideoDto completeUpload(@PathParam("id") UUID id) {
        VideoDto video = videoService.completeUpload(id, jwt.getSubject(), isAdmin());
        etkinlikService.kaydet(EtkinlikTuru.VIDEO_YUKLENDI, jwt.getSubject(), "video", id,
            Map.of("baslik", video.title()));
        return video;
    }

    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @POST
    @Path("/{id}/kucukresim")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Küçük resim olarak görsel yükle",
        description = "Videodan yakalanan hiçbir karenin uygun olmadığı durumlar için. "
            + "Görsel backend üzerinden geçer — video dosyasının aksine boyutu küçük.")
    public VideoDto uploadThumbnail(@PathParam("id") UUID id, @RestForm("dosya") FileUpload dosya) {
        if (dosya == null) {
            throw AppException.badRequest("Görsel dosyası gönderilmedi ('dosya' alanı).");
        }
        return videoService.uploadThumbnail(id, dosya.filePath(), dosya.contentType(),
            dosya.size(), jwt.getSubject(), isAdmin());
    }

    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @PUT
    @Path("/{id}")
    @Operation(summary = "Videoyu güncelle",
        description = "Başlık, açıklama ve küçük resim anı. Küçük resim anı "
            + "değişirse kayıt yeniden işlenmeye alınır.")
    public VideoDto update(@PathParam("id") UUID id, @Valid UpdateVideoRequest request) {
        return videoService.update(id, request, jwt.getSubject(), isAdmin());
    }

    @RolesAllowed({Roles.YONETICI, Roles.MODERATOR})
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Videoyu sil",
        description = "Kayıt, video dosyası ve küçük resim birlikte silinir.")
    public Response delete(@PathParam("id") UUID id) {
        String baslik = videoService.get(id, jwt.getSubject(), isAdmin()).title();
        videoService.delete(id, jwt.getSubject(), isAdmin());
        etkinlikService.kaydet(EtkinlikTuru.VIDEO_SILINDI, jwt.getSubject(), "video", id,
            Map.of("baslik", baslik));
        return Response.noContent().build();
    }
}
