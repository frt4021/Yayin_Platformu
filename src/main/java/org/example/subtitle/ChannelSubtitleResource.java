package org.example.subtitle;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
    SubtitleLagMetrics lag;

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

    /**
     * İzleyicinin ölçtüğü HLS gecikmesini bildirir.
     *
     * <h2>Neden gerekli</h2>
     * Altyazının yetişip yetişmediği şu koşula bağlı:
     * {@code üretim gecikmesi < HLS gecikmesi}. Sol taraf sunucuda ölçülüyor,
     * sağ taraf ise <b>bilinemiyor</b> — izleyicinin tamponuna ve ağına bağlı.
     * O yüzden bugüne kadar {@code altyazi.butce-ms} varsayımıyla
     * karşılaştırılıyordu; yani ölçülmüş bir sayı ile tahmin edilmiş bir sayı.
     *
     * <p>Bu uç sağ tarafı da ölçüye çeviriyor. Değer tarayıcıda
     * {@code Date.now() - playingDate()} ile bulunuyor.
     *
     * <p><b>Dakikada bir</b> gönderiliyor, her karede değil: eşleştirme 250
     * ms'de bir çalışıyor ve o sıklıkta istek atmak izleyici başına saniyede
     * dört istek ederdi.
     *
     * <p>Gövdesiz ve yan etkisi ölçümle sınırlı; hata durumunda çağıran
     * <b>yok saymalı</b> — altyazının kendisi buna bağlı değil.
     */
    @POST
    @Path("/hls-gecikme")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "İzleyicinin HLS gecikmesini bildir",
        description = "Kapsama ölçümünde bütçenin gerçek değeri olarak kullanılıyor. "
            + "Bildirilmezse altyazi.butce-ms varsayımına düşülüyor.")
    public void hlsGecikme(@PathParam("channelId") UUID channelId,
                           HlsGecikmeRequest request) {
        lag.hlsGecikmeBildir(channelId, request.ms());
    }

    /** @param ms izleyicinin canlı kenardan geride olma süresi */
    public record HlsGecikmeRequest(long ms) {
    }
}
