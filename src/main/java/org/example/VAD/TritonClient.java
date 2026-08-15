package org.example.VAD;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Triton Inference Server'a (KServe v2 HTTP protokolü) konuşan istemci.
 *
 * <p>{@code SttClient}'ın (eski stt-worker'a özel {@code /transcribe}
 * endpoint'i) yerini alıyor. Triton'un kendi sabit
 * {@code /v2/models/<isim>/infer} sözleşmesi var — özel bir endpoint
 * yazamıyoruz, bu yüzden istek/yanıt şekli SttClient'tan farklı ve dört
 * ayrı model (whisper + 3 marian) dört ayrı çağrı gerektiriyor.
 */
@ApplicationScoped
public class TritonClient {

    private static final Logger LOG = Logger.getLogger(TritonClient.class);

    @ConfigProperty(name = "triton.url")
    String baseUrl;

    @Inject
    ObjectMapper json;

    /** stt.py'deki gerekçe aynı: çözümleme uzun sürebilir, en kötü duruma göre geniş tutuluyor. */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /**
     * HTTP/1.1 — SttClient'taki uvicorn/h11 bug'ı (HTTP/2 upgrade'inde POST
     * gövdesinin düşmesi) buraya KÖRÜ KÖRÜNE taşındı, TEST EDİLMEDİ. Triton
     * kendi C++ sunucusu; aynı bug'ın orada da olacağı varsayılmamalı
     * (docs/triton geçiş planı, Bölüm 5.1) — ilk canlı testte bu satır
     * kaldırılıp doğrulanmalı.
     */
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public record TranscribeResult(String pivotText, String sourceLanguage, Float confidence) {}

    /**
     * Triton'un hazır olup olmadığını sorar — admin panelin "Sistem Sağlığı"
     * özeti için. Kısa zaman aşımı kasıtlı: burada 120sn beklemek servisi
     * çökmüş gösterirdi, oysa yalnızca yavaştır.
     */
    public boolean saglikliMi() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v2/health/ready"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Bir ses bölütünü {@code whisper}'a gönderir.
     *
     * <p>Binary-data-extension kullanılıyor: JSON sadece metadata taşıyor,
     * ham PCM baytları gövdenin sonuna DÜZ ekleniyor — base64 %33 şişmesini
     * önlemek için (SttClient'taki ayrı aynı hedef).
     *
     * @return servisin ayrıştırılmış yanıtı, ya da başarısızsa {@code null}
     */
    public TranscribeResult transcribe(SpeechSegment segment) {
        try {
            byte[] pcm = segment.pcm();
            Map<String, Object> header = Map.of(
                "inputs", List.of(Map.of(
                    "name", "PCM_AUDIO",
                    "shape", List.of(1, pcm.length / 2),
                    "datatype", "INT16",
                    "parameters", Map.of("binary_data_size", pcm.length)
                )),
                "outputs", List.of(
                    Map.of("name", "PIVOT_TEXT"),
                    Map.of("name", "SOURCE_LANGUAGE"),
                    Map.of("name", "LANGUAGE_CONFIDENCE")
                )
            );
            byte[] headerBytes = json.writeValueAsBytes(header);
            byte[] body = ByteBuffer.allocate(headerBytes.length + pcm.length)
                .put(headerBytes).put(pcm).array();

            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(baseUrl + "/v2/models/whisper/infer"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .header("Inference-Header-Content-Length", String.valueOf(headerBytes.length))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Triton (whisper) reddetti (HTTP %d): %s — %s",
                    response.statusCode(), segment.channelName(), kisalt(response.body()));
                return null;
            }
            return parseTranscribe(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Tek bir bolutun kaybi hatti durdurmamali; Triton gecici olarak
            // erisilemez olabilir.
            LOG.warnf("Triton'a (whisper) ulaşılamadı: %s — %s",
                segment.channelName(), e.getMessage());
            return null;
        }
    }

    private TranscribeResult parseTranscribe(String responseBody) throws Exception {
        JsonNode outputs = json.readTree(responseBody).path("outputs");
        String pivotText = "";
        String sourceLanguage = null;
        Float confidence = null;
        for (JsonNode output : outputs) {
            JsonNode data = output.path("data");
            switch (output.path("name").asText()) {
                case "PIVOT_TEXT" -> pivotText = data.path(0).asText("");
                case "SOURCE_LANGUAGE" -> sourceLanguage = data.path(0).asText(null);
                case "LANGUAGE_CONFIDENCE" -> confidence = (float) data.path(0).asDouble(0);
            }
        }
        return new TranscribeResult(pivotText, sourceLanguage, confidence);
    }

    /**
     * İngilizce pivot metni bir hedef dile çevirir.
     *
     * @param model Triton model adı, örn. {@code "marian_en_tr"}
     * @return çevrilmiş metin, ya da başarısızsa {@code null}
     */
    public String translate(String model, String text) {
        try {
            Map<String, Object> requestBody = Map.of(
                "inputs", List.of(Map.of(
                    "name", "SOURCE_TEXT",
                    // [1, 1]: max_batch_size>0 oldugu icin Triton, config.pbtxt'teki
                    // dims:[1]'in ONUNE batch boyutunu da istiyor -- yalnizca [1]
                    // gonderilince "Expected [-1,1], got [1]" ile reddediyordu
                    // (whisper'daki 3-vs-4 boyut hatasiyla ayni kok neden).
                    "shape", List.of(1, 1),
                    "datatype", "BYTES",
                    "data", List.of(text)
                )),
                "outputs", List.of(Map.of("name", "TRANSLATED_TEXT"))
            );

            HttpRequest request = HttpRequest.newBuilder(
                    URI.create(baseUrl + "/v2/models/" + model + "/infer"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(requestBody)))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Triton (%s) reddetti (HTTP %d): %s",
                    model, response.statusCode(), kisalt(response.body()));
                return null;
            }

            for (JsonNode output : json.readTree(response.body()).path("outputs")) {
                if ("TRANSLATED_TEXT".equals(output.path("name").asText())) {
                    return output.path("data").path(0).asText("");
                }
            }
            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Tek bir dilin cevirisinin kaybi diger dilleri/hatti durdurmamali.
            LOG.warnf("Triton'a (%s) ulaşılamadı: %s", model, e.getMessage());
            return null;
        }
    }

    private static String kisalt(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
