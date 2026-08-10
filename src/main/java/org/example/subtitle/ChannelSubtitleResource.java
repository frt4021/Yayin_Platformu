package org.example.subtitle;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.subtitle.dto.SubtitleDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bir kanalın altyazıları.
 *
 * <p>Ayrı sınıf: JAX-RS isteği en iyi eşleşen kaynak sınıfına yönlendirip
 * yalnızca onun metotlarına bakıyor. {@code /api} altında dursaydı
 * {@code /api/channels/...} isteği {@code ChannelResource}'a düşer ve orada
 * karşılığı olmadığı için "eşleşen metot yok" hatası verirdi.
 */
@Path("/api/channels/{channelId}/altyazilar")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Altyazı", description = "Canlı ve geriye dönük altyazı")
public class ChannelSubtitleResource {

    @Inject
    SubtitleService service;

    /**
     * Verilen zaman aralığındaki altyazılar.
     *
     * <p>Zamanlar <b>mutlak</b>: oynatıcı kendi {@code playingDate()}
     * değerinden bir pencere kuruyor. Canlı yayında izleyici 6-12 saniye
     * geride olduğu için "şimdi"yi sormak yanlış olurdu.
     */
    @GET
    @Operation(summary = "Aralıktaki altyazılar",
        description = "Aralıkla KESİŞEN bölütler döner; pencereye taşan cümle de gösterilmeli.")
    public List<SubtitleDto> list(@PathParam("channelId") UUID channelId,
                                  @QueryParam("from") Instant from,
                                  @QueryParam("to") Instant to) {
        return service.araliktakiler(channelId, from, to);
    }
}
