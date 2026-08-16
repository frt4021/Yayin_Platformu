package org.example.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.exception.AppException;
import org.example.media.VideoEncoder;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe ve ffmpeg çağrıları.
 *
 * <p>Bu sınıf yalnızca <b>işçi konteynerinde</b> anlamlı: backend imajında
 * ffmpeg yok. Bean olarak her yerde ayağa kalkar ama çağrılmadıkça bir şey
 * yapmaz.
 *
 * <p><b>Kabuk kullanılmıyor.</b> {@link ProcessBuilder} komutu argüman
 * listesiyle çalıştırıyor, dolayısıyla imzalı adresin içindeki {@code &},
 * {@code ?} gibi karakterler yorumlanmıyor. Radyo köprüsündeki
 * ({@code AudioBridgeCommand}) tırnaklama zorunluluğu burada yok — orada
 * komutu MediaMTX bir kabuğa veriyordu.
 */
@ApplicationScoped
public class MediaTools {

    private static final Logger LOG = Logger.getLogger(MediaTools.class);

    /** MP4 üst düzey kutu başlığı: 4 bayt boyut + 4 bayt tip. */
    private static final int BOX_HEADER = 8;

    /**
     * Önizleme kalitesi. Yüksek değer = küçük dosya; 30 kart üzerinde
     * oynayan 480p bir klip için fazlasıyla yeterli.
     */
    private static final int PREVIEW_CRF = 30;

    /** VAAPI sabit kaliteyi güvenilir desteklemiyor; orada bit hızına düşülüyor. */
    private static final String PREVIEW_VAAPI_BITRATE = "600k";

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "videos.ffmpeg-timeout-minutes")
    int timeoutMinutes;

    @ConfigProperty(name = "videos.thumbnail-width")
    int thumbnailWidth;

    /**
     * Önizleme klibi hangi hızlandırıcıyla kodlanacak.
     *
     * <p>Kanallardan <b>ayrı</b> ayarlanıyor: iki konteyner farklı aygıtlara
     * erişebilir. Varsayılan {@code YAZILIM} çünkü worker'a aygıt geçirilmiş
     * olması gerekmiyor ve 5 saniyelik 480p bir klip zaten saniyeler içinde
     * kodlanıyor.
     */
    @ConfigProperty(name = "videos.encoder")
    VideoEncoder encoder;

    @ConfigProperty(name = "videos.vaapi-device")
    String vaapiDevice;

    /** NVENC'te kareler GPU belleğinde kalsın mı; bkz. {@link VideoEncoder}. */
    @ConfigProperty(name = "videos.gpu-full-pipeline")
    boolean fullGpu;

    /**
     * ffprobe çıktısının işe yarayan kısmı.
     *
     * @param durationSeconds toplam süre; bilinmiyorsa {@code null}
     * @param hasVideo        video akışı var mı. <b>Yoksa yüklenen şey video
     *                        değildir</b> — imzalı adrese herhangi bir bayt
     *                        dizisi yazılabildiği için bu kontrol şart.
     * @param formatNames     kapsayıcı biçimleri ({@code mov,mp4,m4a,...})
     */
    public record Probe(
        Integer durationSeconds,
        Integer width,
        Integer height,
        boolean hasVideo,
        boolean hasAudio,
        String formatNames
    ) {
        /** faststart yalnızca MP4 ailesinde anlamlı; WebM'de böyle bir sorun yok. */
        public boolean isMp4Family() {
            return formatNames != null && formatNames.contains("mp4");
        }
    }

    /**
     * Dosyayı inceler. Girdi imzalı bir HTTP adresi olabilir — ffprobe
     * yalnızca gereken bölümleri range isteğiyle çeker, dosyanın tamamını
     * indirmez.
     */
    public Probe probe(String input) {
        String json = run(List.of(
            "ffprobe", "-v", "error",
            "-print_format", "json",
            "-show_format", "-show_streams",
            input), "ffprobe");

        try {
            JsonNode root = mapper.readTree(json);
            JsonNode format = root.path("format");

            Integer duration = null;
            if (format.hasNonNull("duration")) {
                double d = format.get("duration").asDouble(0);
                if (d > 0) {
                    duration = (int) Math.round(d);
                }
            }

            boolean hasVideo = false;
            boolean hasAudio = false;
            Integer width = null;
            Integer height = null;
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asText("");
                if ("video".equals(type)) {
                    // Kapak gorseli de "video" akisi olarak gorunur; gercek
                    // videodan ayirmak icin attached_pic bayragina bakiliyor.
                    if (stream.path("disposition").path("attached_pic").asInt(0) == 1) {
                        continue;
                    }
                    hasVideo = true;
                    width = stream.path("width").isMissingNode() ? null : stream.get("width").asInt();
                    height = stream.path("height").isMissingNode() ? null : stream.get("height").asInt();
                } else if ("audio".equals(type)) {
                    hasAudio = true;
                }
            }

            return new Probe(duration, width, height, hasVideo, hasAudio,
                format.path("format_name").asText(null));
        } catch (Exception e) {
            throw AppException.internalError("ffprobe çıktısı okunamadı", e);
        }
    }

    /**
     * Belirtilen andan tek kare yakalar ve JPEG olarak döner.
     *
     * <p>{@code -ss} girdiden <b>önce</b> veriliyor: bu "input seeking" demek
     * ve ffmpeg dosyanın yalnızca gereken kısmını okur. Girdiden sonra
     * verilseydi ffmpeg baştan itibaren çözerek o ana kadar ilerler, uzun bir
     * videoda dakikalar sürerdi.
     */
    public byte[] thumbnail(String input, int atSeconds) {
        Path out = tempFile("kucukresim", ".jpg");
        try {
            List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin"));
            // Yalnizca COZME hizlandirmasi: JPEG'i yazan mjpeg kodlayici
            // yazilimda, dolayisiyla kare sistem belleginde olmali.
            command.addAll(encoder.frameGrabInputArgs());
            command.addAll(List.of(
                "-ss", String.valueOf(atSeconds),
                "-i", input,
                "-frames:v", "1",
                // -2: yukseklik en-boy oranina gore hesaplansin ve cift sayi olsun.
                "-vf", "scale=" + thumbnailWidth + ":-2",
                "-q:v", "4",
                "-y", out.toString()));
            run(command, "ffmpeg (küçük resim)");
            byte[] bytes = Files.readAllBytes(out);
            if (bytes.length == 0) {
                throw AppException.internalError("Küçük resim boş üretildi.", null);
            }
            return bytes;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw AppException.internalError("Küçük resim üretilemedi", e);
        } finally {
            deleteQuietly(out);
        }
    }

    /**
     * Kısa, sessiz, düşük çözünürlüklü önizleme klibi üretir.
     *
     * <p>Izgarada fare kartın üzerine geldiğinde asıl videoyu oynatmak, 1080p
     * bir kaynakta birkaç saniye için birkaç megabayt indirmek demekti.
     * Kullanıcı ızgarada gezinirken bu hızla büyüyor. Bu klip ~200-400 KB.
     *
     * <p>Seçimler:
     * <ul>
     *   <li>{@code -ss} girdiden önce — hızlı arama, tüm dosya okunmuyor</li>
     *   <li>{@code -an} — önizleme zaten sessiz oynatılıyor, ses boşuna yer kaplar</li>
     *   <li>{@code +faststart} — kart üzerinde anında başlaması için şart</li>
     *   <li>{@code yuv420p} — bazı kaynaklar 10-bit ya da 4:2:2 geliyor,
     *       tarayıcılar bunları oynatamıyor</li>
     * </ul>
     *
     * @return üretilen dosyanın yolu; çağıran silmekle yükümlü
     */
    public Path previewClip(String input, int atSeconds, int durationSeconds, int width) {
        Path out = tempFile("onizleme", ".mp4");
        try {
            List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin"));
            // Donanim baglami ve cozme hizlandirmasi girdiden ONCE verilmeli.
            command.addAll(encoder.inputArgs(vaapiDevice, fullGpu));
            command.addAll(List.of(
                "-ss", String.valueOf(atSeconds),
                "-t", String.valueOf(durationSeconds),
                "-i", input,
                "-an"));
            command.addAll(encoder.previewArgs(width, PREVIEW_CRF, PREVIEW_VAAPI_BITRATE, fullGpu));
            command.addAll(List.of("-movflags", "+faststart", "-y", out.toString()));

            run(command, "ffmpeg (önizleme)");
            return out;
        } catch (RuntimeException e) {
            deleteQuietly(out);
            throw e;
        }
    }

    /**
     * Dosyadan 16 kHz tek kanal ham PCM ({@code s16le}) çıkarır — VAD/Triton'un
     * beklediği tam biçim, canlı {@code AudioStream}'in ffmpeg bayraklarıyla
     * AYNI (yalnızca stdout'a akıtmak yerine geçici dosyaya: bu bir toplu iş,
     * akış gerekmiyor).
     *
     * @return üretilen PCM dosyasının yolu; çağıran silmekle yükümlü
     */
    public Path extractAudio(String input) {
        Path out = tempFile("ses", ".pcm");
        try {
            run(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", input,
                "-vn", "-ac", "1", "-ar", "16000",
                "-f", "s16le", "-y", out.toString()), "ffmpeg (ses çıkarma)");
            return out;
        } catch (RuntimeException e) {
            deleteQuietly(out);
            throw e;
        }
    }

    /**
     * Dosyayı yeniden kodlamadan {@code faststart} düzenine getirir.
     *
     * <p>Yeniden kodlama yok ({@code -c copy}) ama dosya baştan sona okunup
     * yeniden yazılıyor: 4 GB'lık bir videoda bu dakikalar ve geçici olarak
     * iki katı depolama demek. Bu yüzden yalnızca gerçekten gerektiğinde
     * çağrılmalı (bkz. {@link #needsFastStart}).
     *
     * @return yeniden yazılmış dosyanın yolu; çağıran silmekle yükümlü
     */
    public Path remuxFastStart(String input) {
        Path out = tempFile("faststart", ".mp4");
        try {
            run(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", input,
                "-c", "copy",
                "-movflags", "+faststart",
                "-y", out.toString()), "ffmpeg (faststart)");
            return out;
        } catch (RuntimeException e) {
            deleteQuietly(out);
            throw e;
        }
    }

    /**
     * Dosyanın başındaki üst düzey MP4 kutularına bakarak {@code moov}
     * atomunun {@code mdat}'tan önce gelip gelmediğini söyler.
     *
     * <p>Neden önemli: {@code moov} sondaysa tarayıcı oynatmaya başlamadan
     * <b>dosyanın tamamını</b> indirmeye çalışır. 4 GB'lık bir videoda bu,
     * oynatmanın hiç başlamaması demek.
     *
     * <p>Neden ffprobe değil: ffprobe kutu sırasını raporlamıyor. Kutular
     * sıralı bir yapı olduğu için dosyanın ilk birkaç kilobaytını okumak
     * yeterli — tam dosyayı indirmeye gerek yok.
     *
     * @param head dosyanın başından okunmuş baytlar
     * @return {@code moov}'dan önce {@code mdat} görülürse {@code true}
     */
    public boolean needsFastStart(byte[] head) {
        ByteBuffer buf = ByteBuffer.wrap(head);
        while (buf.remaining() >= BOX_HEADER) {
            long size = Integer.toUnsignedLong(buf.getInt());
            byte[] type = new byte[4];
            buf.get(type);
            String name = new String(type, java.nio.charset.StandardCharsets.US_ASCII);

            if ("moov".equals(name)) {
                return false;
            }
            if ("mdat".equals(name)) {
                return true;
            }

            if (size == 1) {
                // 64-bit boyut: 8 baytlik largesize basligi takip ediyor.
                if (buf.remaining() < 8) {
                    break;
                }
                size = buf.getLong();
                if (size < BOX_HEADER + 8) {
                    break;
                }
                size -= (BOX_HEADER + 8);
            } else if (size == 0) {
                // "dosya sonuna kadar" demek; sonrasinda kutu yok.
                break;
            } else {
                if (size < BOX_HEADER) {
                    break;
                }
                size -= BOX_HEADER;
            }

            if (size > buf.remaining()) {
                // Kutu okudugumuz parcanin disina tasiyor. moov'u henuz
                // gormediysek ve tasan kutu mdat degilse karar veremiyoruz;
                // guvenli taraf: remux etme (yanlis remux pahali).
                return false;
            }
            buf.position(buf.position() + (int) size);
        }
        return false;
    }

    public java.io.InputStream toStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    public Path tempFile(String prefix, String suffix) {
        try {
            return Files.createTempFile(prefix, suffix);
        } catch (Exception e) {
            throw AppException.internalError("Geçici dosya oluşturulamadı", e);
        }
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            LOG.warnf("Geçici dosya silinemedi: %s", path);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Süreci çalıştırır, çıktısını toplar, zaman aşımında öldürür.
     *
     * <p>Zaman aşımı şart: ağ üzerinden okuyan bir ffmpeg, kaynak yanıt
     * vermeyi bırakırsa sonsuza kadar bekleyebilir ve işçi iş parçacığını
     * kalıcı olarak kilitlerdi.
     */
    private String run(List<String> command, String label) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());

            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw AppException.internalError(
                    label + " zaman aşımına uğradı (" + timeoutMinutes + " dk).", null);
            }
            if (process.exitValue() != 0) {
                throw AppException.internalError(
                    label + " başarısız (kod " + process.exitValue() + "): " + firstLine(stderr), null);
            }
            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AppException.internalError(label + " kesildi", e);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw AppException.internalError(label + " çalıştırılamadı", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Hata mesajı kullanıcıya gösteriliyor; ffmpeg'in tüm çıktısı gereksiz gürültü. */
    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(çıktı yok)";
        }
        List<String> lines = new ArrayList<>(List.of(text.strip().split("\n")));
        return lines.get(lines.size() - 1).strip();
    }
}
