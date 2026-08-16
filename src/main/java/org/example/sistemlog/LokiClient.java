package org.example.sistemlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loki'nin sorgu API'sine konuşan ince istemci — {@code PrometheusClient}
 * ile aynı desen. Backend Promtail'in aksine {@code docker.sock}'a hiçbir
 * zaman dokunmuyor; yalnızca Loki'nin HTTP API'sine (LogQL) sorgu atıyor.
 */
@ApplicationScoped
public class LokiClient {

    private static final Logger LOG = Logger.getLogger(LokiClient.class);

    @ConfigProperty(name = "loki.url")
    String baseUrl;

    @Inject
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    public record LogSatiri(String servis, Instant zaman, String mesaj) {
    }

    /**
     * @param logQl LogQL sorgusu, örn. {@code {servis=~".+"}}
     * @return sorgu başarısızsa boş liste — log altyapısının kendisi
     *         çökse bile admin panelin geri kalanı etkilenmemeli
     *         (Prometheus/Triton istemcileriyle aynı tolerans ilkesi)
     */
    public List<LogSatiri> sorgula(String logQl, Duration sure, int limit) {
        try {
            Instant simdi = Instant.now();
            Instant baslangic = simdi.minus(sure);
            String url = baseUrl + "/loki/api/v1/query_range"
                + "?query=" + URLEncoder.encode(logQl, StandardCharsets.UTF_8)
                + "&start=" + (baslangic.getEpochSecond() * 1_000_000_000L)
                + "&end=" + (simdi.getEpochSecond() * 1_000_000_000L)
                + "&limit=" + limit
                + "&direction=backward";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.debugf(e, "Loki sorgusu başarısız: %s", logQl);
            return List.of();
        }
    }

    private List<LogSatiri> parse(String body) throws Exception {
        List<LogSatiri> sonuc = new ArrayList<>();
        JsonNode root = json.readTree(body);
        if (!"success".equals(root.path("status").asText())) {
            return sonuc;
        }
        for (JsonNode akis : root.path("data").path("result")) {
            String servis = akis.path("stream").path("servis").asText("bilinmeyen");
            for (JsonNode giris : akis.path("values")) {
                // Her giris [nanosaniye-string, log-metni] cifti.
                if (!giris.isArray() || giris.size() < 2) {
                    continue;
                }
                long nanos;
                try {
                    nanos = Long.parseLong(giris.get(0).asText());
                } catch (NumberFormatException e) {
                    continue;
                }
                Instant zaman = Instant.ofEpochSecond(0, nanos);
                sonuc.add(new LogSatiri(servis, zaman, giris.get(1).asText()));
            }
        }
        return sonuc;
    }
}
