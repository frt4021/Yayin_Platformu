package org.example.video;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.exception.AppException;
import org.example.exception.ErrorCode;
import org.example.video.entity.Video;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bir video işini üreten birim: metadata çıkarma, küçük resim ve gerekiyorsa
 * {@code faststart} düzenlemesi. Ne zaman çalışacağına {@link VideoConsumer}
 * karar verir.
 *
 * <p><b>Doğruluk kaynağı veritabanı.</b> Redis bildirim taşır ama işin
 * durumu, deneme sayısı ve sonucu {@code videos} tablosundadır. İşi talep
 * etmek de ({@code SKIP LOCKED}) burada yapılır: Redis en-az-bir-kez teslim
 * ettiği için aynı iş iki kez bildirilebilir.
 *
 * <p><b>Küçük resim tazeleme sözleşmesi.</b> Zaten {@code HAZIR} olmuş bir
 * kaydın küçük resmi yeniden üretilirken hata olursa kayıt {@code HATA}'ya
 * <b>düşürülmez</b>: video dosyası sapasağlam duruyor, onu izlenemez kılmak
 * orantısız olurdu. Eski küçük resim korunur ve durum {@code HAZIR}'a döner.
 */
@ApplicationScoped
public class VideoWorker {

    private static final Logger LOG = Logger.getLogger(VideoWorker.class);

    /** Geçici hatalarda bu sayıya kadar yeniden denenir. */
    private static final int MAX_ATTEMPTS = 3;

    /** MP4 kutu sırasını görmek için yeterli; tüm dosyayı indirmeye gerek yok. */
    private static final int HEAD_BYTES = 64 * 1024;

    /** Otomatik kare: videonun başı sıklıkla siyah ya da logo oluyor. */
    private static final double AUTO_THUMBNAIL_RATIO = 0.10;
    private static final int AUTO_THUMBNAIL_MIN_SECONDS = 3;

    @Inject
    VideoStorage storage;

    @Inject
    MediaTools media;

    @ConfigProperty(name = "videos.concurrency")
    int concurrency;

    @ConfigProperty(name = "videos.preview-seconds")
    int previewSeconds;

    @ConfigProperty(name = "videos.preview-width")
    int previewWidth;

    /**
     * Tek bir işi talep eder.
     *
     * @return iş bu çağrı tarafından alındıysa {@code true}
     */
    /**
     * Tek bir işi talep eder.
     *
     * <p>Eşzamanlılık burada <b>sayılmıyor</b>: {@link VideoConsumer} sabit
     * boyutlu bir iş parçacığı havuzu kullandığı için aynı anda en fazla
     * {@code videos.concurrency} kadar {@link #process} çağrısı olabiliyor.
     * Kliplerde ayrıca bir veritabanı sayacı var çünkü orada "kuyrukta"
     * ({@code BEKLIYOR}) ve "işleniyor" ({@code ISLENIYOR}) ayrı durumlar;
     * burada ikisi tek durumda toplandığı için sayım zaten mümkün değil.
     *
     * @return iş bu çağrı tarafından alındıysa {@code true}
     */
    @Transactional
    boolean claim(UUID videoId) {
        Video video = Video.findById(videoId);
        if (video == null || video.status != VideoStatus.ISLENIYOR) {
            return false;
        }
        video.attempts++;
        video.updatedAt = Instant.now();
        return true;
    }

    /**
     * Bekleyen işleri toplu talep eder — Redis bildiriminin ulaşmadığı
     * durumlar için. Havuz boyutundan fazlası alınmıyor, aksi halde süpürücü
     * işçilerin baş edemeyeceği kadar işi aynı anda başlatırdı.
     */
    @Transactional
    List<UUID> claimBatch() {
        List<Video> batch = Video.lockNextPending(concurrency);
        batch.forEach(v -> {
            v.attempts++;
            v.updatedAt = Instant.now();
        });
        return batch.stream().map(v -> v.id).toList();
    }

    /**
     * İşi yapar: inceler, gerekiyorsa yeniden düzenler, küçük resmi üretir.
     *
     * <p>Kaynak dosya <b>imzalı bir HTTP adresi</b> üzerinden okunuyor.
     * ffprobe ve ffmpeg range istekleriyle yalnızca gereken bölümleri çeker;
     * dosyanın tamamı indirilmez (faststart yeniden yazımı hariç — o
     * kaçınılmaz olarak tam dosyayı okur).
     */
    void process(UUID videoId) {
        Job job = loadJob(videoId);
        if (job == null) {
            return;
        }

        Path remuxed = null;
        try {
            // Ic ag adresi: isci konteynerin icinde, dis adresle imzalanmis
            // bir adres trafigi host uzerinden gereksizce dolastirirdi.
            String source = storage.internalReadUrl(job.objectKey());

            MediaTools.Probe probe = media.probe(source);
            if (!probe.hasVideo()) {
                // Imzali adrese herhangi bir bayt yazilabilir; yuklenen seyin
                // gercekten video oldugunu ancak burada dogruluyoruz.
                throw AppException.badRequest(
                    "Dosyada video akışı yok. Desteklenen bir video dosyası yükleyin.");
            }

            long size = job.sizeBytes();

            // faststart yalnizca MP4 ailesinde anlamli. Kutu sirasi dosyanin
            // basindan okunuyor; moov sondaysa tarayici oynatmaya baslamadan
            // tum dosyayi indirmeye calisir.
            if (probe.isMp4Family()
                && media.needsFastStart(storage.readHead(job.objectKey(), HEAD_BYTES))) {
                LOG.infof("moov atomu sonda, faststart uygulanıyor: %s", videoId);
                remuxed = media.remuxFastStart(source);
                size = storage.putFile(job.objectKey(), remuxed, job.contentType());
                // Yeniden yazilan dosyanin adresi degismedi ama icerigi degisti;
                // sonraki adimlar taze halden okusun.
                source = storage.internalReadUrl(job.objectKey());
            }

            int at = thumbnailSecond(job.thumbnailAtSeconds(), probe.durationSeconds());

            // Kullanici kendi gorselini yuklediyse kare URETILMIYOR: uretip
            // atmak bosa is, uretip yazmak da kullanicinin secimini ezmek
            // olurdu. Onizleme klibi yine de uretiliyor -- o gorselden
            // bagimsiz.
            String thumbKey = null;
            if (!job.thumbnailIsUpload()) {
                byte[] jpeg = media.thumbnail(source, at);
                thumbKey = VideoService.thumbnailKeyFor(job.objectKey());
                storage.put(thumbKey, media.toStream(jpeg), "image/jpeg");
            }

            String previewKey = buildPreview(job.objectKey(), source, at, probe.durationSeconds());

            markReady(videoId, thumbKey, previewKey, size, probe);
            LOG.infof("Video hazır: %s (%s, %d sn, %dx%d)",
                videoId, job.objectKey(), probe.durationSeconds() == null ? 0 : probe.durationSeconds(),
                probe.width() == null ? 0 : probe.width(), probe.height() == null ? 0 : probe.height());
        } catch (Exception e) {
            handleFailure(videoId, e);
        } finally {
            media.deleteQuietly(remuxed);
        }
    }

    // ------------------------------------------------------------------

    /** Transaction dışında kullanılacak alanlar — lazy proxy taşımamak için. */
    private record Job(
        String objectKey,
        String contentType,
        Integer thumbnailAtSeconds,
        long sizeBytes,
        boolean thumbnailIsUpload
    ) {
    }

    @Transactional
    Job loadJob(UUID videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return null;
        }
        return new Job(
            video.objectKey,
            video.contentType,
            video.thumbnailAtSeconds,
            video.sizeBytes == null ? 0 : video.sizeBytes,
            video.thumbnailIsUpload);
    }

    /**
     * Önizleme klibini üretip yükler.
     *
     * <p><b>Hata ölümcül değil.</b> Önizleme bir kolaylık; üretilemezse video
     * yine izlenebilir ve kart küçük resme düşer. Bu yüzden istisna yukarı
     * taşınmıyor — taşınsaydı sağlam bir video, yalnızca önizlemesi
     * üretilemediği için {@code HATA}'ya düşerdi.
     *
     * @return önizleme anahtarı, üretilemezse {@code null}
     */
    private String buildPreview(String objectKey, String source, int atSeconds,
                                Integer durationSeconds) {
        Path clip = null;
        try {
            // Video onizleme suresinden kisaysa elde olani al; -t fazlaysa
            // ffmpeg dosyanin sonuna kadar yazip duruyor, hata vermiyor.
            int length = durationSeconds == null
                ? previewSeconds
                : Math.max(1, Math.min(previewSeconds, durationSeconds - atSeconds));

            clip = media.previewClip(source, atSeconds, length, previewWidth);
            String key = VideoService.previewKeyFor(objectKey);
            storage.putFile(key, clip, "video/mp4");
            return key;
        } catch (RuntimeException e) {
            LOG.warnf(e, "Önizleme klibi üretilemedi, kart küçük resme düşecek: %s", objectKey);
            return null;
        } finally {
            media.deleteQuietly(clip);
        }
    }

    /**
     * Otomatik kare anı: sürenin %10'u, en az 3. saniye.
     *
     * <p>Videonun ilk saniyeleri sıklıkla siyah ya da logo oluyor; 0. saniyeyi
     * almak çoğu videoda boş bir kare üretirdi.
     */
    private static int thumbnailSecond(Integer requested, Integer durationSeconds) {
        if (requested != null) {
            return durationSeconds == null
                ? requested
                : Math.min(requested, Math.max(0, durationSeconds - 1));
        }
        if (durationSeconds == null || durationSeconds <= AUTO_THUMBNAIL_MIN_SECONDS) {
            return 0;
        }
        int at = (int) Math.round(durationSeconds * AUTO_THUMBNAIL_RATIO);
        return Math.max(AUTO_THUMBNAIL_MIN_SECONDS, Math.min(at, durationSeconds - 1));
    }

    @Transactional
    void markReady(UUID videoId, String thumbnailKey, String previewKey, long size,
                   MediaTools.Probe probe) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return;
        }
        if (thumbnailKey != null) {
            if (video.thumbnailIsUpload) {
                // Yaris: is baslarken kullanici gorseli yoktu, isleme
                // sirasinda yuklendi. Kullanicinin secimi kazanir; uretilen
                // kare sahipsiz kalmasin diye siliniyor.
                storage.delete(thumbnailKey);
            } else {
                video.thumbnailKey = thumbnailKey;
            }
        }
        // Onizleme uretilemediyse eskisini SILMIYORUZ: kucuk resim tazeleme
        // yeniden calistiginda calisan bir onizlemeyi kaybetmek anlamsiz olurdu.
        if (previewKey != null) {
            video.previewKey = previewKey;
        }
        video.sizeBytes = size;
        video.durationSeconds = probe.durationSeconds();
        video.width = probe.width();
        video.height = probe.height();
        video.status = VideoStatus.HAZIR;
        video.error = null;
        video.completedAt = Instant.now();
        video.updatedAt = Instant.now();
    }

    @Transactional
    void handleFailure(UUID videoId, Exception cause) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return;
        }

        // Tazeleme basarisiz oldu: video dosyasi saglam, yalnizca yeni kucuk
        // resim uretilemedi. Kaydi HATA'ya dusurmek onu izlenemez kilardi.
        //
        // Olcut completedAt, thumbnailKey DEGIL: kullanici yukleme sirasinda
        // kendi gorselini koyduysa kayit hic islenmeden de bir kucuk resme
        // sahip olur ve ilk isleme hatasi yanlislikla "tazeleme" sayilirdi --
        // hic dogrulanmamis bir dosya HAZIR gorunurdu.
        if (video.completedAt != null) {
            video.status = VideoStatus.HAZIR;
            video.updatedAt = Instant.now();
            LOG.warnf(cause, "Küçük resim tazelenemedi, eskisi korunuyor: %s", videoId);
            return;
        }

        boolean permanent = isPermanent(cause) || video.attempts >= MAX_ATTEMPTS;
        if (permanent) {
            video.status = VideoStatus.HATA;
            video.error = cause.getMessage();
            video.completedAt = Instant.now();
            video.updatedAt = Instant.now();
            LOG.errorf(cause, "Video işlenemedi (kalıcı): %s", videoId);
        } else {
            // Gecici hata: ISLENIYOR'da birakiliyor, supurucu yeniden alacak.
            video.updatedAt = Instant.now();
            LOG.warnf(cause, "Video işleme başarısız, yeniden denenecek (%d/%d): %s",
                video.attempts, MAX_ATTEMPTS, videoId);
        }
    }

    /**
     * Yeniden denemenin anlamsız olduğu hatalar. "Video akışı yok" tekrar
     * denemekle düzelmez; ağ hatası düzelebilir.
     */
    private boolean isPermanent(Exception cause) {
        return cause instanceof AppException app
            && (app.getErrorCode() == ErrorCode.BAD_REQUEST
            || app.getErrorCode() == ErrorCode.NOT_FOUND);
    }
}
