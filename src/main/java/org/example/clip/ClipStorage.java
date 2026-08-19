package org.example.clip;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Klip dosyalarının nesne depolaması (MinIO).
 *
 * <p>Klipler diske değil nesne depolamasına yazılıyor çünkü indirme
 * <b>backend'den geçmemeli</b>: imzalı adresle kullanıcı doğrudan MinIO'dan
 * indirir. 2 saatlik bir klip 6 Mbps'te ~5.4 GB eder; bunu backend üzerinden
 * akıtmak canlı yayın mimarisindeki "video backend'den geçmez" ilkesini
 * bozardı.
 */
@ApplicationScoped
public class ClipStorage {

    private static final Logger LOG = Logger.getLogger(ClipStorage.class);

    /** MinIO'ya bilinmeyen uzunlukta akış yazarken kullanılan parça boyutu. */
    private static final long PART_SIZE = 10L * 1024 * 1024;

    @Inject
    MinioClient minio;

    /** İmzalı adresler tarayıcıda açılacağı için dışarıdan erişilen adresle üretilir. */
    @Inject
    @PresignClient
    MinioClient presignMinio;

    @ConfigProperty(name = "clips.bucket")
    String bucket;

    @ConfigProperty(name = "clips.download-url-ttl-minutes")
    int urlTtlMinutes;

    void ensureBucket(@Observes StartupEvent event) {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                LOG.infof("Klip kovası oluşturuldu: %s", bucket);
            }
        } catch (Exception e) {
            // Açılışı düşürmüyoruz: MinIO geç ayağa kalkmış olabilir ve
            // uygulamanın geri kalanı (canlı yayın, kullanıcı yönetimi) buna
            // bağlı değil. İlk klip isteğinde hata görünür olur.
            LOG.warnf(e, "Klip kovası hazırlanamadı: %s", bucket);
        }
    }

    /**
     * Akışı nesne olarak yazar. Boyut önceden bilinmediği için parça parça
     * yüklenir; dosyanın tamamı belleğe alınmaz.
     *
     * @return yazılan bayt sayısı
     */
    public long put(String objectKey, InputStream data, String contentType) {
        try {
            var response = minio.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(data, -1, PART_SIZE)
                .contentType(contentType)
                .build());
            return sizeOf(response.object());
        } catch (Exception e) {
            throw AppException.internalError("Klip depolamaya yazılamadı: " + objectKey, e);
        }
    }

    /**
     * Yerel bir dosyayı (önizleme klibi gibi) nesne olarak yazar.
     *
     * <p>{@link #put(String, InputStream, String)}'ten farkı: boyut önceden
     * biliniyor, ffmpeg zaten diske bir dosya üretti — akış sarmalamaya
     * gerek yok.
     */
    public void putFile(String objectKey, Path file, String contentType) {
        try {
            minio.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .filename(file.toString())
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw AppException.internalError("Dosya klip depolamaya yazılamadı: " + objectKey, e);
        }
    }

    /**
     * İşçinin ffmpeg'e vereceği imzalı adres — <b>iç ağ</b> üzerinden.
     *
     * <p>{@code ClipWorker} backend konteynerinin İÇİNDE çalışıyor: dış
     * adresle imzalanmış bir adres kullansaydı trafik konteynerden çıkıp
     * host üzerinden geri dönerdi (bkz. {@code VideoStorage.internalReadUrl}
     * ile aynı gerekçe).
     */
    public String internalReadUrl(String objectKey) {
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(urlTtlMinutes, TimeUnit.MINUTES)
                .build());
        } catch (Exception e) {
            throw AppException.internalError("İç okuma adresi üretilemedi: " + objectKey, e);
        }
    }

    private long sizeOf(String objectKey) {
        try {
            return minio.statObject(io.minio.StatObjectArgs.builder()
                .bucket(bucket).object(objectKey).build()).size();
        } catch (Exception e) {
            LOG.warnf(e, "Klip boyutu okunamadı: %s", objectKey);
            return 0;
        }
    }

    /**
     * Süreli imzalı <b>izleme</b> adresi.
     *
     * <p>İndirme adresinden tek farkı {@code content-disposition}
     * başlığının olmaması: o başlık varken tarayıcı videoyu oynatmak yerine
     * dosyayı indirir, {@code <video src>} çalışmaz.
     */
    public String streamUrl(String objectKey) {
        try {
            return presignMinio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(urlTtlMinutes, TimeUnit.MINUTES)
                .build());
        } catch (Exception e) {
            throw AppException.internalError("İzleme adresi üretilemedi: " + objectKey, e);
        }
    }

    /** Süreli imzalı indirme adresi. Kullanıcı doğrudan MinIO'dan indirir. */
    public String downloadUrl(String objectKey, String fileName) {
        try {
            return presignMinio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(urlTtlMinutes, TimeUnit.MINUTES)
                .extraQueryParams(java.util.Map.of(
                    "response-content-disposition", "attachment; filename=\"" + fileName + "\""))
                .build());
        } catch (Exception e) {
            throw AppException.internalError("İndirme adresi üretilemedi: " + objectKey, e);
        }
    }

    public void delete(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // Nesne zaten yoksa veya silinemiyorsa kaydı silmeyi engellemiyoruz;
            // artık nesne kalması, kullanıcının kaydı silememesinden iyidir.
            LOG.warnf(e, "Klip nesnesi silinemedi: %s", objectKey);
        }
    }
}
