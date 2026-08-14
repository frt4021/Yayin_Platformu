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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
    TritonClient triton;

    @Inject
    org.example.subtitle.SubtitleService subtitles;

    @Inject
    org.example.subtitle.SubtitleBroadcaster broadcaster;

    @Inject
    org.example.subtitle.SubtitleLagMetrics lag;

    @Inject
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

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

    /**
     * Kuyruktan aynı anda kaç "stt-gonderici" thread'i Triton'a istek
     * gönderebilir. Asıl eşzamanlılık/batching artık Triton içinde
     * ({@code instance_group} + {@code dynamic_batching}) yönetiliyor — bu
     * değer yalnızca Java tarafındaki gönderici thread sayısını sınırlıyor.
     */
    @ConfigProperty(name = "vad.stt-gonderici-sayisi")
    int sttGondericiSayisi;

    @ConfigProperty(name = "mediamtx.rtsp-url")
    String rtspBase;

    /**
     * Altyazı bütçesi — {@code SubtitleLagMetrics}'in kapsama yüzdesini
     * hesapladığı aynı değer. Burada farklı bir amaçla kullanılıyor: bir
     * bölüt kuyruğa girmeden önce bu bütçeyi ÇOKTAN aşmışsa, işlenmiş olsa
     * bile izleyiciye asla ulaşmayacak (bkz. SubtitleOverlay'deki mutlak
     * zaman damgası eşleşmesi) — o yüzden hiç kuyruğa alınmıyor.
     */
    @ConfigProperty(name = "altyazi.butce-ms")
    long butceMs;

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
     * <h2>Neden kanal başına ayrı kuyruk</h2>
     * Tek paylaşımlı kuyrukta konuşkan bir kanal kapasitenin tamamını
     * doldurabiliyordu — sessiz kanalların taze bölütleri de aynı kapıda
     * reddediliyordu ("head-of-line blocking"). Kanal başına küçük, ayrı bir
     * kuyruk bu haksızlığı kapatıyor: bir kanalın tavanı diğerini etkilemez.
     *
     * <h2>Neden en eski düşürülüyor, en yeni değil</h2>
     * Kuyruk dolduğunda önceki tasarım YENİ gelen bölütü reddediyordu
     * (Java'nın {@code offer()}'ı), ama tüketim hep EN ESKİDEN yapılıyordu
     * ({@code take()}) — kuyruk bir kez dolunca içi kalıcı olarak bayat
     * kalıyordu. Şimdi tam tersi: dolunca en eski atılır, taze olan girer.
     * Canlı altyazıda izleyici "şimdi ne söyleniyor"u önemser, dakikalar önce
     * kuyruğa girmiş bir cümleyi değil.
     */
    private static final int KANAL_KUYRUK_KAPASITESI = 4;

    /** Kanal başına bekleyen bölütler — kapasitesi {@link #KANAL_KUYRUK_KAPASITESI}. */
    private final Map<UUID, Deque<SpeechSegment>> bekleyen = new ConcurrentHashMap<>();

    /**
     * İşlenmeyi bekleyen bölütü olan kanallar — round-robin sırayla.
     *
     * <p>Bir kanal aynı anda en fazla bir kez burada: iş bitince kuyrukta
     * başka bölütü kaldıysa sıranın SONUNA yeniden ekleniyor, böylece
     * ardışık iki bölüt aynı kanaldan gelse de diğer kanallar aradan
     * geçebiliyor.
     */
    private final BlockingQueue<UUID> hazir = new LinkedBlockingQueue<>();

    /** Kanal başına kayıtlı derinlik gauge'u — durunca kaydı silinsin diye tutuluyor. */
    private final Map<UUID, io.micrometer.core.instrument.Gauge> kuyrukGaugeleri = new ConcurrentHashMap<>();

    private ExecutorService sttPool;

    /**
     * Çevirileri dağıtan havuz — {@code sttPool}'dan AYRI: sttPool
     * thread'leri sürekli {@link #hazir} kuyruğundan almalı, bir bölütün 3
     * dilinin ağ cevabını beklerken tıkanmamalı.
     */
    private ExecutorService ceviriPool;

    /**
     * Triton'daki hedef dil → model adı eşlemesi. stt-worker/app/config.py'deki
     * {@code TRANSLATION_MODELS} ile AYNI küme — orada da belirtildiği gibi
     * SABİT, Whisper pivotu sağladığı için sadece {@code EN → X} yönleri var.
     */
    private static final Map<String, String> DIL_MODELLERI = Map.of(
        "tr", "marian_en_tr",
        "de", "marian_en_de",
        "ru", "marian_en_ru"
    );

    @jakarta.annotation.PostConstruct
    void metrikleriKaydet() {
        meterRegistry.gauge("altyazi_aktif_kanal", workers, Map::size);
    }

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
        sttPool = Executors.newFixedThreadPool(sttGondericiSayisi, r -> {
            Thread t = new Thread(r, "stt-gonderici");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < sttGondericiSayisi; i++) {
            sttPool.submit(this::sttDongusu);
        }
        ceviriPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ceviri-gonderici");
            t.setDaemon(true);
            return t;
        });
    }

    private void sttDongusu() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                UUID channelId = hazir.take();
                SpeechSegment segment = kanaldanAl(channelId);
                if (segment == null) {
                    continue;
                }
                TritonClient.TranscribeResult sonuc = triton.transcribe(segment);
                if (sonuc != null && !sonuc.pivotText().isBlank()) {
                    islePivot(segment, sonuc);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warnf("Çözümleme kuyruğunda hata: %s", e.getMessage());
            }
        }
    }

    /**
     * Pivot (İngilizce) metin hazır olur olmaz kaydedip yayınlar, SONRA 3
     * dile PARALEL çeviri isteği başlatır — "anında yayınla" deseni: bir
     * dilin çevirisi geç kalırsa diğerleri onu beklemeden yayınlanır.
     *
     * <p>Ölçüm ({@code lag.kaydet}) burada, pivot anında alınıyor: arayüz
     * altyazıyı ilk bu anda görür, çeviriler gecikse de "görünür" sayılır.
     */
    private void islePivot(SpeechSegment segment, TritonClient.TranscribeResult sonuc) {
        kaydetVeYayinla(segment, sonuc.sourceLanguage(), sonuc.confidence(),
            Map.of("en", sonuc.pivotText()));

        lag.kaydet(segment.channelId(), segment.channelName(),
            segment.endedAt(), segment.durationMs());

        LOG.infof("ALTYAZI %s [%s] %s → %s",
            segment.channelName(), segment.startedAt(),
            sonuc.sourceLanguage() == null ? "?" : sonuc.sourceLanguage(),
            sonuc.pivotText().length() > 80
                ? sonuc.pivotText().substring(0, 80) + "…" : sonuc.pivotText());

        // Pivot'un yayinlandigi an -- her dilin kendi EK gecikmesini
        // (pivottan o dilin yayinina kadar) olcmek icin baslangic noktasi.
        Instant pivotYayinAni = Instant.now();

        DIL_MODELLERI.forEach((dil, model) ->
            java.util.concurrent.CompletableFuture
                .supplyAsync(() -> triton.translate(model, sonuc.pivotText()), ceviriPool)
                .thenAccept(ceviri -> {
                    if (ceviri != null && !ceviri.isBlank()) {
                        kaydetVeYayinla(segment, sonuc.sourceLanguage(), sonuc.confidence(),
                            Map.of(dil, ceviri));
                        lag.ceviriKaydet(segment.channelName(), dil,
                            Duration.between(pivotYayinAni, Instant.now()).toMillis());
                    }
                })
                .exceptionally(e -> {
                    LOG.warnf("Çeviri hata: kanal=%s dil=%s hata=%s",
                        segment.channelName(), dil, e.getMessage());
                    return null;
                }));
    }

    /**
     * Kanalın kuyruğundan en eski bölütü alır; kuyrukta başka bölüt
     * kaldıysa kanalı {@link #hazir} sırasının SONUNA yeniden ekler —
     * round-robin adilliği burada sağlanıyor.
     */
    private SpeechSegment kanaldanAl(UUID channelId) {
        Deque<SpeechSegment> kanalKuyrugu = bekleyen.get(channelId);
        if (kanalKuyrugu == null) {
            return null;
        }
        SpeechSegment segment;
        synchronized (kanalKuyrugu) {
            segment = kanalKuyrugu.pollFirst();
            if (!kanalKuyrugu.isEmpty()) {
                hazir.add(channelId);
            }
        }
        return segment;
    }

    /**
     * Bir bölütün bir veya birden fazla dilini kaydedip yayınlar.
     *
     * <p>Triton'a geçişle pivot ve her çeviri AYRI çağrılarla, kendi hazır
     * olduğu anda gelir — bu yüzden burası tek seferlik değil, aynı bölüt
     * için BİRDEN FAZLA kez (önce {@code {"en": ...}}, sonra sırayla
     * {@code {"tr": ...}}, {@code {"de": ...}}, {@code {"ru": ...}})
     * çağrılabiliyor. {@link SubtitleService#kaydetVeyaBirlestir} bunları
     * aynı satırda birleştirip GÜNCEL haritayı döndürüyor.
     */
    private void kaydetVeYayinla(SpeechSegment segment, String kaynakDil, Float guven,
                                  Map<String, String> yeniMetinler) {
        try {
            Map<String, String> guncelMetinler = subtitles.kaydetVeyaBirlestir(
                segment.channelId(), segment.startedAt(), segment.endedAt(),
                kaynakDil, guven, yeniMetinler, segment.forceCut());

            // Once veritabani, SONRA yayin. Ters sirada olsaydi izleyici
            // altyaziyi gorur ama sayfayi yenilediginde kaybolurdu.
            broadcaster.publish(new org.example.subtitle.SubtitleEvent(
                segment.channelId(), segment.startedAt(), segment.endedAt(),
                kaynakDil, guncelMetinler, segment.forceCut()));

        } catch (Exception e) {
            // Tek bolutun/dilin kaybi hatti durdurmamali.
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

        // Grafana'da "şu an hangi kanalın kuyruğu ne kadar dolu" sorusuna
        // cevap veren gauge. Kanal adı etiket: birden fazla kanalın aynı
        // adı taşımaması channels.name'in UNIQUE olmasıyla garanti.
        var gauge = io.micrometer.core.instrument.Gauge
            .builder("altyazi_kuyruk_derinlik", () -> {
                Deque<SpeechSegment> kanalKuyrugu = bekleyen.get(channel.id);
                return kanalKuyrugu == null ? 0 : kanalKuyrugu.size();
            })
            .tag("kanal", channel.name)
            .description("O an kanal kuyruğunda bekleyen, henüz çözümlenmemiş bölüt sayısı")
            .register(meterRegistry);
        kuyrukGaugeleri.put(channel.id, gauge);

        LOG.infof("VAD başladı: %s (%s)", channel.name, channel.mediamtxPath);
    }

    private void stop(UUID channelId) {
        ChannelVadWorker worker = workers.get(channelId);
        if (worker != null) {
            worker.close();
            LOG.infof("VAD durduruldu: %s", worker.mediamtxPath());
        }
        // Kanal kapanınca bekleyen bölütleri de düşür: yayından çıkmış bir
        // kanalın eski sesini çözümlemenin kimseye faydası yok, üstüne
        // bellekte sonsuza dek dolu bir kuyruk olarak kalmasın.
        bekleyen.remove(channelId);

        // Gauge'u da kaydı da sil -- yoksa Prometheus'ta artık var olmayan
        // kanallar icin sonsuza dek "0" degerinde olu seriler birikir.
        var eskiGauge = kuyrukGaugeleri.remove(channelId);
        if (eskiGauge != null) {
            meterRegistry.remove(eskiGauge);
        }

        // Dusme sayaci (Counter) ad-hoc kaydediliyor (bkz. kuyrugaEkle) ve
        // gauge gibi tek bir referansi yok -- kanal silinen/pasife alinan
        // her kanal icin registry'de "sebep" bazinda arayip kaldiriyoruz.
        // SubtitleLagMetrics'in kendi gauge'lari (gecikme/kapsama) da ayni
        // sekilde: kanal ismini bilmeden temizlenemez. Isim yalnizca worker
        // uzerinden erisiliyor -- bu yuzden worker null ise (senkron disi
        // bir cagri) atlaniyor.
        if (worker != null) {
            String kanalAdi = worker.channelName();
            meterRegistry.find("altyazi_bolut_dusme_toplam").tag("kanal", kanalAdi)
                .meters().forEach(meterRegistry::remove);
            lag.temizle(channelId, kanalAdi);
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

        if (sttEnabled) {
            kuyrugaEkle(segment);
        }
    }

    /**
     * Bölütü kanalının kuyruğuna ekler.
     *
     * <p>İki eleme, bu sırayla: önce YAŞ — {@code butceMs}'i çoktan aşmışsa
     * hiç kuyruğa girmiyor, çünkü işlense de izleyiciye asla ulaşmayacak
     * (mutlak zaman damgası eşleşmesi geçmişte kalmış bir pencereyi bir daha
     * hiç yakalamaz). Sonra KAPASİTE — kanalın kendi kuyruğu doluysa en
     * eskisi atılır, yenisi girer.
     *
     * <p>Sessizce düşürmek, altyazının neden eksik olduğunu hiçbir yerde
     * göstermezdi. STT yetişmiyorsa bunun görünmesi şart.
     */
    private void kuyrugaEkle(SpeechSegment segment) {
        long yasMs = Duration.between(segment.endedAt(), Instant.now()).toMillis();
        if (yasMs > butceMs) {
            meterRegistry.counter("altyazi_bolut_dusme_toplam",
                "sebep", "yas", "kanal", segment.channelName()).increment();
            LOG.warnf("Bölüt bütçeyi (%d ms) aşmış, kuyruğa alınmadan düşürüldü: %s [%s, %d ms geride]",
                butceMs, segment.channelName(), segment.startedAt(), yasMs);
            return;
        }

        Deque<SpeechSegment> kanalKuyrugu =
            bekleyen.computeIfAbsent(segment.channelId(), id -> new ArrayDeque<>());
        boolean yeniSinyal;
        synchronized (kanalKuyrugu) {
            if (kanalKuyrugu.size() >= KANAL_KUYRUK_KAPASITESI) {
                SpeechSegment atilan = kanalKuyrugu.pollFirst();
                meterRegistry.counter("altyazi_bolut_dusme_toplam",
                    "sebep", "kapasite", "kanal", segment.channelName()).increment();
                LOG.warnf("Kanal kuyruğu dolu, en eski bölüt düşürüldü: %s [%s]",
                    segment.channelName(), atilan != null ? atilan.startedAt() : null);
            }
            yeniSinyal = kanalKuyrugu.isEmpty();
            kanalKuyrugu.addLast(segment);
        }
        if (yeniSinyal) {
            hazir.add(segment.channelId());
        }
    }

    /** Kapanışta tüm işçileri durdurur — açık bölütler {@code flush} ile gelir. */
    void onShutdown(@Observes ShutdownEvent event) {
        workers.values().forEach(ChannelVadWorker::close);
        workers.clear();
        if (sttPool != null) {
            sttPool.shutdownNow();
        }
        if (ceviriPool != null) {
            ceviriPool.shutdownNow();
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
