package org.example.channel;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.entity.Channel;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kanal kartlarında gösterilecek ön izleme karelerini periyodik yakalar.
 *
 * <h2>Neden yalnızca video-worker'da</h2>
 * <b>ffmpeg gerektirir</b>; backend imajında yok. Aynı gerekçe
 * {@link org.example.dvr.DvrRecorder} için de geçerli — bayrak
 * ({@code channels.snapshot-enabled}) unutulursa iş iki konteynerde de
 * denenir ama ffmpeg olmayan tarafta sessizce hata verip loglanır.
 *
 * <h2>Neden doğrudan RTSP</h2>
 * HLS'in aksine kaynak path'i her zaman açık ({@code alwaysOn}); MediaMTX'in
 * isteğe bağlı HLS muxer'ını (izleyici yoksa kapalı) tetiklemez. Her yakalama
 * tek karelik, kısa ömürlü bir bağlantı — {@link org.example.dvr.DvrRecorder}
 * gibi sürekli açık tutulmuyor.
 */
@ApplicationScoped
public class ChannelSnapshotWorker {

    private static final Logger LOG = Logger.getLogger(ChannelSnapshotWorker.class);

    @Inject
    MediaMtxService mediaMtx;

    @Inject
    ChannelSnapshotStorage storage;

    @ConfigProperty(name = "channels.snapshot-enabled")
    boolean enabled;

    @ConfigProperty(name = "mediamtx.rtsp-url")
    String rtspBase;

    @ConfigProperty(name = "channels.snapshot-width")
    int width;

    @ConfigProperty(name = "channels.snapshot-timeout-seconds")
    int timeoutSeconds;

    /**
     * {@code SKIP}: bir tur MediaMTX'in yavaş cevabı ya da çok kanal yüzünden
     * uzarsa bir sonraki tur beklenir — aynı kanal için iki ffmpeg süreci
     * aynı anda başlamaz.
     */
    @Scheduled(every = "{channels.snapshot-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void yakala() {
        if (!enabled) {
            return;
        }

        Map<String, org.example.channel.dto.MediaMtxPathList.Item> states = mediaMtx.pathStates();
        if (states.isEmpty()) {
            // MediaMTX'e ulasilamiyor; bu turu atla, bir sonrakinde tekrar dene.
            return;
        }

        for (Channel channel : Channel.<Channel>listActive()) {
            var state = states.get(channel.mediamtxPath);
            if (state == null || !state.ready()) {
                continue;
            }
            try {
                byte[] jpeg = kareCek(channel.mediamtxPath);
                storage.put(channel.mediamtxPath, jpeg);
            } catch (Exception e) {
                // Bir kanalin karesi alinamamasi digerlerini engellemez.
                LOG.debugf("Kanal ön izlemesi alınamadı (%s): %s", channel.mediamtxPath, e.getMessage());
            }
        }
    }

    private byte[] kareCek(String mediamtxPath) throws IOException, InterruptedException {
        Path out = Files.createTempFile("kanal-onizleme", ".jpg");
        try {
            List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-rtsp_transport", "tcp",
                "-i", rtspBase + "/" + mediamtxPath,
                "-frames:v", "1",
                // -2: yukseklik en-boy oranina gore hesaplansin ve cift sayi olsun.
                "-vf", "scale=" + width + ":-2",
                "-q:v", "4",
                "-y", out.toString());

            Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String stderr;
            try (var stdout = process.getInputStream(); var stderrStream = process.getErrorStream()) {
                stdout.readAllBytes();
                stderr = new String(stderrStream.readAllBytes());
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg zaman aşımına uğradı");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg başarısız (kod " + process.exitValue() + "): " + stderr.strip());
            }

            byte[] bytes = Files.readAllBytes(out);
            if (bytes.length == 0) {
                throw new IOException("kare boş üretildi");
            }
            return bytes;
        } finally {
            Files.deleteIfExists(out);
        }
    }
}
