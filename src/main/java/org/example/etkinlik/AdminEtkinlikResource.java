package org.example.etkinlik;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.etkinlik.dto.EtkinlikSayfasiDto;
import org.example.user.Roles;

import java.time.Instant;
import java.util.UUID;

/**
 * Yönetici kullanıcı davranışı denetim izi görüntüleme. Sınıf düzeyindeki
 * {@code @RolesAllowed} tüm uçları kapsar — {@code AdminUserResource} ile
 * aynı desen.
 */
@Path("/api/admin/etkinlikler")
@RolesAllowed(Roles.YONETICI)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Kullanıcı Etkinliği", description = "Yöneticiye özel denetim izi")
public class AdminEtkinlikResource {

    @Inject
    EtkinlikService etkinlikService;

    @GET
    @Operation(summary = "Etkinlik kayıtlarını listele",
        description = "Tür, kullanıcı ve tarih aralığına göre filtrelenebilir, en yeni önce.")
    public EtkinlikSayfasiDto list(
        @QueryParam("tur") EtkinlikTuru tur,
        @QueryParam("kullaniciId") UUID kullaniciId,
        @QueryParam("kullaniciAdi") String kullaniciAdi,
        @QueryParam("baslangic") Instant baslangic,
        @QueryParam("bitis") Instant bitis,
        @QueryParam("first") @DefaultValue("0") @Min(0) int first,
        @QueryParam("max") @DefaultValue("50") @Min(1) @Max(200) int max) {
        return etkinlikService.ara(tur, kullaniciId, kullaniciAdi, baslangic, bitis, first, max);
    }
}
