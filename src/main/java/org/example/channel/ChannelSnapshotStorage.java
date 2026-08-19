package org.example.channel;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.PresignClient;
import org.example.exception.AppException;
import org.example.storage.StoragePaths;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

/**
 * Kanal ön izleme kareleri (MinIO).
 *
 * <p>Her kanal için <b>tek</b> nesne, her yakalamada üzerine yazılır — geçmiş
 * kareler tutulmuyor, bu bir arşiv değil "şu an nasıl görünüyor" göstergesi.
 * Yazan taraf {@link ChannelSnapshotWorker} (yalnızca video-worker), okuyan
 * taraf {@link ChannelService#list()} (backend) — {@code VideoStorage} ile
 * aynı ayrım: dosya backend'den geçmiyor, yalnızca imzalı adres üretiliyor.
 */
@ApplicationScoped
public class ChannelSnapshotStorage {

    private static final Logger LOG = Logger.getLogger(ChannelSnapshotStorage.class);

    @Inject
    MinioClient minio;

    /** İmzalı adres tarayıcıda açılacağı için dışarıdan erişilen adresle üretilir. */
    @Inject
    @PresignClient
    MinioClient presignMinio;

    @ConfigProperty(name = "channels.snapshot-bucket")
    String bucket;

    @ConfigProperty(name = "channels.snapshot-url-ttl-minutes")
    int urlTtlMinutes;

    void ensureBucket(@Observes StartupEvent event) {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                LOG.infof("Kanal ön izleme kovası oluşturuldu: %s", bucket);
            }
        } catch (Exception e) {
            // Acilisi dusurmuyoruz: MinIO gec ayaga kalkmis olabilir, kanal
            // listesi bundan bagimsiz calismaya devam etmeli.
            LOG.warnf(e, "Kanal ön izleme kovası hazırlanamadı: %s", bucket);
        }
    }

    private String keyFor(String mediamtxPath) {
        String slug = StoragePaths.slug(mediamtxPath);
        return (slug.isEmpty() ? mediamtxPath : slug) + ".jpg";
    }

    /** İşçinin yakaladığı kareyi yazar; aynı kanalın önceki karesinin üzerine. */
    public void put(String mediamtxPath, byte[] jpeg) {
        try {
            minio.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(keyFor(mediamtxPath))
                .stream(new ByteArrayInputStream(jpeg), jpeg.length, -1)
                .contentType("image/jpeg")
                .build());
        } catch (Exception e) {
            throw AppException.internalError("Kanal ön izleme yazılamadı: " + mediamtxPath, e);
        }
    }

    /**
     * Süreli imzalı adres. Nesnenin var olup olmadığı <b>kontrol edilmiyor</b>
     * — henüz hiç yakalanmamış bir kanal için tarayıcı 404 alır, arayüz bunu
     * {@code onError} ile ikon yer tutucuya düşerek karşılıyor. Her istekte
     * bir MinIO {@code stat} çağrısı yapmamak için bilinçli bir seçim.
     */
    public String url(String mediamtxPath) {
        try {
            return presignMinio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(keyFor(mediamtxPath))
                .expiry(urlTtlMinutes, TimeUnit.MINUTES)
                .build());
        } catch (Exception e) {
            LOG.debugf(e, "Kanal ön izleme adresi üretilemedi: %s", mediamtxPath);
            return null;
        }
    }
}
