package org.example.media;

import java.util.List;

/**
 * Video kodlamada kullanılacak hızlandırıcı.
 *
 * <p>İki ayrı yerde kullanılıyor ve <b>ayrı ayrı ayarlanıyor</b>:
 * <ul>
 *   <li>Canlı yayın rendition'ları — mediamtx konteynerinde
 *       ({@code channels.encoder})</li>
 *   <li>Kütüphane küçük resim ve önizleme klipleri — video-worker
 *       konteynerinde ({@code videos.encoder})</li>
 * </ul>
 * Ayrı olmalarının sebebi iki konteynerin farklı aygıtlara erişebilmesi.
 *
 * <p><b>Elle seçiliyor.</b> Kodlama her iki durumda da <i>başka bir
 * konteynerde</i> yapıldığı için backend oradaki donanımı göremez.
 *
 * <p><b>Ölçülen maliyet</b> (canlı yayın, 1080p kaynak, rendition başına):
 * VAAPI ~%34 CPU, {@link #YAZILIM} bunun birkaç katı. Karşılaştırma için
 * MediaMTX'in kendisi 12 kanal + 15 radyoyu %25 CPU ile taşıyor — yani
 * ölçeklemenin önündeki duvar kanal sayısı değil, transcode.
 */
public enum VideoEncoder {

    /**
     * NVIDIA, NVDEC + CUDA + NVENC.
     *
     * <p><b>Hedef üretim yapılandırması.</b> Tam GPU hattı destekleniyor:
     * çözme NVDEC'te, ölçekleme {@code scale_cuda} ile CUDA'da, kodlama
     * NVENC'te — kareler hiç sistem belleğine inmiyor. Her iki imajda
     * {@code h264_nvenc}, {@code scale_cuda} ve {@code h264_cuvid} mevcut.
     *
     * <p>Konteynerde NVIDIA sürücüsü ve {@code nvidia-container-toolkit}
     * gerekiyor; compose'da GPU ayrılmış olmalı (bkz. {@code
     * docker-compose.nvidia.yml}).
     *
     * <p>{@code -preset p4}: NVENC'in yeni ön ayar ölçeğinde denge noktası
     * (p1 en hızlı, p7 en kaliteli).
     */
    NVENC,

    /**
     * Intel/AMD, {@code /dev/dri} üzerinden.
     *
     * <p>Bu geliştirme makinesinde (Intel Iris Xe) kullanılan seçenek.
     * Ölçekleme bilinçli olarak yazılımda: tam VAAPI hattı
     * ({@code -hwaccel vaapi} ile çözme) denendi ve bu kaynakta çalışmadı.
     */
    VAAPI,

    /**
     * Donanım yok — {@code libx264}.
     *
     * <p>Canlı yayında pahalı; birkaç kanaldan fazlasında makineyi doyurur.
     * Önizleme klipleri için yeterli.
     */
    YAZILIM;

    /**
     * Girdiden <b>önce</b> verilecek argümanlar: donanım bağlamı ve çözme
     * hızlandırması.
     *
     * @param fullGpu {@link #NVENC} için kareler GPU belleğinde kalsın mı.
     *                {@code true} en verimlisi ama filtre zinciri
     *                {@code scale_cuda} kullanmak zorunda kalır; sürücü ya da
     *                kaynak sorun çıkarırsa {@code false} ile yazılım
     *                ölçeklemeye düşülebilir.
     */
    public List<String> inputArgs(String vaapiDevice, boolean fullGpu) {
        return switch (this) {
            case NVENC -> fullGpu
                ? List.of("-hwaccel", "cuda", "-hwaccel_output_format", "cuda")
                : List.of("-hwaccel", "cuda");
            case VAAPI -> List.of("-vaapi_device", vaapiDevice);
            case YAZILIM -> List.of();
        };
    }

    /** Aynı argümanların tek dizge hali — MediaMTX kancasına metin veriliyor. */
    public String inputArgsAsString(String vaapiDevice, boolean fullGpu) {
        List<String> args = inputArgs(vaapiDevice, fullGpu);
        return args.isEmpty() ? "" : " " + String.join(" ", args);
    }

    /**
     * Canlı yayın rendition'ı için filtre ve kodek argümanları.
     *
     * <p>{@code -map}: kaynakta birden fazla akış olabiliyor; açıkça
     * seçilmezse filtre yanlış akışa uygulanıp "Error while processing the
     * decoded data" ile düşüyor (ölçümde yaşandı).
     *
     * <p>{@code -c:a copy}: ses hiçbir seçenekte yeniden kodlanmıyor.
     */
    public String renditionArgs(int width, int height, String bitrate, boolean fullGpu) {
        String map = " -map 0:v:0 -map 0:a:0";
        return switch (this) {
            case NVENC -> map
                // scale_cuda: kareler GPU bellegindeyken olcekleniyor, sistem
                // bellegine inip cikmiyorlar. fullGpu kapaliysa kareler zaten
                // indirilmis oluyor, yazilim scale gerekiyor.
                + (fullGpu ? " -vf scale_cuda=" + width + ":" + height
                           : " -vf scale=" + width + ":" + height + ",format=yuv420p")
                + " -c:v h264_nvenc -preset p4 -tune ll"
                + " -b:v " + bitrate + " -maxrate " + bitrate + " -bufsize " + bitrate
                + " -c:a copy";
            case VAAPI -> map
                + " -vf scale=" + width + ":" + height + ",format=nv12,hwupload"
                + " -c:v h264_vaapi -b:v " + bitrate
                + " -c:a copy";
            case YAZILIM -> map
                + " -vf scale=" + width + ":" + height
                + " -c:v libx264 -preset veryfast -tune zerolatency"
                + " -b:v " + bitrate + " -maxrate " + bitrate + " -bufsize " + bitrate
                + " -pix_fmt yuv420p"
                + " -c:a copy";
        };
    }

    /**
     * Kütüphane önizleme klibi için argümanlar.
     *
     * <p>Rendition'dan farkı: ses yok, kalite bit hızıyla değil sabit kalite
     * ile hedefleniyor. VAAPI sabit kaliteyi güvenilir desteklemediği için
     * orada bit hızına düşülüyor.
     *
     * @param width hedef genişlik; yükseklik en-boy oranından ({@code -2})
     * @param crf   yazılım/NVENC için kalite değeri (yüksek = küçük dosya)
     */
    public List<String> previewArgs(int width, int crf, String vaapiBitrate, boolean fullGpu) {
        return switch (this) {
            case NVENC -> fullGpu
                ? List.of("-vf", "scale_cuda=" + width + ":-2",
                    "-c:v", "h264_nvenc", "-preset", "p4", "-cq", String.valueOf(crf))
                : List.of("-vf", "scale=" + width + ":-2,format=yuv420p",
                    "-c:v", "h264_nvenc", "-preset", "p4", "-cq", String.valueOf(crf));
            case VAAPI -> List.of("-vf", "scale=" + width + ":-2,format=nv12,hwupload",
                "-c:v", "h264_vaapi", "-b:v", vaapiBitrate);
            case YAZILIM -> List.of("-vf", "scale=" + width + ":-2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", String.valueOf(crf),
                "-pix_fmt", "yuv420p");
        };
    }

    /**
     * Tek kare yakalarken kullanılacak <b>yalnızca çözme</b> hızlandırması.
     *
     * <p>Küçük resim JPEG olarak yazılıyor ve mjpeg kodlayıcı yazılımda;
     * kare bu yüzden sistem belleğinde olmalı. {@code hwaccel_output_format}
     * <b>verilmiyor</b> — verilseydi kare GPU'da kalır ve mjpeg'e
     * ulaşamayıp {@code hwdownload} zorunlu hale gelirdi. Çözme yine de
     * GPU'da yapılıyor, asıl kazanç orada.
     */
    public List<String> frameGrabInputArgs() {
        return this == NVENC ? List.of("-hwaccel", "cuda") : List.of();
    }
}
