package org.example.VAD;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tek bir kanalın VAD döngüsü: ffmpeg → kare → model → bölütleyici.
 *
 * <p>Kanal başına bir iş parçacığı. Ölçülen maliyet düşük olduğu için
 * (ffmpeg %0,8 CPU, VAD 199× gerçek zaman) 20 kanal ~%20 CPU ve ~1 GB RAM'e
 * sığıyor; havuz karmaşıklığına gerek yok.
 *
 * <h2>Yeniden bağlanma</h2>
 * Kaynak kopması olağan: MediaMTX yeniden başlar, yayın düşer, ağ tıkanır.
 * Üstel geri çekilme kullanılıyor ({@value #BACKOFF_BASE_MS} ms'den başlayıp
 * {@value #BACKOFF_MAX_MS} ms'ye kadar) — saniyede bir yeniden bağlanmaya
 * çalışmak MediaMTX'i boşuna yorar.
 *
 * <p>Her yeniden başlatmada <b>üçü birden</b> sıfırlanıyor: modelin LSTM
 * durumu, bölütleyicinin biriktirdiği ses ve akışın örnek sayacı. Biri
 * unutulursa yeni akış eski durumla devam eder ve ilk saniyeler bozulur.
 */
public final class ChannelVadWorker implements Runnable, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ChannelVadWorker.class);

    private static final long BACKOFF_BASE_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 30_000;

    /**
     * Bu kadar ses okunduktan sonra bağlantı "oturmuş" sayılıp geri çekilme
     * sıfırlanıyor.
     *
     * <p>Olmasaydı, dakikada bir kopan bir kaynak için bekleme süresi
     * sonsuza kadar büyür ve kanal saatlerce sessiz kalırdı.
     */
    private static final long STABLE_AFTER_MS = 30_000;

    /** Konuşma oranı bu aralıkla loglanıyor — sağlık göstergesi. */
    private static final long RATIO_LOG_INTERVAL_MS = 300_000;

    private final UUID channelId;
    private final String channelName;
    private final String mediamtxPath;
    private final String rtspBase;
    private final String modelPath;
    private final Consumer<SpeechSegment> onSegment;

    private volatile boolean running = true;
    private volatile AudioStream current;

    public ChannelVadWorker(UUID channelId, String channelName, String mediamtxPath,
                            String rtspBase, String modelPath,
                            Consumer<SpeechSegment> onSegment) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.mediamtxPath = mediamtxPath;
        this.rtspBase = rtspBase;
        this.modelPath = modelPath;
        this.onSegment = onSegment;
    }

    @Override
    public void run() {
        long backoff = BACKOFF_BASE_MS;

        // Model ve bolutleyici DONGUNUN DISINDA: her yeniden baglanmada
        // yeni oturum acmak 2,2 MB modeli tekrar tekrar yuklemek olurdu.
        try (SileroVad vad = new SileroVad(modelPath)) {
            SpeechSegmenter segmenter = new SpeechSegmenter(channelId, channelName, onSegment);
            long lastRatioLog = System.currentTimeMillis();

            while (running) {
                long read = 0;
                try (AudioStream stream = new AudioStream(mediamtxPath, rtspBase)) {
                    current = stream;
                    vad.reset();
                    segmenter.reset();
                    stream.start();

                    float[] frame;
                    while (running && (frame = stream.readFrame()) != null) {
                        segmenter.accept(frame, vad.score(frame), stream.currentFrameStart());

                        read = stream.readDurationMs();
                        if (read >= STABLE_AFTER_MS) {
                            backoff = BACKOFF_BASE_MS;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastRatioLog >= RATIO_LOG_INTERVAL_MS) {
                            logRatio(segmenter);
                            lastRatioLog = now;
                        }
                    }
                    // Akis bitti: son bolut kaybolmasin.
                    segmenter.flush();
                    LOG.infof("Ses akışı sonlandı: %s (%d sn okundu) %s",
                        channelName, read / 1000, stream.lastErrors());

                } catch (Exception e) {
                    // Tek bir kanalin hatasi digerlerini etkilememeli.
                    segmenter.flush();
                    LOG.warnf("VAD akışı koptu: %s — %s", channelName, e.getMessage());
                } finally {
                    current = null;
                }

                if (!running) {
                    break;
                }
                sleep(backoff);
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            }
            logRatio(segmenter);

        } catch (RuntimeException e) {
            // Model yuklenemedi ya da imza tutmadi: yeniden denemenin anlami
            // yok, yapilandirma hatasi.
            LOG.errorf(e, "VAD işçisi başlatılamadı: %s", channelName);
        }
        LOG.infof("VAD işçisi durdu: %s", channelName);
    }

    /**
     * Konuşma oranını loglar.
     *
     * <p><b>%0 ya da %100 bozukluk demek</b> — en olası sebep modelin bağlam
     * girdisinin verilmemesi. Ayrıca bu oran STT'de kazanılacak GPU
     * tasarrufunun ta kendisi: ölçülen bir haber kanalında %97 çıktı, yani
     * orada VAD neredeyse hiç kazanç sağlamıyor.
     */
    private void logRatio(SpeechSegmenter segmenter) {
        if (segmenter.totalFrames() == 0) {
            return;
        }
        LOG.infof("VAD %s: konuşma oranı %%%.0f (%d kare)",
            channelName, segmenter.speechRatio() * 100, segmenter.totalFrames());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Duration.ofMillis(ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Döngüyü durdurur — kanal yayından çıkınca ya da kapanışta. */
    @Override
    public void close() {
        running = false;
        AudioStream stream = current;
        if (stream != null) {
            // Okuma bloke oldugu icin akisi kapatmak sart: yalnizca bayrak
            // indirilseydi is parcacigi bir sonraki kareye kadar beklerdi.
            stream.close();
        }
    }

    public UUID channelId() {
        return channelId;
    }

    public String mediamtxPath() {
        return mediamtxPath;
    }

    public String channelName() {
        return channelName;
    }
}
