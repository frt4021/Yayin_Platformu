package org.example.video;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.PresignClient;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Video ve küçük resim dosyalarının nesne depolaması (MinIO).
 *
 * <p>Kliplerden ayrı bir sınıf ve ayrı bir kova: klip TTL'i indirme için
 * kısa (15 dk) tutulmuş, oysa video oynatmada aynı süre videonun ortasında
 * kopmaya yol açar (bkz. {@link #streamUrl}). Ayrı kova ayrıca ileride
 * farklı yaşam döngüsü politikası tanımlamayı mümkün kılıyor.
 *
 * <p><b>Dosya backend'den geçmiyor.</b> Yükleme imzalı PUT adresiyle
 * doğrudan tarayıcıdan, indirme imzalı GET adresiyle doğrudan MinIO'dan.
 */
@ApplicationScoped
public class VideoStorage {

    private static final Logger LOG = Logger.getLogger(VideoStorage.class);

    /** Küçük resim yüklerken kullanılan parça boyutu; dosyalar zaten küçük. */
    private static final long PART_SIZE = 5L * 1024 * 1024;

    @Inject
    MinioClient minio;

    /** İmzalı adresler tarayıcıda açılacağı için dışarıdan erişilen adresle üretilir. */
    @Inject
    @PresignClient
    MinioClient presignMinio;

    @ConfigProperty(name = "videos.bucket")
    String bucket;

    @ConfigProperty(name = "videos.upload-url-ttl-minutes")
    int uploadTtlMinutes;

    @ConfigProperty(name = "videos.stream-url-ttl-hours")
    int streamTtlHours;

    void ensureBucket(@Observes StartupEvent event) {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                LOG.infof("Video kovası oluşturuldu: %s", bucket);
            }
        } catch (Exception e) {
            // Açılışı düşürmüyoruz: MinIO geç ayağa kalkmış olabilir ve
            // uygulamanın geri kalanı (canlı yayın, radyo) buna bağlı değil.
            // İlk yükleme isteğinde hata görünür olur.
            LOG.warnf(e, "Video kovası hazırlanamadı: %s", bucket);
        }
    }

    /**
     * Tarayıcının dosyayı doğrudan yazacağı imzalı PUT adresi.
     *
     * <p>Adres <b>tek bir anahtara</b> ve <b>tek bir yönteme</b> kapsanmış;
     * süresi kısa. Sızsa bile yapılabilecek tek şey, zaten o kullanıcı adına
     * açılmış kayda ait nesneyi yazmak.
     *
     * <p>{@code Content-Type} imzaya <b>dahil edilmiyor</b>. Dahil edilseydi
     * tarayıcının birebir aynı başlığı göndermesi şart olurdu; en ufak farkta
     * MinIO 403 döner ve hata istemcide "erişim reddedildi" gibi görünür —
     * teşhis edilmesi zor bir kırılganlık. Bunun bedeli, nesnenin yanlış
     * içerik tipiyle kaydedilebilmesi; işçi dosyayı incelerken bunu düzeltir.
     */
    public String uploadUrl(String objectKey) {
        try {
            return presignMinio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(bucket)
                .object(objectKey)
                .expiry(uploadTtlMinutes, TimeUnit.MINUTES)
                .build());
        } catch (Exception e) {
            throw AppException.internalError("Yükleme adresi üretilemedi: " + objectKey, e);
        }
    }

    /**
     * Süreli imzalı <b>izleme</b> adresi.
     *
     * <p>TTL saat cinsinden ve bilinçli olarak uzun: kısa tutulursa oynatma
     * başlar ama kullanıcı süre dolduktan sonra ileri sardığında yeni bir
     * range isteği gider ve 403 alır — arayüzde bu, videonun ortasında
     * bozulması gibi görünür. Adres zaten tek nesneye ve salt okumaya
     * kapsanmış durumda.
     */
    public String streamUrl(String objectKey) {
        return get(objectKey, null, streamTtlHours, TimeUnit.HOURS);
    }

    /**
     * Süreli imzalı indirme adresi.
     *
     * <p>İzleme adresinden tek farkı {@code content-disposition} başlığı:
     * o başlık varken tarayıcı videoyu oynatmak yerine dosyayı indirir,
     * {@code <video src>} çalışmaz.
     */
    public String downloadUrl(String objectKey, String fileName) {
        return get(objectKey, fileName, streamTtlHours, TimeUnit.HOURS);
    }

    /** Küçük resim adresi; video adresiyle aynı ömürde. */
    public String thumbnailUrl(String objectKey) {
        return get(objectKey, null, streamTtlHours, TimeUnit.HOURS);
    }

    /**
     * İşçinin ffmpeg'e vereceği imzalı adres — <b>iç ağ</b> üzerinden.
     *
     * <p>Tarayıcıya giden adresler dış adresle imzalanmak zorunda (S3 v4
     * imzası Host başlığını da kapsıyor). Ama işçi konteynerin içinde:
     * dış adresle imzalanmış bir adres kullansaydı trafik konteynerden
     * çıkıp host üzerinden geri dönerdi — gereksiz bir sıçrama ve host
     * ağ yapılandırmasına bağımlılık. İç adres hem daha hızlı hem
     * makinenin IP'si değiştiğinde kırılmıyor.
     */
    public String internalReadUrl(String objectKey) {
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(streamTtlHours, TimeUnit.HOURS)
                .build());
        } catch (Exception e) {
            throw AppException.internalError("İç okuma adresi üretilemedi: " + objectKey, e);
        }
    }

    private String get(String objectKey, String downloadName, int ttl, TimeUnit unit) {
        try {
            var builder = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(ttl, unit);
            if (downloadName != null) {
                builder.extraQueryParams(Map.of(
                    "response-content-disposition", "attachment; filename=\"" + downloadName + "\""));
            }
            return presignMinio.getPresignedObjectUrl(builder.build());
        } catch (Exception e) {
            throw AppException.internalError("İmzalı adres üretilemedi: " + objectKey, e);
        }
    }

    /**
     * Nesnenin depolamadaki gerçek durumu.
     *
     * <p>Yüklemenin bittiğini <b>yalnızca bu</b> kanıtlar. İstemcinin
     * "tamamlandı" bildirimi bir hızlandırma; kullanıcı sekmeyi kapatırsa
     * hiç gelmez, ya da dosya yarım yüklenmişken gelebilir.
     *
     * @return nesne yoksa {@link Optional#empty()}
     */
    public Optional<StatObjectResponse> stat(String objectKey) {
        try {
            return Optional.of(minio.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(objectKey).build()));
        } catch (ErrorResponseException e) {
            // "Yok" bir hata değil, cevabın kendisi: süpürücü tam olarak bunu soruyor.
            return Optional.empty();
        } catch (Exception e) {
            throw AppException.upstreamError("Nesne durumu okunamadı: " + objectKey, e);
        }
    }

    /**
     * Dosyanın <b>yalnızca başındaki</b> baytları okur.
     *
     * <p>MP4'te {@code moov} atomunun konumunu anlamak için kullanılıyor.
     * Kutular sıralı bir yapı olduğu için ilk birkaç kilobayt yeterli —
     * 4 GB'lık bir dosyayı indirmeye gerek yok.
     */
    public byte[] readHead(String objectKey, int length) {
        try (InputStream in = minio.getObject(GetObjectArgs.builder()
            .bucket(bucket).object(objectKey).offset(0L).length((long) length).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw AppException.upstreamError("Dosya başlığı okunamadı: " + objectKey, e);
        }
    }

    /**
     * Yerel bir dosyayı nesnenin üzerine yazar. faststart yeniden düzenlemesi
     * sonrası kullanılıyor.
     *
     * @return yazılan bayt sayısı
     */
    public long putFile(String objectKey, Path file, String contentType) {
        try {
            minio.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .filename(file.toString())
                .contentType(contentType == null ? "video/mp4" : contentType)
                .build());
            return Files.size(file);
        } catch (Exception e) {
            throw AppException.internalError("Dosya depolamaya yazılamadı: " + objectKey, e);
        }
    }

    /** Küçük resmi yazar. İşçi tarafından çağrılır. */
    public void put(String objectKey, InputStream data, String contentType) {
        try {
            minio.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(data, -1, PART_SIZE)
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw AppException.internalError("Depolamaya yazılamadı: " + objectKey, e);
        }
    }

    public void delete(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // Nesne zaten yoksa veya silinemiyorsa kaydı silmeyi engellemiyoruz;
            // artık nesne kalması, kullanıcının kaydı silememesinden iyidir.
            LOG.warnf(e, "Nesne silinemedi: %s", objectKey);
        }
    }
}
