package org.example.VAD;

import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bir MediaMTX path'inden ham PCM okuyan ffmpeg süreci.
 *
 * <h2>Komut ve gerekçeleri</h2>
 * <pre>
 * ffmpeg -v error -rtsp_transport tcp -allowed_media_types audio \
 *        -i rtsp://mediamtx:8554/&lt;path&gt; \
 *        -vn -ac 1 -ar 16000 -f s16le -
 * </pre>
 *
 * <table>
 *   <tr><td>{@code -rtsp_transport tcp}</td>
 *       <td>UDP'de paket kaybı sessiz ses boşluğu yapar; VAD onu sessizlik
 *           sanar ve bölütü yanlış yerde keser</td></tr>
 *   <tr><td>{@code -allowed_media_types audio}</td>
 *       <td><b>Ölçüldü: CPU %1,5 → %0,8.</b> Video track'i RTSP'de hiç SETUP
 *           edilmiyor, ağdan da gelmiyor</td></tr>
 *   <tr><td>{@code -ac 1 -ar 16000}</td>
 *       <td>Silero ve Whisper'ın istediği tam biçim</td></tr>
 *   <tr><td>{@code -f s16le -}</td>
 *       <td>Ham PCM, stdout'a — ara dosya yok</td></tr>
 * </table>
 *
 * <p>Ölçülen maliyet kanal başına <b>%0,8 CPU · 49 MB</b>; 8 paralel süreçte
 * süreç başına %1,0, yani doğrusal.
 *
 * <h2>Nerede çalışır</h2>
 * <b>ffmpeg gerektirir.</b> Backend imajında ffmpeg yok (doğrulandı); bu sınıf
 * yalnızca {@code video-worker} gibi ffmpeg içeren bir konteynerde çalışır.
 *
 * <p><b>İş parçacığı güvenli değil.</b> Kanal başına bir örnek.
 */
public final class
AudioStream implements Closeable {

    private static final Logger LOG = Logger.getLogger(AudioStream.class);

    /** Hata mesajında gösterilecek son ffmpeg satırı sayısı. */
    private static final int STDERR_KEEP = 20;

    private final String mediamtxPath;
    private final String rtspBase;

    private Process process;
    private DataInputStream in;
    private Thread stderrPump;

    /** ffmpeg'in son çıktıları — süreç ölünce sebebi söyleyebilmek için. */
    private final Deque<String> stderrTail = new ArrayDeque<>();

    /**
     * Akışın <b>çıpası</b>: okunan örnek sayısı buna eklenerek mutlak zaman
     * bulunuyor.
     *
     * <p><b>Duvar saati kullanılmıyor.</b> Her karede {@code Instant.now()}
     * çağrılsaydı ağ tıkanmasında zaman kayar ve bir daha toparlamazdı; örnek
     * sayacı kaymaz.
     */
    private Instant anchor;

    /** Akış başından beri okunan örnek — zaman hesabının tek kaynağı. */
    private long samplesRead;

    private final byte[] frameBytes = new byte[VadConfig.FRAME_BYTES];
    private final float[] frame = new float[VadConfig.FRAME_SAMPLES];

    public AudioStream(String mediamtxPath, String rtspBase) {
        this.mediamtxPath = mediamtxPath;
        this.rtspBase = rtspBase;
    }

    /**
     * ffmpeg'i başlatır.
     *
     * <p>Yeniden başlatmada sayaç sıfırlanıp çıpa yeniden konuyor. Çağıranın
     * ayrıca {@link SileroVad#reset()} çağırması gerekiyor — yeni akış eski
     * LSTM durumuyla devam ederse ilk saniyeler bozuk skorlanır.
     */
    public void start() throws IOException {
        List<String> cmd = List.of(
            "ffmpeg", "-v", "error",
            "-rtsp_transport", "tcp",
            "-allowed_media_types", "audio",
            "-i", rtspBase + "/" + mediamtxPath,
            "-vn", "-ac", "1", "-ar", String.valueOf(VadConfig.SAMPLE_RATE),
            "-f", "s16le", "-");

        // redirectErrorStream(true) YAPILMIYOR: hata metni PCM verinin icine
        // karisir ve akis bozulur.
        process = new ProcessBuilder(cmd).start();
        in = new DataInputStream(new BufferedInputStream(
            process.getInputStream(), VadConfig.FRAME_BYTES * 16));

        startStderrPump();

        samplesRead = 0;
        anchor = Instant.now();
        LOG.infof("Ses akışı başladı: %s", mediamtxPath);
    }

    /**
     * stderr'i ayrı iş parçacığında boşaltır.
     *
     * <p><b>Şart.</b> Okunmazsa boru dolar, ffmpeg yazarken bloke olur ve
     * süreç sessizce donar — stdout'tan da veri gelmez. Belirtisi "ffmpeg
     * çalışıyor ama kare gelmiyor" olur ve sebebi bulmak zordur.
     */
    private void startStderrPump() {
        stderrPump = new Thread(() -> {
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
                    LOG.debugf("[ffmpeg %s] %s", mediamtxPath, line);
                }
            } catch (IOException e) {
                // Surec kapanirken beklenen; sessizce bitiyoruz.
            }
        }, "ffmpeg-stderr-" + mediamtxPath);
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    /**
     * Bir kare okur ve {@code [-1,1]} aralığına çevirir.
     *
     * <p>Dönen dizi <b>yeniden kullanılıyor</b> — çağıran içeriğini saklamak
     * isterse kopyalamalı. Karede 512 örnek var ve saniyede 31 kare geliyor;
     * her kare için yeni dizi ayırmak 20 kanalda gereksiz çöp üretirdi.
     *
     * @return kare, ya da akış bittiyse {@code null}
     */
    public float[] readFrame() throws IOException {
        try {
            // readFully SART: read() kisa donebilir ve eksik okunan bir kare
            // SONRAKI TUM kareleri kaydirir. Model bunu belli etmez, yalnizca
            // skorlar bozulur.
            in.readFully(frameBytes);
        } catch (EOFException e) {
            return null;
        }

        ByteBuffer bb = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < VadConfig.FRAME_SAMPLES; i++) {
            frame[i] = bb.getShort() / 32768f;
        }
        samplesRead += VadConfig.FRAME_SAMPLES;
        return frame;
    }

    /**
     * Son okunan karenin <b>mutlak</b> başlangıç anı.
     *
     * <p>{@link #readFrame()} çağrıldıktan sonra geçerli.
     */
    public Instant currentFrameStart() {
        long ms = (samplesRead - VadConfig.FRAME_SAMPLES) * 1000L / VadConfig.SAMPLE_RATE;
        return anchor.plusMillis(ms);
    }

    /** Akış başından beri okunan ses süresi — doğrulama için. */
    public long readDurationMs() {
        return samplesRead * 1000L / VadConfig.SAMPLE_RATE;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** ffmpeg'in son çıktıları — süreç beklenmedik şekilde ölünce sebebi burada. */
    public String lastErrors() {
        synchronized (stderrTail) {
            return String.join(" | ", stderrTail);
        }
    }

    public String path() {
        return mediamtxPath;
    }

    @Override
    public void close() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            // Nazik kapanma icin kisa sure; ffmpeg genelde hemen cikar.
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            // Kapanista yutuluyor.
        }
        if (stderrPump != null) {
            try {
                stderrPump.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.infof("Ses akışı kapandı: %s (%d sn okundu)", mediamtxPath, readDurationMs() / 1000);
    }
}
