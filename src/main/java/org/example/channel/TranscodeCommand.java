package org.example.channel;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.media.VideoEncoder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Çözünürlük düşürme için MediaMTX'e verilecek ffmpeg komutunu üretir.
 *
 * <p>Komut MediaMTX'in {@code runOnAvailable} kancasıyla, yayın hazır olunca
 * <b>konteynerin içinde</b> çalıştırılır. MediaMTX transcode yapmadığı için
 * (119 ayarının hiçbiri kodlamayla ilgili değil) çözünürlük değiştirmenin
 * tek yolu bu.
 *
 * <p><b>Tek süreçte çoklu çıkış:</b> kaynak bir kez çözülür, her rendition
 * için ayrı kodlama yapılır. Rendition başına ayrı ffmpeg süreci açılsaydı
 * aynı akış N kez çözülürdü — ölçümde çözme, kodlamadan pahalıydı.
 *
 * <p><b>Kodlayıcı seçilebilir</b> ({@link VideoEncoder}): Intel/AMD için
 * VAAPI, NVIDIA için NVENC, donanım yoksa libx264. Statik bir sınıf değil de
 * bean olmasının sebebi bu — seçim yapılandırmadan geliyor.
 */
@ApplicationScoped
public class TranscodeCommand {

    /**
     * Hangi kodlayıcı kullanılacak.
     *
     * <p>Elle seçiliyor çünkü kodlama <b>mediamtx konteynerinde</b> yapılıyor;
     * backend başka bir konteynerde olduğu için oradaki aygıtları göremez ve
     * kendiliğinden doğru seçimi yapamaz.
     */
    @ConfigProperty(name = "channels.encoder")
    VideoEncoder encoder;

    @ConfigProperty(name = "channels.vaapi-device")
    String vaapiDevice;

    /**
     * NVENC'te kareler GPU belleğinde kalsın mı (NVDEC → scale_cuda → NVENC).
     * En verimli yol; sürücü ya da kaynak sorun çıkarırsa kapatılıp yazılım
     * ölçeklemeye düşülebilir.
     */
    @ConfigProperty(name = "channels.gpu-full-pipeline")
    boolean fullGpu;

    /**
     * @param renditions boşsa {@code null} döner — çağıran kancayı hiç yazmaz
     */
    String build(List<Rendition> renditions) {
        if (renditions.isEmpty()) {
            return null;
        }

        // $MTX_PATH ve $RTSP_PORT: MediaMTX'in kancaya geçirdiği değişkenler.
        // Kaynak RTSP üzerinden 127.0.0.1'den okunuyor — aynı konteyner içindeyiz.
        String outputs = renditions.stream()
            .map(this::output)
            .collect(Collectors.joining(" "));

        return "ffmpeg -hide_banner -loglevel warning -nostdin"
            + encoder.inputArgsAsString(vaapiDevice, fullGpu)
            + " -rtsp_transport tcp -i rtsp://127.0.0.1:$RTSP_PORT/$MTX_PATH "
            + outputs;
    }

    private String output(Rendition r) {
        return encoder.renditionArgs(r.width(), r.height(), r.bitrate(), fullGpu).trim()
            + " -f rtsp rtsp://127.0.0.1:$RTSP_PORT/${MTX_PATH}_" + r.suffix();
    }
}
