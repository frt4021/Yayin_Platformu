package org.example.channel;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Kaynağı inceleyip MediaMTX'e verilecek adresi ve gerçek çözünürlüğü belirler.
 *
 * <p><b>Neden gerekli — ölçülen iki sorun:</b>
 *
 * <ol>
 *   <li><b>MediaMTX master playlist'te boğuluyor.</b> Kaynak olarak bir master
 *       playlist verildiğinde MediaMTX en yüksek bant genişlikli varyantı
 *       seçiyor. O varyantın segmentleri gohlslib'in ~4 MB'lik sınırını
 *       aşarsa yayın <b>hiç başlamıyor</b> ve tek belirti log'daki
 *       {@code max recorded size exceeded} satırı oluyor — kullanıcı hata
 *       görmüyor. Ölçüm: TRT 720p (3.01 MB/segment) çalışıyor, 1080p
 *       (4.29 MB/segment) düşüyor. Sınır <b>yapılandırılabilir değil</b>:
 *       {@code hlsSegmentMaxSize} 500M'ye çıkarıldığında da aynı hata alındı,
 *       çünkü o ayar HLS <i>sunucusunu</i> etkiliyor, kaynak okuyucusunu değil.</li>
 *   <li><b>Merdiven kaynağın üstüne çıkabiliyordu.</b> Kaynağın gerçek boyutu
 *       bilinmediği için doğrulanamıyordu.</li>
 * </ol>
 *
 * <p>Belirleyici olan bant genişliği değil <b>segment başına bayt</b>: DW'nin
 * 6.2 Mbps'lik 1080p yayını 2 saniyelik segmentlerle sorunsuz akıyor, TRT'nin
 * 6.9 Mbps'lik 1080p yayını 6 saniyelik segmentlerle düşüyor.
 */
@ApplicationScoped
public class
SourceProbe {

    private static final Logger LOG = Logger.getLogger(SourceProbe.class);

    /** Kaynağa bağlanma ve okuma süresi; kanal kaydetmeyi uzun süre bekletmemeli. */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** Segment süresi okunamazsa varsayılan. HLS'te en yaygın değer. */
    private static final int DEFAULT_TARGET_DURATION = 6;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @ConfigProperty(name = "channels.hls-max-segment-bytes")
    long maxSegmentBytes;

    /**
     * İnceleme sonucu.
     *
     * @param effectiveUrl MediaMTX'e yazılacak adres. Master playlist'ten bir
     *                     varyant seçildiyse onun mutlak adresi, aksi halde
     *                     girilen adresin kendisi.
     * @param width        tespit edilemediyse {@code null}
     * @param note         kullanıcıya gösterilecek bilgi; seçim yapıldıysa
     *                     neden yapıldığını anlatır
     */
    public record Result(String effectiveUrl, Integer width, Integer height, String note) {

        static Result unknown(String url) {
            return new Result(url, null, null, null);
        }
    }

    /**
     * Kaynağı inceler.
     *
     * <p><b>Hata fırlatmaz.</b> Kaynak o an erişilemiyorsa kanal yine
     * kaydedilebilmeli: yayın kaynakları geçici olarak düşer ve bu yüzden
     * kanal düzenlemeyi engellemek, arızayı çözmeyi imkânsızlaştırırdı.
     * Böyle durumlarda çözünürlük {@code null} döner ve merdiven doğrulaması
     * atlanır.
     */
    public Result probe(String sourceUrl) {
        if (sourceUrl == null || !sourceUrl.startsWith("http")) {
            // RTSP/SRT/UDP kaynaklarda playlist yok; cozunurluk ancak ffprobe
            // ile ogrenilebilirdi ve backend imajinda ffmpeg bulunmuyor.
            return Result.unknown(sourceUrl);
        }

        String body;
        try {
            body = fetch(sourceUrl);
        } catch (Exception e) {
            LOG.warnf("Kaynak incelenemedi, doğrulama atlanıyor: %s (%s)",
                sourceUrl, e.getMessage());
            return Result.unknown(sourceUrl);
        }

        if (!HlsPlaylist.isMaster(body)) {
            // Dogrudan medya playlist'i: varyant secimi yok. Cozunurluk
            // playlist'te yazmiyor, ancak segment cozulerek ogrenilebilirdi.
            return Result.unknown(sourceUrl);
        }

        List<HlsPlaylist.Variant> variants = HlsPlaylist.parseMaster(body);
        if (variants.isEmpty()) {
            return Result.unknown(sourceUrl);
        }

        HlsPlaylist.Variant chosen = null;
        HlsPlaylist.Variant highest = variants.get(0);

        // Varyantlar kaliteden dusuge sirali; sinira sigan ILK (yani en
        // yuksek) varyanti aliyoruz. Amac kaliteden gereksiz odun vermemek.
        for (HlsPlaylist.Variant variant : variants) {
            long estimate = variant.estimatedSegmentBytes(targetDurationOf(sourceUrl, variant));
            if (estimate <= maxSegmentBytes) {
                chosen = variant;
                break;
            }
            LOG.debugf("Varyant atlandı (segment ~%,d bayt > %,d): %dx%d",
                estimate, maxSegmentBytes, variant.width(), variant.height());
        }

        if (chosen == null) {
            // Hicbiri sigmadi: en dusugu deniyoruz. Kesin cozum degil ama
            // "hicbir sey yapma"dan iyi -- en azindan bir sansi var ve
            // kullanici sebebi notta goruyor.
            chosen = variants.get(variants.size() - 1);
            LOG.warnf("Hiçbir varyant segment sınırına sığmadı, en düşüğü seçildi: %dx%d",
                chosen.width(), chosen.height());
        }

        String url = HlsPlaylist.resolve(sourceUrl, chosen.uri());
        String note = chosen == highest
            ? null
            : String.format(
                "Kaynak %dx%d varyantına ayarlandı: en yüksek varyant (%dx%d) "
                    + "MediaMTX'in segment sınırını aşıyor ve yayın hiç başlamazdı.",
                chosen.width(), chosen.height(), highest.width(), highest.height());

        return new Result(
            url,
            chosen.width() > 0 ? chosen.width() : null,
            chosen.height() > 0 ? chosen.height() : null,
            note);
    }

    /**
     * Varyantın segment süresi. Playlist okunamazsa varsayılana düşülüyor —
     * tahmini biraz kaçırmak, incelemeyi tamamen bırakmaktan iyi.
     */
    private int targetDurationOf(String masterUrl, HlsPlaylist.Variant variant) {
        try {
            String body = fetch(HlsPlaylist.resolve(masterUrl, variant.uri()));
            Integer target = HlsPlaylist.parseTargetDuration(body);
            return target == null ? DEFAULT_TARGET_DURATION : target;
        } catch (Exception e) {
            LOG.debugf("Varyant playlist'i okunamadı, varsayılan segment süresi kullanılıyor: %s",
                e.getMessage());
            return DEFAULT_TARGET_DURATION;
        }
    }

    private String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }
}
