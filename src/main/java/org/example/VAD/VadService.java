package org.example.VAD;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.MediaMtxService;
import org.example.channel.entity.Channel;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Yayında olan kanallar için VAD işçilerini açan ve kapatan katman.
 *
 * <h2>Nerede çalışır</h2>
 * <b>ffmpeg gerektiriyor.</b> Backend imajında ffmpeg yok (doğrulandı), bu
 * yüzden {@code VAD_ENABLED} yalnızca {@code video-worker} konteynerinde
 * açılıyor. Aynı jar iki konteynerde çalıştığı için bayrak şart — klip ve
 * video işçilerindeki desenin aynısı.
 *
 * <h2>Neden yoklama</h2>
 * Kanalın yayına girip çıkışı olay olarak yayınlanmıyor; MediaMTX'in path
 * durumu tek doğru kaynak. Yayında olmayan bir path'e bağlanan ffmpeg
 * <b>sessizce bekler</b> — hata vermez, veri de gelmez.
 *
 * <p>Yoklama aralığı kritik değil: kanal yayına girdikten birkaç saniye sonra
 * altyazının başlaması sorun değil, izleyici zaten 6-12 saniye geride.
 */
@ApplicationScoped
public class VadService {

    private static final Logger LOG = Logger.getLogger(VadService.class);

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    @Inject
    MediaMtxService mediaMtx;

    @Inject
    SttClient stt;

    @Inject
    org.example.subtitle.SubtitleService subtitles;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    @ConfigProperty(name = "vad.enabled")
    boolean enabled;

    @ConfigProperty(name = "vad.model-path")
    String modelPath;

    @ConfigProperty(name = "vad.max-channels")
    int maxChannels;

    @ConfigProperty(name = "vad.segment-dir")
    String segmentDir;

    /** Bölütler STT'ye gönderilsin mi. Kapalıysa yalnızca WAV yazılır. */
    @ConfigProperty(name = "vad.stt-enabled")
    boolean sttEnabled;

    @ConfigProperty(name = "mediamtx.rtsp-url")
    String rtspBase;

    private final Map<UUID, ChannelVadWorker> workers = new ConcurrentHashMap<>();
    private ExecutorService pool;

    /**
     * Çözümleme kuyruğu — <b>kare döngüsünden ayrı</b>.
     *
     * <p>Çözümleme uzun sürüyor: 25 saniyelik bir bölüt CPU'da ~6,5 saniye
     * (ölçüldü). {@code onSegment} doğrudan STT'yi çağırsaydı kare döngüsü o
     * süre boyunca dururdu; ffmpeg borusu dolar, sonraki kareler kaybolur ve
     * ses akışı bozulurdu.
     *
     * <p>Kuyruk <b>sınırlı ve dolduğunda bölüt düşürülüyor</b>. Sınırsız
     * olsaydı STT yetişemediğinde bellek sessizce büyür ve sonunda süreç
     * ölürdü; beklemek ise yakalamayı durdurmak demek — canlı yayında
     * geçmişi bekletemezsin.
     */
    private final BlockingQueue<SpeechSegment> queue = new ArrayBlockingQueue<>(64);
    private ExecutorService sttPool;

    /**
     * Yayında olan kanallarla aktif işçileri eşitler.
     *
     * <p>{@code SKIP}: bir tik uzarsa (MediaMTX yavaş, çok kanal) ikincisi
     * başlamıyor; aksi halde aynı kanal için iki işçi açılabilirdi.
     */
    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void sync() {
        if (!enabled) {
            return;
        }
        Map<String, org.example.channel.dto.MediaMtxPathList.Item> states = mediaMtx.pathStates();
        if (states.isEmpty()) {
            // MediaMTX'e ulasilamiyor. Isciler KAPATILMIYOR: anlik bir
            // aksaklikta tum kanallarin altyazisini kesmek, birkac saniye
            // eski bilgiyle devam etmekten kotu.
            LOG.debug("MediaMTX path durumu alınamadı, VAD eşitlemesi atlandı");
            return;
        }

        Map<UUID, Channel> live = new HashMap<>();
        for (Channel channel : Channel.listActive()) {
            var state = states.get(channel.mediamtxPath);
            if (state != null && state.ready()) {
                live.put(channel.id, channel);
            }
        }

        // Yayindan cikanlari kapat.
        workers.keySet().removeIf(id -> {
            if (live.containsKey(id)) {
                return false;
            }
            stop(id);
            return true;
        });

        // Yeni yayina girenleri ac.
        for (var entry : live.entrySet()) {
            if (workers.containsKey(entry.getKey())) {
                continue;
            }
            if (workers.size() >= maxChannels) {
                // Sessizce atlamak yerine uyariyoruz: sinira dayanildigi
                // fark edilmezse bazi kanallar hic altyazi almaz ve sebebi
                // hicbir yerde gorunmez.
                LOG.warnf("VAD kanal sınırı dolu (%d), atlanan: %s",
                    maxChannels, entry.getValue().name);
                break;
            }
            start(entry.getValue());
        }
    }

    /** Çözümleme işçilerini açar — ilk kanalla birlikte. */
    private void ensureSttPool() {
        if (sttPool != null || !sttEnabled) {
            return;
        }
        sttPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "stt-gonderici");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < 2; i++) {
            sttPool.submit(this::sttDongusu);
        }
    }

    private void sttDongusu() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SpeechSegment segment = queue.take();
                String cevap = stt.transcribe(segment);
                if (cevap != null) {
                    kaydet(segment, cevap);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warnf("Çözümleme kuyruğunda hata: %s", e.getMessage());
            }
        }
    }

    /**
     * STT yanıtını altyazı olarak kaydeder.
     *
     * <p>İngilizce metin ({@code text}) ve çeviriler ({@code translations})
     * <b>tek haritada</b> birleştiriliyor: İngilizce de bir hedef dil ve
     * ayrı tutulması arayüzde iki farklı yoldan okumayı gerektirirdi.
     */
    private void kaydet(SpeechSegment segment, String cevap) {
        try {
            var kok = json.readTree(cevap);
            String ingilizce = kok.path("text").asText("");
            if (ingilizce.isBlank()) {
                // Bos cozumleme: sessizlik ya da anlasilmayan ses. Kaydetmek
                // arayuzde bos altyazi kutusu gostermek olurdu.
                return;
            }

            Map<String, String> metinler = new HashMap<>();
            metinler.put("en", ingilizce);
            var ceviriler = kok.path("translations");
            ceviriler.fieldNames().forEachRemaining(
                dil -> metinler.put(dil, ceviriler.path(dil).asText("")));

            Float guven = kok.has("source_language_confidence")
                ? (float) kok.path("source_language_confidence").asDouble() : null;

            subtitles.kaydet(segment.channelId(), segment.startedAt(), segment.endedAt(),
                kok.path("source_language").asText(null), guven, metinler, segment.forceCut());

            LOG.infof("ALTYAZI %s [%s] %s → %s",
                segment.channelName(), segment.startedAt(),
                kok.path("source_language").asText("?"),
                ingilizce.length() > 80 ? ingilizce.substring(0, 80) + "…" : ingilizce);

        } catch (Exception e) {
            // Tek bolutun kaybi hatti durdurmamali.
            LOG.warnf("Altyazı kaydedilemedi: %s — %s", segment.channelName(), e.getMessage());
        }
    }

    private void start(Channel channel) {
        ensureSttPool();
        if (pool == null) {
            pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });
        }
        var worker = new ChannelVadWorker(channel.id, channel.name, channel.mediamtxPath,
            rtspBase, modelPath, this::onSegment);
        workers.put(channel.id, worker);
        pool.submit(worker);
        LOG.infof("VAD başladı: %s (%s)", channel.name, channel.mediamtxPath);
    }

    private void stop(UUID channelId) {
        ChannelVadWorker worker = workers.get(channelId);
        if (worker != null) {
            worker.close();
            LOG.infof("VAD durduruldu: %s", worker.mediamtxPath());
        }
    }

    /**
     * Bölüt hazır olduğunda çağrılır.
     *
     * <p><b>Şimdilik diske WAV yazılıyor.</b> STT servisi henüz yok ve
     * doğrulamanın tek gerçek yolu bölütleri kulakla dinlemek — hiçbir metrik
     * bunun yerini tutmuyor. Hat doğrulandıktan sonra burası STT çağrısına
     * çevrilecek.
     *
     * <p>Bu metot {@link ChannelVadWorker}'ın kare döngüsünden çağrılıyor ve
     * <b>bloklamamalı</b>: burada beklenirse ffmpeg borusu dolar ve akış
     * bozulur. Disk yazımı hızlı; STT'ye geçildiğinde kuyruğa atılmalı.
     */
    void onSegment(SpeechSegment segment) {
        try {
            // Klasor KANAL ADIYLA: dogrulama bu WAV'lari kulakla dinlemek
            // demek ve 20 kanal calisirken UUID klasorleri arasinda hangisinin
            // hangisi oldugunu bulmak imkansizdi. Nesne depolamada da ayni
            // karari vermistik; slug oradan geliyor.
            String klasor = org.example.storage.StoragePaths.slug(segment.channelName());
            if (klasor.isEmpty()) {
                klasor = segment.channelId().toString();
            }
            Path dir = Path.of(segmentDir, klasor);
            Files.createDirectories(dir);
            Path file = dir.resolve(STAMP.format(segment.startedAt())
                + "-" + segment.durationMs() + "ms"
                + (segment.forceCut() ? "-kesik" : "") + ".wav");

            try (OutputStream out = Files.newOutputStream(file)) {
                out.write(wavHeader(segment.pcm().length));
                out.write(segment.pcm());
            }
            LOG.debugf("Bölüt yazıldı: %s (%d ms)", file.getFileName(), segment.durationMs());
        } catch (IOException e) {
            // Bolut kaybi hattin tamamini durdurmamali.
            LOG.warnf("Bölüt yazılamadı: %s", e.getMessage());
        }

        if (sttEnabled && !queue.offer(segment)) {
            // Sessizce dusurmek, altyazinin neden eksik oldugunu hicbir
            // yerde gostermezdi. STT yetismiyorsa bunun gorunmesi sart.
            LOG.warnf("Çözümleme kuyruğu dolu, bölüt düşürüldü: %s [%s]",
                segment.channelName(), segment.startedAt());
        }
    }

    /** Kapanışta tüm işçileri durdurur — açık bölütler {@code flush} ile gelir. */
    void onShutdown(@Observes ShutdownEvent event) {
        workers.values().forEach(ChannelVadWorker::close);
        workers.clear();
        if (sttPool != null) {
            sttPool.shutdownNow();
        }
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Aktif VAD işçisi sayısı — sağlık ucu ve testler için. */
    public int activeCount() {
        return workers.size();
    }

    // ------------------------------------------------------------------

    /**
     * 44 baytlık WAV başlığı — 16 kHz, tek kanal, {@code s16le}.
     *
     * <p>Ham PCM'i çoğu oynatıcı açmıyor; başlık eklemek doğrulamayı
     * "dosyayı çift tıkla" kadar kolaylaştırıyor.
     */
    private static byte[] wavHeader(int dataLen) {
        int byteRate = VadConfig.SAMPLE_RATE * 2;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(36 + dataLen);
        b.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16);              // fmt bloğu uzunluğu
        b.putShort((short) 1);     // PCM
        b.putShort((short) 1);     // tek kanal
        b.putInt(VadConfig.SAMPLE_RATE);
        b.putInt(byteRate);
        b.putShort((short) 2);     // blok hizası
        b.putShort((short) 16);    // bit derinliği
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(dataLen);
        return b.array();
    }
}
