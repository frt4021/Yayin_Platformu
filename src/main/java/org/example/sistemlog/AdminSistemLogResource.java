package org.example.sistemlog;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.sistemlog.dto.SistemLogDto;
import org.example.user.Roles;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Admin panelin "Sistem Logları" ekranı — tüm konteynerlerin loglarını
 * Loki üzerinden çekip Türkçe, kullanıcı dostu mesajlara çevirir.
 *
 * <p>Diğer admin uçlarıyla aynı desen: sınıf düzeyi {@code @RolesAllowed}.
 */
@Path("/api/admin/sistem-loglari")
@RolesAllowed(Roles.YONETICI)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sistem Logları", description = "Tüm konteynerlerin Türkçeye yorumlanmış logları")
public class AdminSistemLogResource {

    /** Rutin gürültünün büyük kısmı yorumlama aşamasında süzülüyor; ham çekimi bu yüzden geniş tutuluyor. */
    private static final int CEKME_ORANI = 5;

    @Inject
    LokiClient loki;

    @GET
    @Operation(summary = "Sistem logları",
        description = "Loki'den son 24 saatin loglarını çeker, bilinen örüntülere göre Türkçe "
            + "yorumlar (bkz. SistemLogYorumlayici); rutin gürültü (health-check tekrarları, "
            + "erişim logları vb.) hiç dönmez.")
    public List<SistemLogDto> listele(
            @QueryParam("servis") String servis,
            @QueryParam("seviye") String seviye,
            @QueryParam("limit") Integer limit) {
        int gercekLimit = limit == null ? 200 : Math.min(limit, 1000);
        String logQl = servis == null || servis.isBlank()
            ? "{servis=~\".+\"}"
            : "{servis=\"" + servis.replace("\"", "") + "\"}";

        return loki.sorgula(logQl, Duration.ofHours(24), gercekLimit * CEKME_ORANI).stream()
            .map(satir -> SistemLogYorumlayici.yorumla(satir.servis(), satir.zaman(), satir.mesaj()))
            .filter(Objects::nonNull)
            .filter(dto -> seviye == null || seviye.isBlank() || dto.seviye().equalsIgnoreCase(seviye))
            .sorted((a, b) -> b.zaman().compareTo(a.zaman()))
            .limit(gercekLimit)
            .toList();
    }
}
