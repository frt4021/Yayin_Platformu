package org.example.channel;

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
 */
final class
TranscodeCommand {

    /**
     * Ölçülen: yazılım ölçekleme + VAAPI kodlama %14 CPU, tamamen yazılım
     * (libx264 veryfast) %142. 16 kanalda fark 2,2 çekirdek ile 22,7 çekirdek
     * arasında — 8 çekirdekli bir makinede yazılım kodlama mümkün değil.
     */
    private static final String VAAPI_DEVICE = "/dev/dri/renderD128";

    private TranscodeCommand() {
    }

    /**
     * @param renditions boşsa {@code null} döner — çağıran kancayı hiç yazmaz
     */
    static String build(List<Rendition> renditions) {
        if (renditions.isEmpty()) {
            return null;
        }

        // $MTX_PATH: MediaMTX'in kancaya geçirdiği path adı. Kaynak RTSP
        // üzerinden 127.0.0.1'den okunuyor — aynı konteyner içindeyiz.
        String outputs = renditions.stream()
            .map(TranscodeCommand::output)
            .collect(Collectors.joining(" "));

        return "ffmpeg -hide_banner -loglevel warning -nostdin"
            + " -vaapi_device " + VAAPI_DEVICE
            + " -rtsp_transport tcp -i rtsp://127.0.0.1:$RTSP_PORT/$MTX_PATH "
            + outputs;
    }

    private static String output(Rendition r) {
        // -map: kaynakta birden fazla akış olabiliyor; açıkça seçilmezse
        // filtre yanlış akışa uygulanıp "Error while processing the decoded
        // data" ile düşüyor (ölçümde yaşandı).
        //
        // scale yazılımda, hwupload sonrası kodlama GPU'da: tam donanım
        // hattı (hwaccel vaapi ile decode) bu kaynakta çalışmadı.
        //
        // -c:a copy: ses yeniden kodlanmıyor, gereksiz maliyet.
        return "-map 0:v:0 -map 0:a:0"
            + " -vf scale=" + r.width() + ":" + r.height() + ",format=nv12,hwupload"
            + " -c:v h264_vaapi -b:v " + r.bitrate()
            + " -c:a copy"
            + " -f rtsp rtsp://127.0.0.1:$RTSP_PORT/${MTX_PATH}_" + r.suffix();
    }
}
