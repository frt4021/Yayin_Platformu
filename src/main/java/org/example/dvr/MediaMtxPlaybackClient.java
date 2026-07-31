package org.example.dvr;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.example.dvr.dto.RecordingSpan;

import java.util.List;

/**
 * MediaMTX'in geri sarma (playback) sunucusu.
 *
 * <p>Kayıtlı segmentleri okuyup birleştirerek sunar. Kendi segment
 * indeksimizi ve ffmpeg kesme katmanımızı yazmamızı gereksiz kılan uç budur.
 *
 * <p><b>Bu sunucu dışarı açılmaz.</b> Yetkilendirme backend'de yapılır;
 * doğrudan erişilebilir olsaydı kayıtlara kimlik doğrulamasız ulaşılırdı.
 */
@Path("/")
@RegisterRestClient(configKey = "mediamtx-playback")
public interface
MediaMtxPlaybackClient {

    /**
     * Bir path için kayıt bulunan zaman aralıkları.
     *
     * <p>Bitişik segmentler tek aralık olarak birleşir; kayıt boşlukları
     * ayrı aralık olarak görünür. Zaman çizelgesindeki "bu saatte kayıt var
     * mı" bilgisi doğrudan buradan gelir.
     */
    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    List<RecordingSpan> list(@QueryParam("path") String path);

    /**
     * Belirtilen andan itibaren verilen süre kadar kaydı döndürür.
     *
     * <p>Yanıt akış halinde geldiği için {@link Response} olarak alınıyor;
     * gövdeyi belleğe toplamadan doğrudan aktarabilmek gerekiyor. Saatlik
     * bir klip 6 Mbps'te ~2.7 GB eder.
     *
     * @param start ISO-8601, UTC (ör. {@code 2026-07-31T10:34:38.125248Z})
     * @param format {@code mp4} indirilebilir dosya, {@code fmp4} akış için
     */
    @GET
    @Path("/get")
    Response get(@QueryParam("path") String path,
                 @QueryParam("start") String start,
                 @QueryParam("duration") double durationSeconds,
                 @QueryParam("format") String format);
}
