package org.example.clip;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.entity.Clip;
import org.example.dvr.DvrService;
import org.example.exception.AppException;
import org.example.subtitle.WebVttWriter;
import org.example.subtitle.entity.Subtitle;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Bir klip işini üreten birim. Ne zaman çalışacağına {@link ClipConsumer}
 * karar verir; burada yalnızca "işi talep et" ve "işi yap" var.
 *
 * <p><b>Doğruluk kaynağı veritabanı.</b> Redis bildirim taşır ama işin
 * durumu, deneme sayısı ve sonucu {@code clips} tablosundadır. İşi talep
 * etmek de ({@code BEKLIYOR → ISLENIYOR}, {@code SKIP LOCKED}) burada
 * yapılır: Redis en-az-bir-kez teslim ettiği için aynı iş iki kez
 * bildirilebilir, tekilliği garanti eden şey bu talep adımıdır.
 */
@ApplicationScoped
public class
ClipWorker {

    private static final Logger LOG = Logger.getLogger(ClipWorker.class);

    /** Geçici hatalarda bu sayıya kadar yeniden denenir. */
    private static final int MAX_ATTEMPTS = 3;

    @Inject
    DvrService dvrService;

    @Inject
    ClipStorage storage;

    @ConfigProperty(name = "clips.concurrency")
    int concurrency;

    /**
     * Kaydın zaman çizelgesine düşmesi için beklenecek üst süre.
     *
     * <p>Bkz. {@link #dvrBekle}. Normalde saniyenin altında dönüyor; bu sınır
     * yalnızca kesme sinyali ulaşmadığında devreye giriyor.
     */
    @ConfigProperty(name = "clips.dvr-bekleme")
    Duration dvrBekleme;

    /**
     * Tek bir işi talep eder: Redis'ten gelen bildirimin karşılığı.
     *
     * @return iş bu çağrı tarafından alındıysa {@code true}; başkası almışsa,
     *         iş artık {@code BEKLIYOR} değilse veya eşzamanlılık sınırı
     *         dolduysa {@code false}
     */
    @Transactional
    boolean claim(UUID clipId) {
        if (atCapacity()) {
            // Sınır dolu: işi BEKLIYOR bırakıyoruz, süpürücü veya boşalan bir
            // işçi alacak. Sınırsız olsaydı onlarca klip aynı anda MediaMTX'ten
            // çekilir, disk ve ağ doyar, CANLI YAYIN etkilenirdi.
            return false;
        }
        Clip clip = Clip.findById(clipId);
        if (clip == null || clip.status != ClipStatus.BEKLIYOR) {
            return false;
        }
        markRunning(clip);
        return true;
    }

    /**
     * Bekleyen işleri toplu talep eder — Redis bildiriminin ulaşmadığı
     * durumlar için güvenlik ağı.
     *
     * <p>Kısa transaction: asıl uzun iş ({@link #process}) transaction dışında
     * yapılır. Aksi halde saatlerce süren bir indirme boyunca veritabanı
     * bağlantısı ve satır kilidi tutulurdu.
     */
    @Transactional
    List<UUID> claimBatch() {
        long running = Clip.count("status", ClipStatus.ISLENIYOR);
        int slots = (int) (concurrency - running);
        if (slots <= 0) {
            return List.of();
        }

        List<Clip> batch = Clip.lockNextPending(slots);
        batch.forEach(this::markRunning);
        return batch.stream().map(clip -> clip.id).toList();
    }

    private boolean atCapacity() {
        return Clip.count("status", ClipStatus.ISLENIYOR) >= concurrency;
    }

    private void markRunning(Clip clip) {
        clip.status = ClipStatus.ISLENIYOR;
        clip.startedAt = Instant.now();
        clip.attempts++;
    }

    /**
     * Klibi MediaMTX'ten çekip depolamaya yazar.
     *
     * <p>Akış halinde aktarılır — dosya belleğe veya geçici diske alınmaz.
     * 2 saatlik bir klip 6 Mbps'te ~5.4 GB eder; tamponlamak sunucuyu düşürürdü.
     */
    void process(UUID clipId) {
        ClipJob job = loadJob(clipId);
        if (job == null) {
            return;
        }
        try {
            Instant bas = job.start();
            Instant bit = job.end();

            // ARALIK secimi disindaki kliplerde (manuel ve planli kayit)
            // istenen aralik ile diske yazilmis aralik ayni degil: kayit
            // basladiginda kaydedicinin baglanmasi, bittiginde suren segmentin
            // kapanmasi zaman aliyor. Bekleme ve kirpma BURADA -- durdurma
            // isteginde degil; kullanici klibin uretilmesini beklemiyor.
            if (job.origin() != ClipOrigin.ARALIK) {
                var kirpilmis = dvrBekle(clipId, job);
                bas = kirpilmis.start();
                bit = kirpilmis.end();
                if (!bas.equals(job.start()) || !bit.equals(job.end())) {
                    araligiGuncelle(clipId, bas, bit);
                    LOG.infof("Klip aralığı kayda göre kırpıldı: %s → %s (istenen %s → %s)",
                        bas, bit, job.start(), job.end());
                }
            }

            Duration duration = Duration.between(bas, bit);
            // Akis DOGRUDAN aliniyor. readEntity() burada CALISMIYOR: o
            // istemci yanitlari icin, sunucuda kurulmus bir Response'ta
            // entity zaten nesnenin kendisi. Yasandi -- klipler
            // "Request could not be mapped to type InputStream" ile dusuyordu.
            try (InputStream body = dvrService.extractStream(
                    job.channelId(), bas, duration)) {

                long size = storage.put(job.objectKey(), body, "video/mp4");
                List<String> uretilenDiller = altyaziUret(job.channelId(), job.objectKey(), bas, bit);
                markReady(clipId, job.objectKey(), size,
                    uretilenDiller.isEmpty() ? null : String.join(",", uretilenDiller));
                LOG.infof("Klip hazır: %s (%,d bayt)", clipId, size);
            }
        } catch (Exception e) {
            handleFailure(clipId, e);
        }
    }

    /**
     * İstenen aralığın zaman çizelgesine düşmesini bekler ve kaydedilene
     * kırpar.
     *
     * <h2>Neden beklemek gerekiyor</h2>
     * Segment <b>kapanmadan</b> çizelgeye satır yazılmıyor. Kaydı durduran
     * kullanıcı, kaydettiği anı içeren segment henüz açıkken soruyor:
     * çizelgede o aralık yok. Kesme sinyali ({@code DvrSignalEvent.KES})
     * segmenti hemen kapattırıyor, ama sinyal başka bir konteynere gidiyor ve
     * ulaşmayabilir — bekleme o durumun ağı.
     *
     * <h2>Neden her turda yeni transaction</h2>
     * Aynı oturumla sorulsaydı Hibernate ilk sonucu önbelleğe alır ve
     * <b>yeni yazılan segment hiç görünmezdi</b>; döngü boşuna dönerdi.
     *
     * <p><b>Bedeli:</b> bekleyen iş bir işçi yuvası tutuyor
     * ({@code clips.concurrency}, varsayılan 2). Sinyal çalıştığında bu süre
     * saniyenin altında; çalışmadığında segment süresi kadar. Sınırın
     * varsayılanı bu yüzden segment süresinin biraz üstünde.
     *
     * @throws org.example.exception.AppException hiç örtüşen kayıt yoksa —
     *         {@code NOT_FOUND} olduğu için yeniden denenmiyor
     */
    private org.example.dvr.dto.TimelineSpan dvrBekle(UUID clipId, ClipJob job) {
        long bitisAni = System.currentTimeMillis() + dvrBekleme.toMillis();
        java.util.Optional<org.example.dvr.dto.TimelineSpan> son;
        boolean beklendi = false;

        while (true) {
            son = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> dvrService.clampToRecorded(
                    job.channelId(), job.start(), job.end()));

            // Istenen sonun tamami yazilmis: beklemeye gerek yok.
            if (son.isPresent() && !son.get().end().isBefore(job.end())) {
                break;
            }
            if (System.currentTimeMillis() >= bitisAni) {
                // Sure doldu. Elde bir sey varsa ONUNLA devam ediliyor:
                // eksik bir klip, hic klip olmamasindan iyi.
                break;
            }
            beklendi = true;
            uyu();
        }

        if (son.isEmpty()) {
            throw AppException.notFound(
                "Bu aralıkta kayıt bulunamadı. Kanal o sırada yayında olmamış "
                    + "ya da kaydedici hiç başlamamış olabilir.");
        }
        if (beklendi) {
            LOG.debugf("Klip %s: kayıt çizelgeye düşene kadar beklendi.", clipId);
        }
        return son.get();
    }

    /** Yoklama aralığı: kesme sinyaliyle segment saniyeler içinde düşüyor. */
    private void uyu() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Klip beklerken kesildi", e);
        }
    }

    // ------------------------------------------------------------------

    /** İşin transaction dışında kullanılacak alanları — lazy proxy taşımamak için. */
    private record ClipJob(UUID channelId, Instant start, Instant end, String objectKey,
                           ClipOrigin origin) {
    }

    @Transactional
    ClipJob loadJob(UUID clipId) {
        Clip clip = Clip.findById(clipId);
        if (clip == null) {
            return null;
        }
        // Kanal silinmis olabilir (V21: ON DELETE SET NULL). clip.channel null
        // iken mediamtxPath ya da id'ye erismek NPE atardi ve is sessizce
        // HATA'ya dusup "clip.channel null" mesajiyla gorunurdu.
        //
        // Kanal yokken DVR'den de okunamiyor (segmentler kanal id'sine gore);
        // bu durum zaten geri dondurulemez. Yine de NPE yerine acik bir hata
        // veriyoruz — channelName hâlâ duruyor, klibin nereden geldigi
        // bilinebiliyor.
        if (clip.channel == null) {
            throw AppException.notFound(
                "Kanal silinmiş; bu klibin kaynağı artık erişilemez: " + clip.channelName);
        }
        // <kullanici>/<kanal>/<id>.mp4 -- icerik zaten kullaniciya ozel,
        // klasor duzeni de onu yansitiyor.
        String key = org.example.storage.StoragePaths.channelFile(
            clip.requestedBy, clip.channel.mediamtxPath, clip.id + ".mp4");
        clip.objectKey = key;
        // Kanal kimligi BURADA cozuluyor: process() transaction disinda
        // calisiyor ve orada lazy kanal proxy'sine erismek
        // ContextNotActiveException veriyor.
        return new ClipJob(clip.channel.id, clip.startAt, clip.endAt, key, clip.origin);
    }

    /**
     * Kırpılmış aralığı klibe yazar.
     *
     * <p>Kullanıcı listede <b>gerçekten elde ettiği</b> aralığı görmeli;
     * istenen aralık orada kalsaydı süre dosyayla uyuşmazdı.
     */
    @Transactional
    void araligiGuncelle(UUID clipId, Instant bas, Instant bit) {
        Clip clip = Clip.findById(clipId);
        if (clip != null) {
            clip.startAt = bas;
            clip.endAt = bit;
        }
    }

    @Transactional
    void markReady(UUID clipId, String objectKey, long size, String subtitleLangs) {
        Clip clip = Clip.findById(clipId);
        if (clip == null) {
            return;
        }
        clip.status = ClipStatus.HAZIR;
        clip.objectKey = objectKey;
        clip.sizeBytes = size;
        clip.subtitleLangs = subtitleLangs;
        clip.completedAt = Instant.now();
        clip.error = null;
    }

    /**
     * Klibin zaman aralığındaki canlı altyazıyı (§10.1 kararı: her zaman
     * üretiliyor) WebVTT'ye çevirip klibin yanına sidecar dosya olarak
     * yükler. Hangi dillerin üretileceği HARDCODE değil — o aralıkta
     * gerçekten veri taşıyan diller neyse onlar (STT_TARGET_LANGS
     * değişse bile burası değişmeden çalışır).
     *
     * <p><b>Hata klibin üretimini durdurmaz</b> — altyazı ikincil bir
     * özellik, video sağlamsa klip yine HAZIR olmalı (VideoWorker'daki
     * önizleme toleransıyla aynı ilke).
     *
     * @return üretilen dillerin listesi; hiçbiri üretilemediyse boş
     */
    private List<String> altyaziUret(UUID channelId, String objectKey, Instant bas, Instant bit) {
        try {
            List<Subtitle> altyazilar = Subtitle.between(channelId, bas, bit);
            if (altyazilar.isEmpty()) {
                return List.of();
            }

            var diller = new TreeSet<String>();
            for (Subtitle a : altyazilar) {
                if (a.metinler != null) {
                    diller.addAll(a.metinler.keySet());
                }
            }

            List<String> uretilen = new ArrayList<>();
            for (String dil : diller) {
                List<WebVttWriter.VttCue> cues = new ArrayList<>();
                for (Subtitle a : altyazilar) {
                    String metin = a.metinler == null ? null : a.metinler.get(dil);
                    if (metin == null || metin.isBlank()) {
                        continue;
                    }
                    // Klip sinirlarina KIRPILIYOR: bir altyazi satiri klip
                    // baslamadan once baslamis ya da bitmeden sonra bitmis
                    // olabilir (Subtitle.between KESISENLERI seciyor, tam
                    // icerilenleri degil).
                    Instant cueStart = a.baslangic.isBefore(bas) ? bas : a.baslangic;
                    Instant cueEnd = a.bitis.isAfter(bit) ? bit : a.bitis;
                    if (!cueEnd.isAfter(cueStart)) {
                        continue;
                    }
                    cues.add(new WebVttWriter.VttCue(
                        Duration.between(bas, cueStart), Duration.between(bas, cueEnd), metin));
                }
                if (cues.isEmpty()) {
                    continue;
                }
                String vtt = WebVttWriter.yaz(cues);
                String vttKey = objectKey.replace(".mp4", "-altyazi-" + dil + ".vtt");
                storage.put(vttKey,
                    new ByteArrayInputStream(vtt.getBytes(StandardCharsets.UTF_8)), "text/vtt");
                uretilen.add(dil);
            }
            return uretilen;
        } catch (Exception e) {
            LOG.warnf(e, "Klip altyazısı üretilemedi, klip yine de hazır olacak.");
            return List.of();
        }
    }

    @Transactional
    void handleFailure(UUID clipId, Exception cause) {
        Clip clip = Clip.findById(clipId);
        if (clip == null) {
            return;
        }
        boolean permanent = isPermanent(cause) || clip.attempts >= MAX_ATTEMPTS;

        if (permanent) {
            clip.status = ClipStatus.HATA;
            clip.error = cause.getMessage();
            clip.completedAt = Instant.now();
            LOG.errorf(cause, "Klip üretilemedi (kalıcı): %s", clipId);
        } else {
            // Geçici hata: kuyruğa geri koy, bir sonraki turda yeniden denensin.
            clip.status = ClipStatus.BEKLIYOR;
            clip.startedAt = null;
            LOG.warnf(cause, "Klip üretimi başarısız, yeniden denenecek (%d/%d): %s",
                clip.attempts, MAX_ATTEMPTS, clipId);
        }
    }

    /**
     * Yeniden denemenin anlamsız olduğu hatalar. "Kayıt bulunamadı" tekrar
     * denemekle düzelmez; ağ hatası düzelebilir.
     */
    private boolean isPermanent(Exception cause) {
        return cause instanceof AppException app
            && app.getErrorCode() == org.example.exception.ErrorCode.NOT_FOUND;
    }
}
