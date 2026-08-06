package org.example.screenshot;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.PresignClient;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ekran görüntülerinin nesne depolaması.
 *
 * <p>Ayrı kova: galeri farklı bir yaşam döngüsüne sahip (kendi saklama
 * politikası) ve karışık bir kovada listeleme maliyeti gereksizce artardı.
 */
@ApplicationScoped
public class ScreenshotStorage {

    private static final Logger LOG = Logger.getLogger(ScreenshotStorage.class);

    @Inject
    MinioClient minio;

    /** İmzalı adresler tarayıcıda açılacağı için dışarıdan erişilen adresle üretilir. */
    @Inject
    @PresignClient
    MinioClient presignMinio;

    @ConfigProperty(name = "screenshots.bucket")
    String bucket;

    @ConfigProperty(name = "screenshots.url-ttl-hours")
    int urlTtlHours;

    void ensureBucket(@Observes StartupEvent event) {
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                LOG.infof("Ekran görüntüsü kovası oluşturuldu: %s", bucket);
            }
        } catch (Exception e) {
            // Acilisi dusurmuyoruz: MinIO gec ayaga kalkmis olabilir ve
            // uygulamanin geri kalani buna bagli degil.
            LOG.warnf(e, "Ekran görüntüsü kovası hazırlanamadı: %s", bucket);
        }
    }

    /** @return yazılan bayt sayısı */
    public long put(String objectKey, Path file, String contentType) {
        try {
            minio.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .filename(file.toString())
                .contentType(contentType)
                .build());
            return Files.size(file);
        } catch (Exception e) {
            throw AppException.internalError("Ekran görüntüsü yazılamadı: " + objectKey, e);
        }
    }

    /** Galeride {@code <img src>} ile gösterilebilir imzalı adres. */
    public String viewUrl(String objectKey) {
        return url(objectKey, null);
    }

    /** Tarayıcıyı dosyayı kaydetmeye zorlayan imzalı adres. */
    public String downloadUrl(String objectKey, String fileName) {
        return url(objectKey, fileName);
    }

    private String url(String objectKey, String downloadName) {
        try {
            var builder = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(objectKey)
                .expiry(urlTtlHours, TimeUnit.HOURS);
            if (downloadName != null) {
                builder.extraQueryParams(Map.of(
                    "response-content-disposition", "attachment; filename=\"" + downloadName + "\""));
            }
            return presignMinio.getPresignedObjectUrl(builder.build());
        } catch (Exception e) {
            throw AppException.internalError("İmzalı adres üretilemedi: " + objectKey, e);
        }
    }

    public void delete(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // Nesne silinemezse kaydi silmeyi engellemiyoruz; artik nesne
            // kalmasi, kullanicinin kaydi silememesinden iyidir.
            LOG.warnf(e, "Ekran görüntüsü silinemedi: %s", objectKey);
        }
    }
}
