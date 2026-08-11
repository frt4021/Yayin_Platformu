package org.example.dvr;

import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Tek bir kanalın DVR kaydını alan işçi.
 *
 * <h2>Komut</h2>
 * <pre>
 * ffmpeg -v error -rtsp_transport tcp -i rtsp://mediamtx:8554/&lt;path&gt; \
 *        -c copy -f mpegts -
 * </pre>
 *
 * <table>
 *   <tr><td>{@code -c copy}</td>
 *       <td><b>Yeniden kodlama yok.</b> Kaynak hangi kalitedeyse o kaydediliyor;
 *           bir rendition'a yazmak kalite kaybı demekti ve rendition düştüğünde
 *           kayıt sessizce boşalıyordu</td></tr>
 *   <tr><td>{@code -rtsp_transport tcp}</td>
 *       <td>UDP'de paket kaybı kayda kalıcı bozulma olarak işlenir</td></tr>
 *   <tr><td>{@code -f mpegts -}</td>
 *       <td>Rastgele sınırdan kesilebilen tek biçim; bkz. {@link SegmentStream}</td></tr>
 * </table>
 *
 * <h2>Neden MediaMTX'in kendi kaydı kullanılmıyor</h2>
 * MediaMTX yalnızca yerel dosya sistemine yazabiliyor (S3 desteği yok,
 * ikilide izi bile çıkmadı) ve playback sunucusu da yalnızca o dizini
 * okuyabiliyor. Kayıt nesne depolamaya taşınınca hem yazma hem okuma bu
 * tarafa geçmek zorunda kaldı.
 *
 * <h2>Geri baskı</h2>
 * MinIO yavaşlarsa yükleme yavaşlar, bu iş parçacığı ffmpeg'den okumayı
 * keser, ffmpeg'in stdout borusu dolar ve ffmpeg bloke olur. <b>Bu noktada
 * RTSP tamponu taşarak canlı yayını etkileyebilir.</b> Bu yüzden segment
 * süresi aşımı ölçülüyor ve aşıldığında yüksek sesle loglanıyor: sessizce
 * yavaşlamak, DVR'da fark edilmeyen delikler açardı.
 *
 * <p><b>İş parçacığı başına bir örnek.</b> {@link Runnable} olarak çalışıyor.
 */
final class ChannelDvrRecorder implements Runnable, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ChannelDvrRecorder.class);

    /** Hata mesajında tutulacak son ffmpeg satırı sayısı. */
    private static final int STDERR_KEEP = 20;

    /**
     * ffmpeg ölünce yeniden başlamadan önce beklenen süre.
     *
     * <p>Kaynak gerçekten kesildiyse hemen yeniden denemek saniyede onlarca
     * süreç açıp kapamak olurdu.
     */
    private static final long RESTART_DELAY_MS = 3_000;

    interface SegmentSink {
        /**
         * Bir segment yüklendiğinde çağrılır.
         *
         * <p>Yükleme <b>bu çağrıdan önce</b> tamamlanmış oluyor; burada
         * yalnızca zaman çizelgesine satır yazılıyor.
         */
        void accept(UUID channelId, String objectKey, Instant start, Instant end, long bytes);
    }

    private final UUID channelId;
    private final String channelName;
    private final String mediamtxPath;
    private final String channelSlug;
    private final String rtspBase;
    private final long segmentMillis;
    private final DvrStorage storage;
    private final SegmentSink sink;

    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile boolean running = true;
    private volatile Process process;

    ChannelDvrRecorder(UUID channelId, String channelName, String mediamtxPath,
                       String channelSlug, String rtspBase, long segmentMillis,
                       DvrStorage storage, SegmentSink sink) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.mediamtxPath = mediamtxPath;
        this.channelSlug = channelSlug;
        this.rtspBase = rtspBase;
        this.segmentMillis = segmentMillis;
        this.storage = storage;
        this.sink = sink;
    }

    @Override
    public void run() {
        while (running) {
            try {
                kaydet();
            } catch (RuntimeException e) {
                LOG.warnf("DVR kaydı hata verdi (%s): %s — %s",
                    channelName, e.getMessage(), lastErrors());
            }
            if (running) {
                uyu(RESTART_DELAY_MS);
            }
        }
        LOG.infof("DVR kaydı durdu: %s", channelName);
    }

    /** Bir ffmpeg süreci boyunca segment segment kaydeder. */
    private void kaydet() {
        try {
            start();
        } catch (IOException e) {
            LOG.warnf("DVR ffmpeg başlatılamadı (%s): %s", channelName, e.getMessage());
            return;
        }

        // Tampon boyutu bir MinIO parçasının altında tutuluyor: daha büyüğü
        // yalnızca ffmpeg ile MinIO arasında bekleyen veriyi artırırdı.
        InputStream ffmpegOut = new BufferedInputStream(process.getInputStream(), 1 << 20);

        while (running && process.isAlive()) {
            Instant basladi = Instant.now();
            String key = storage.keyFor(channelSlug, basladi);
            SegmentStream segment = new SegmentStream(ffmpegOut, segmentMillis);

            long boyut;
            try {
                boyut = storage.put(key, segment);
            } catch (RuntimeException e) {
                LOG.errorf("DVR segmenti yüklenemedi (%s): %s", channelName, e.getMessage());
                // Akış devam ediyor; bu segment kayıp ama kayıt sürüyor.
                // Döngüden çıkmak tüm kanalı durdururdu.
                continue;
            }

            if (segment.sourceEnded() && boyut == 0) {
                // ffmpeg hic veri vermeden oldu: yeniden baslatilmali.
                break;
            }

            Instant bitti = Instant.now();
            sink.accept(channelId, key, basladi, bitti, boyut);

            long gecen = bitti.toEpochMilli() - basladi.toEpochMilli();
            // Segment suresinin belirgin uzamasi, yuklemenin yakalayamadigi
            // anlamina gelir ve ffmpeg borusu doluyor demektir -- bir sonraki
            // adim canli yayinin etkilenmesi.
            if (gecen > segmentMillis * 3 / 2) {
                LOG.warnf("DVR yüklemesi geride kalıyor (%s): segment %d ms sürdü, "
                        + "hedef %d ms. MinIO yavaşsa canlı yayın etkilenebilir.",
                    channelName, gecen, segmentMillis);
            }

            if (segment.sourceEnded()) {
                break;
            }
        }

        stop();
    }

    private void start() throws IOException {
        List<String> cmd = List.of(
            "ffmpeg", "-v", "error",
            "-rtsp_transport", "tcp",
            "-i", rtspBase + "/" + mediamtxPath,
            "-c", "copy",
            "-f", "mpegts", "-");

        // redirectErrorStream(true) YAPILMIYOR: hata metni TS verisinin icine
        // karisir ve akis bozulur.
        process = new ProcessBuilder(cmd).start();
        startStderrPump();
        LOG.infof("DVR kaydı başladı: %s (%s)", channelName, mediamtxPath);
    }

    /**
     * stderr'i ayrı iş parçacığında boşaltır.
     *
     * <p><b>Şart.</b> Okunmazsa boru dolar, ffmpeg yazarken bloke olur ve
     * süreç sessizce donar; stdout'tan da veri gelmez.
     */
    private void startStderrPump() {
        Thread t = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        if (stderrTail.size() > STDERR_KEEP) {
                            stderrTail.removeFirst();
                        }
                    }
                    LOG.debugf("[dvr-ffmpeg %s] %s", mediamtxPath, line);
                }
            } catch (IOException e) {
                // Surec kapanirken beklenen.
            }
        }, "dvr-ffmpeg-stderr-" + mediamtxPath);
        t.setDaemon(true);
        t.start();
    }

    private void stop() {
        Process p = process;
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void uyu(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    String lastErrors() {
        synchronized (stderrTail) {
            return String.join(" | ", stderrTail);
        }
    }

    UUID channelId() {
        return channelId;
    }

    @Override
    public void close() {
        running = false;
        stop();
    }
}
