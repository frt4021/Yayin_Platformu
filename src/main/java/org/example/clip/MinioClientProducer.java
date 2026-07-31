package org.example.clip;

import io.minio.MinioClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * MinIO istemcisini üretir.
 *
 * <p>Quarkiverse'ün {@code quarkus-minio} eklentisi kullanılmıyor: 3.8.x
 * sürümü Quarkus 3.37 ile build-step yapılandırma hatası veriyor, 3.9.x ise
 * SDK ile {@code NoSuchMethodError} üretiyor. Eklenti ayrıca Dev Services
 * ile kendi MinIO konteynerini açıp yapılandırılmış adresi eziyordu.
 *
 * <p>Düz SDK + elle üretilen bean bu üç sorunun hiçbirini yaşamıyor ve
 * bağlantı ayarları tek yerde, görünür.
 */
@ApplicationScoped
public class MinioClientProducer {

    @ConfigProperty(name = "minio.url")
    String url;

    @ConfigProperty(name = "minio.access-key")
    String accessKey;

    @ConfigProperty(name = "minio.secret-key")
    String secretKey;

    /**
     * Tarayıcının erişebileceği MinIO adresi.
     *
     * <p>Ayrı olması şart: backend compose ağındayken {@code minio.url}
     * {@code http://minio:9000} olur, ama imzalı indirme adresi
     * <b>tarayıcıda</b> açılacak ve tarayıcı o ismi çözemez. Adresi sonradan
     * değiştirmek de mümkün değil — S3 v4 imzası Host başlığını da imzalıyor,
     * host'u elle değiştirmek imzayı geçersiz kılar. Bu yüzden imzalama için
     * ayrı bir istemci gerekiyor.
     */
    @ConfigProperty(name = "minio.public-url")
    String publicUrl;

    @Produces
    @ApplicationScoped
    MinioClient minioClient() {
        return MinioClient.builder()
            .endpoint(url)
            .credentials(accessKey, secretKey)
            .build();
    }

    @Produces
    @ApplicationScoped
    @PresignClient
    MinioClient presignClient() {
        return MinioClient.builder()
            .endpoint(publicUrl)
            .credentials(accessKey, secretKey)
            .build();
    }

    void closePresign(@Disposes @PresignClient MinioClient client) {
        close(client);
    }

    void close(@Disposes MinioClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // Kapanışta hata önemsiz; uygulama zaten sonlanıyor.
        }
    }
}
