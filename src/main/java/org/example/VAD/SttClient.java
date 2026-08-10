package org.example.VAD;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/**
 * STT servisine bölüt gönderen istemci.
 *
 * <p>Ham PCM gövdede: base64 %33 şişirirdi, çok parçalı form gereksiz
 * ayrıştırma getirirdi. Bölüt ortalama 440 KB.
 *
 * <p>JDK'nın kendi {@code HttpClient}'ı kullanılıyor — tek bir POST için REST
 * istemci altyapısı kurmak fazlaydı.
 */
@ApplicationScoped
public class SttClient {

    private static final Logger LOG = Logger.getLogger(SttClient.class);

    @ConfigProperty(name = "stt.url")
    String baseUrl;

    /**
     * Çözümleme uzun sürebilir: 25 saniyelik bir bölüt CPU'da `small` modelle
     * ~6,5 saniye (ölçüldü). GPU'da çok daha hızlı ama zaman aşımı en kötü
     * duruma göre olmalı — erken kesmek işi boşa harcamak demek.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /**
     * HTTP/1.1 <b>zorunlu</b>.
     *
     * <p>JDK istemcisi varsayılan olarak HTTP/2 deniyor ve şifresiz bağlantıda
     * bunu {@code Upgrade} başlığıyla yapıyor. uvicorn (h11) yalnızca HTTP/1.1
     * konuşuyor ve bu el sıkışmada <b>POST gövdesi düşüyor</b>: sunucuya boş
     * gövde ulaşıyor, hata da vermiyor. Yaşandı — STT her bölüte
     * {@code 400 Boş gövde} döndü, oysa bölütler 380 KB'ydi ve aynı veri
     * curl ile sorunsuz gidiyordu.
     */
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /**
     * Bölütü çözümletir.
     *
     * @return servisin JSON yanıtı, ya da başarısızsa {@code null}
     */
    public String transcribe(SpeechSegment segment) {
        String url = baseUrl + "/transcribe"
            + "?channel=" + enc(segment.channelName())
            + "&start=" + enc(DateTimeFormatter.ISO_INSTANT.format(segment.startedAt()))
            + "&end=" + enc(DateTimeFormatter.ISO_INSTANT.format(segment.endedAt()));

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(segment.pcm()))
                .build();

            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warnf("STT reddetti (HTTP %d): %s — %s",
                    response.statusCode(), segment.channelName(),
                    kisalt(response.body()));
                return null;
            }
            return response.body();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Tek bir bolutun kaybi hatti durdurmamali; STT servisi gecici
            // olarak erisilemez olabilir.
            LOG.warnf("STT'ye ulaşılamadı: %s — %s", segment.channelName(), e.getMessage());
            return null;
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String kisalt(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
