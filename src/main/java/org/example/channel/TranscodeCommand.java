package org.example.channel;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.media.VideoEncoder;

/**
 * Çözünürlük düşürme için MediaMTX'e verilecek ffmpeg komutunu üretir.
 *
 * <p>Komut MediaMTX'in {@code runOnDemand} kancasıyla, rendition'ın kendi
 * path'ine ilk okuyucu geldiğinde <b>konteynerin içinde</b> çalıştırılır.
 * MediaMTX transcode yapmadığı için çözünürlük değiştirmenin tek yolu bu.
 *
 * <p><b>Rendition başına bağımsız süreç</b> (önceki "tek decode + N encode"
 * paylaşımlı modelden farklı): her rendition kendi kaynağı kendi çözüyor.
 * Bedel, aynı kanalın 2 rendition'ı eşzamanlı izlenirse kaynağın 2 kez
 * çözülmesi; kazanç, MediaMTX'in path-başına native {@code runOnDemand}
 * ilkesinin doğrudan kullanılabilmesi — paylaşımlı tek süreç modelinde iki
 * rendition'a aynı anda ilk izleyici gelirse aynı süreç iki kez
 * başlatılmaya çalışılırdı (bunu önlemek Java tarafında ekstra durum takibi
 * gerektirirdi). Kimse izlemiyorsa süreç HİÇ çalışmıyor — asıl kazanç bu
 * (bkz. docs/olcekleme-100-kullanici-plani.md §3).
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
     * Tek bir rendition için bağımsız decode+encode komutu.
     *
     * <p>Kaynak ve hedef path adları ({@code basePath}/{@code r.pathFor(...)})
     * Java tarafında zaten biliniyor — MediaMTX'in kancaya geçirdiği
     * {@code $MTX_PATH} değişkenine ihtiyaç yok, yalnızca {@code $RTSP_PORT}
     * kullanılıyor (aynı konteyner içinde 127.0.0.1'den okunuyor).
     */
    String buildOnDemand(String basePath, Rendition r) {
        return "ffmpeg -hide_banner -loglevel warning -nostdin"
            + encoder.inputArgsAsString(vaapiDevice, fullGpu)
            + " -rtsp_transport tcp -i rtsp://127.0.0.1:$RTSP_PORT/" + basePath + " "
            + encoder.renditionArgs(r.width(), r.height(), r.bitrate(), fullGpu).trim()
            + " -f rtsp rtsp://127.0.0.1:$RTSP_PORT/" + r.pathFor(basePath);
    }
}
