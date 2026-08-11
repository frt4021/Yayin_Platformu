package org.example.dvr;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * DVR segmentlerinin nesne depolaması.
 *
 * <h2>Neden kliplerden ayrı kova</h2>
 * İkisinin <b>yaşam döngüsü zıt</b>: klip kullanıcının istediği kalıcı bir
 * çıktı, segment 7 günde silinen ham parça. MinIO'nun yaşam döngüsü (ILM)
 * kuralları <b>kova bazlı</b> olduğu için aynı kovada dursalardı 7 günlük
 * kural kliplere de uygulanır ve kalıcı olması gereken kayıtlar silinirdi.
 *
 * <h2>Saklama süresini kim uyguluyor</h2>
 * <b>MinIO, kendi başına.</b> Eskiden bu iş MediaMTX'in
 * {@code recordDeleteAfter} ayarındaydı; kayıt oradan alınınca bir süpürge
 * yazmak gerekiyordu. Kovaya konan ILM kuralı aynı işi sunucu tarafında ve
 * bedavaya yapıyor -- silinecek nesneleri taramak için kod çalıştırmıyoruz.
 *
 * <p>Veritabanı satırları ayrı temizleniyor: ILM nesneyi siler ama
 * {@code dvr_segments} satırından haberi olmaz.
 */
@ApplicationScoped
public class DvrStorage {

    private static final Logger LOG = Logger.getLogger(DvrStorage.class);

    /**
     * Bilinmeyen uzunlukta akış yazarken parça boyutu.
     *
     * <p>Segment akışının uzunluğu <b>baştan bilinemiyor</b>: canlı yayından
     * geliyor ve ancak kesildiğinde bitiyor. MinIO SDK bu durumda kendi çok
     * parçalı yüklemesini kuruyor; S3'ün düz {@code PUT}'u ise
     * {@code Content-Length} istediği için burada kullanılamaz (ölçüldü:
     * chunked PUT'a MinIO <b>HTTP 411</b> döndürüyor ve ffmpeg bunu hata
     * saymadığı için geriye 0 baytlık nesne kalıyor).
     *
     * <p>10 MB, kliplerdeki değerle aynı: 30 sn'lik bir segment 3 Mbps'te
     * ~11 MB ettiği için çoğu segment tek parçaya sığıyor.
     */
    private static final long PART_SIZE = 10L * 1024 * 1024;

    /** MPEG-TS. Segmentler bu biçimde yazılıyor; bkz. {@link DvrRecorder}. */
    private static final String CONTENT_TYPE = "video/mp2t";

    /**
     * Anahtardaki tarih hiyerarşisi.
     *
     * <p>Gerçek dizin {@code dvr_segments} tablosu; bu düzen konsoldan
     * bakıldığında gezinebilmek ve gerekirse bir günü tek önek ile silebilmek
     * için. Düz bir liste de çalışırdı ama tek kanalın haftalık ~20 bin
     * nesnesi tek klasörde yığılırdı.
     */
    private static final DateTimeFormatter KEY_STAMP =
        DateTimeFormatter.ofPattern("yyyy/MM/dd/HH/yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    @Inject
    MinioClient minio;

    @ConfigProperty(name = "dvr.bucket")
    String bucket;

    @ConfigProperty(name = "dvr.retention-days")
    int retentionDays;

    void ensureBucket(@Observes StartupEvent event) {
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                LOG.infof("DVR kovası oluşturuldu: %s", bucket);
            }
            applyLifecycle();
        } catch (Exception e) {
            // Açılışı düşürmüyoruz: MinIO geç ayağa kalkmış olabilir ve canlı
            // yayın buna bağlı değil. Kaydedici ilk segmentte hata verir ve
            // durum log'da görünür.
            LOG.warnf(e, "DVR kovası hazırlanamadı: %s", bucket);
        }
    }

    /**
     * Saklama kuralını kovaya yazar.
     *
     * <p>Her açılışta yeniden yazılıyor: kural {@code .env}'den geliyor ve
     * süre değiştirildiğinde MinIO'ya yansıması için başka bir yol yok.
     * Yazma üzerine yazma (idempotent), aynı kural tekrar konsa da sorun yok.
     */
    private void applyLifecycle() throws Exception {
        LifecycleRule rule = new LifecycleRule(
            Status.ENABLED,
            null,
            new Expiration((java.time.ZonedDateTime) null, retentionDays, null),
            new RuleFilter(""),
            "dvr-saklama",
            null,
            null,
            null);
        minio.setBucketLifecycle(SetBucketLifecycleArgs.builder()
            .bucket(bucket)
            .config(new LifecycleConfiguration(List.of(rule)))
            .build());
        LOG.infof("DVR saklama kuralı uygulandı: %s → %d gün", bucket, retentionDays);
    }

    /**
     * Segment anahtarı üretir: {@code <kanal>/<YYYY>/<AA>/<GG>/<SS>/<zaman>.ts}
     *
     * @param channelSlug kanal adı, {@code StoragePaths.slug()}'dan geçmiş
     */
    public String keyFor(String channelSlug, Instant start) {
        return channelSlug + "/" + KEY_STAMP.format(start) + ".ts";
    }

    /**
     * Segmenti yazar.
     *
     * <p>Akış <b>bitene kadar bloklar</b> ve akış ancak segment kesildiğinde
     * bitiyor; yani bu çağrı bir segment süresi kadar sürüyor. Çağıran bunu
     * bilerek yapıyor: kaydedici zaten kanal başına ayrı bir iş parçacığı.
     *
     * @return yazılan bayt sayısı
     */
    public long put(String objectKey, InputStream data) {
        try {
            minio.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(data, -1, PART_SIZE)
                .contentType(CONTENT_TYPE)
                .build());
            return minio.statObject(io.minio.StatObjectArgs.builder()
                .bucket(bucket).object(objectKey).build()).size();
        } catch (Exception e) {
            throw AppException.internalError("DVR segmenti yazılamadı: " + objectKey, e);
        }
    }

    /** Segmenti okur — aralık çıkarma bunu kullanıyor. */
    public InputStream get(String objectKey) {
        try {
            return minio.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw AppException.internalError("DVR segmenti okunamadı: " + objectKey, e);
        }
    }

    /**
     * Segmenti siler.
     *
     * <p>Normal akışta gerekmiyor -- süresi dolanları ILM siliyor. Kanal
     * silindiğinde ise satırlar cascade ile gidiyor ve nesneleri arkada
     * bırakmamak için bu çağrılıyor.
     */
    public void remove(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            // Yutuluyor: nesne zaten ILM ile silinmiş olabilir ve silinemeyen
            // bir nesne yüzünden kanal silmeyi düşürmek orantısız olurdu.
            LOG.debugf("DVR segmenti silinemedi (%s): %s", objectKey, e.getMessage());
        }
    }
}
