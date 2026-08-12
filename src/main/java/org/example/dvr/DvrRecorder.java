package org.example.dvr;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.MediaMtxService;
import org.example.channel.entity.Channel;
import org.example.clip.entity.ActiveRecording;
import org.example.clip.entity.ScheduledRecording;
import org.example.dvr.entity.DvrSegment;
import org.example.storage.StoragePaths;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DVR kayıt işçilerini kanal yayın durumuyla eşitler.
 *
 * <h2>Neden yoklama</h2>
 * Kanalın yayına girip çıkışı olay olarak yayınlanmıyor; MediaMTX'in path
 * durumu tek doğru kaynak. Yayında olmayan bir path'e bağlanan ffmpeg
 * <b>sessizce bekler</b> — hata vermez, veri de gelmez. Aynı gerekçe VAD
 * tarafında da geçerli ve orada da aynı desen kullanılıyor.
 *
 * <h2>Hangi kanallar kaydediliyor</h2>
 * Yayında <b>ve</b> {@code dvrEnabled} olanlar. İkisi ayrı: kanal yayında
 * olabilir ama geriye sarma istenmiyor olabilir; DVR kapalı bir kanalı
 * kaydetmek diski ve MinIO'yu boşuna doldururdu.
 *
 * <h2>Nerede çalışır</h2>
 * <b>ffmpeg gerektirir.</b> Backend imajında ffmpeg yok; bu servis yalnızca
 * {@code video-worker} gibi ffmpeg içeren bir konteynerde açılıyor
 * ({@code dvr.recorder-enabled}). Aynı jar iki konteynerde çalıştığı için
 * bayrak olmadan backend de kayıt almaya çalışır ve her segment iki kez
 * yazılırdı.
 */
@ApplicationScoped
public class DvrRecorder {

    private static final Logger LOG = Logger.getLogger(DvrRecorder.class);

    @Inject
    MediaMtxService mediaMtx;

    @Inject
    DvrStorage storage;

    @Inject
    DvrSignal signal;

    @ConfigProperty(name = "dvr.recorder-enabled")
    boolean enabled;

    @ConfigProperty(name = "dvr.segment-seconds")
    int segmentSeconds;

    @ConfigProperty(name = "mediamtx.rtsp-url")
    String rtspBase;

    private final Map<UUID, ChannelDvrRecorder> workers = new ConcurrentHashMap<>();

    /**
     * Kaydedici iş parçacıkları ve sinyal işleri.
     *
     * <p>Doğrudan alanda kuruluyor: {@code newCachedThreadPool} iş verilene
     * kadar iş parçacığı açmıyor, dolayısıyla tembel kurulumun kazancı yok.
     * Tembel olsaydı sinyal ile eşitleme aynı kilidi paylaşırdı ve sinyal
     * kanalı eşitleme boyunca bloke kalırdı.
     */
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    /** Sinyalle gelen emirleri işleyen abonelik; kapanışta bırakılıyor. */
    private io.quarkus.redis.datasource.pubsub.PubSubCommands.RedisSubscriber abonelik;

    /**
     * Sinyal dinlemeye başlar — yalnızca kaydedicinin açık olduğu süreçte.
     *
     * <p>Backend'de de açılsaydı emirler orada da alınır ve {@link #workers}
     * boş olduğu için sessizce yutulurdu; {@link DvrSignalEvent.Tur#BASLAT}
     * ise gereksiz bir MediaMTX sorgusu doğururdu.
     */
    void baslangic(@Observes io.quarkus.runtime.StartupEvent event) {
        if (!enabled) {
            return;
        }
        abonelik = signal.dinle(this::sinyalGeldi);
        LOG.info("DVR sinyal aboneliği açıldı.");
    }

    /**
     * Gelen emri uygular.
     *
     * <p><b>İş Redis abonesinin iş parçacığında yapılmıyor.</b> Eşitleme
     * MediaMTX'e HTTP çağrısı yapıyor; orada bloke olmak tüm sinyal kanalını
     * durdururdu. Havuza atılıyor.
     */
    private void sinyalGeldi(DvrSignalEvent emir) {
        switch (emir.tur()) {
            case BASLAT -> pool.submit(() -> {
                LOG.debugf("DVR sinyali: kanal %s için eşitleme istendi", emir.channelId());
                try {
                    // requiringNew SART: bu is parcaciginda islem baglami yok
                    // ve senkronize() Panache sorgulari yapiyor. sync()
                    // uzerinden cagirmak da CALISMAZ -- kendi uzerinden cagri
                    // CDI kesicisini atlar ve @Transactional uygulanmaz.
                    QuarkusTransaction.requiringNew().run(this::senkronize);
                } catch (RuntimeException e) {
                    LOG.warnf("DVR sinyaliyle eşitleme başarısız: %s", e.getMessage());
                }
            });
            case KES -> {
                ChannelDvrRecorder worker = workers.get(emir.channelId());
                if (worker != null) {
                    worker.kesSegmenti();
                } else {
                    // Kaydedici yok: ya kanal hic kaydedilmiyor ya da BASLAT
                    // sinyali henuz isini bitirmedi. Kayip bir sey yok --
                    // kesilecek segment de yok.
                    LOG.debugf("Kesme sinyali geldi ama kaydedici yok: %s", emir.channelId());
                }
            }
        }
    }

    /**
     * Yayında olan DVR kanallarıyla aktif işçileri eşitler.
     *
     * <p>{@code SKIP}: bir tik uzarsa (MediaMTX yavaş, çok kanal) ikincisi
     * başlamıyor; aksi halde aynı kanal için iki kaydedici açılabilir ve
     * segmentler ikiye katlanırdı.
     */
    @Scheduled(every = "{dvr.sync-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void sync() {
        senkronize();
    }

    /**
     * Eşitlemenin gövdesi.
     *
     * <p>Ayrı metot: zamanlanmış {@link #sync()} dışında sinyalle de
     * çağrılıyor ve {@code SKIP} yalnızca zamanlanmış çağrıları serileştiriyor.
     * <b>{@code synchronized}</b> bu yüzden burada: iki yolun aynı anda
     * {@link #workers} üzerinde çalışması aynı kanal için iki kaydedici
     * açabilirdi.
     */
    synchronized void senkronize() {
        if (!enabled) {
            return;
        }

        Map<String, org.example.channel.dto.MediaMtxPathList.Item> states = mediaMtx.pathStates();
        if (states.isEmpty()) {
            // MediaMTX'e ulasilamiyor. Isciler KAPATILMIYOR: anlik bir
            // aksaklikta tum kanallarin kaydini kesmek, birkac saniye eski
            // bilgiyle devam etmekten kotu -- kaydin deligi geri gelmez.
            LOG.debug("MediaMTX path durumu alınamadı, DVR eşitlemesi atlandı");
            return;
        }

        Map<UUID, Channel> hedef = new HashMap<>();
        for (Channel channel : Channel.listActive()) {
            if (!kaydedilmeli(channel)) {
                continue;
            }
            var state = states.get(channel.mediamtxPath);
            if (state != null && state.ready()) {
                hedef.put(channel.id, channel);
            }
        }

        // Yayindan cikanlari ya da DVR'i kapatilanlari durdur.
        workers.keySet().removeIf(id -> {
            if (hedef.containsKey(id)) {
                return false;
            }
            ChannelDvrRecorder worker = workers.get(id);
            if (worker != null) {
                worker.close();
            }
            return true;
        });

        // Yeni gelenleri baslat.
        for (var entry : hedef.entrySet()) {
            if (workers.containsKey(entry.getKey())) {
                continue;
            }
            start(entry.getValue());
        }
    }

    /**
     * Bu kanal kaydedilmeli mi.
     *
     * <p>İki gerekçe var ve ikisi de yeterli:
     * <ul>
     *   <li><b>Kanalın kendi DVR'ı açık</b> — sürekli kayıt isteniyor.</li>
     *   <li><b>Geçici kayıt sürüyor</b> — kanalın DVR'ı kapalı ama manuel ya
     *       da planlı bir kayıt işi çalışıyor. Eskiden bu durumda MediaMTX'in
     *       kaydı geçici açılıyordu; artık sinyal bu satırların kendisi
     *       ({@code ChannelRecordingGate}).</li>
     * </ul>
     *
     * <p>Sinyalin veritabanından okunması <b>zorunlu</b>: kaydı başlatan taraf
     * backend, kaydeden taraf video-worker — ayrı konteynerler, doğrudan
     * çağrı yok.
     */
    private boolean kaydedilmeli(Channel channel) {
        return channel.dvrEnabled
            || ActiveRecording.anyTemporaryOn(channel.id)
            || ScheduledRecording.anyTemporaryOn(channel.id);
    }

    private void start(Channel channel) {
        // Kanal adi yerine mediamtxPath: klip ve ekran goruntusu de anahtarda
        // bunu kullaniyor, kovaya bakan biri ayni adi gormeli.
        String slug = StoragePaths.slug(channel.mediamtxPath);
        if (slug.isEmpty()) {
            slug = channel.id.toString();
        }

        var worker = new ChannelDvrRecorder(
            channel.id, channel.name, channel.mediamtxPath, slug, rtspBase,
            segmentSeconds * 1000L, storage, this::onSegment);
        workers.put(channel.id, worker);
        pool.submit(worker);
    }

    /**
     * Yüklenen segmenti zaman çizelgesine yazar.
     *
     * <p>{@code requiringNew}: bu geri çağırım kaydedici iş parçacığından
     * geliyor ve orada bir işlem bağlamı yok. Zamanlanmış {@link #sync()}
     * işleminin bağlamına katılmak da mümkün değil — o çoktan bitmiş oluyor.
     *
     * <p>Hata <b>yutuluyor</b>: nesne MinIO'ya zaten yazıldı ve tek bir satırın
     * yazılamaması yüzünden kaydı durdurmak, o kanalın tüm geçmişini
     * kaybettirirdi. Satırsız kalan nesne zaman çizelgesinde görünmez ve
     * süresi dolunca ILM tarafından silinir.
     */
    void onSegment(UUID channelId, String objectKey, Instant start, Instant end, long bytes) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                Channel channel = Channel.findById(channelId);
                if (channel == null) {
                    return;
                }
                DvrSegment segment = new DvrSegment();
                segment.channel = channel;
                segment.basladi = start;
                segment.bitti = end;
                segment.nesneAnahtari = objectKey;
                segment.boyutBayt = bytes;
                segment.persist();
            });
        } catch (RuntimeException e) {
            LOG.warnf("DVR segmenti çizelgeye yazılamadı (%s): %s", objectKey, e.getMessage());
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (abonelik != null) {
            try {
                abonelik.unsubscribe();
            } catch (RuntimeException e) {
                LOG.debugf("DVR sinyal aboneliği kapatılamadı: %s", e.getMessage());
            }
            abonelik = null;
        }
        workers.values().forEach(ChannelDvrRecorder::close);
        workers.clear();
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Aktif kaydedici sayısı — sağlık ve testler için. */
    public int activeCount() {
        return workers.size();
    }
}
