package org.example.etkinlik;

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

/**
 * Prometheus'un anlık sorgu (instant query) API'sine konuşan ince istemci.
 *
 * <p>Admin panelin "Genel Bakış" ekranında Postgres/Redis/MinIO/MediaMTX/
 * Triton için yalnızca erişilebilir mi (bkz. {@code AnalitikService}'teki
 * {@code *Sagligi()} metotları) değil, gerçek sayısal detay (bağlantı sayısı,
 * bellek kullanımı, gecikme vb.) göstermek için. Bu servislere ayrı ayrı
 * bağlanıp aynı detayı toplamak (Redis {@code INFO} ayrıştırma, MinIO admin
 * API'si gibi ekstra istemci kodu gerektirirdi) yerine, zaten kurulu
 * exporter'ların (bkz. {@code prometheus.yml}) ürettiği metrikler tek bir
 * yerden okunuyor.
 */
@ApplicationScoped
public class PrometheusClient {

    private static final Logger LOG = Logger.getLogger(PrometheusClient.class);

    @ConfigProperty(name = "prometheus.url")
    String baseUrl;

    @Inject
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    /**
     * @return sorgunun ilk sonucunun anlık değeri; veri yoksa ya da
     *         Prometheus'a ulaşılamıyorsa {@code null} — sessizce sıfır
     *         göstermek yerine (bkz. CLAUDE.md "ölçmeden sayı verme").
     */
    public Double anlikDeger(String promQl) {
        try {
            String url = baseUrl + "/api/v1/query?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode root = json.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                return null;
            }
            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return null;
            }
            return result.get(0).path("value").path(1).asDouble();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOG.debugf(e, "Prometheus sorgusu başarısız: %s", promQl);
            return null;
        }
    }
}
