package org.example.channel;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.example.auth.AuthService;
import org.example.channel.dto.ChannelDeletionSummary;
import org.example.channel.entity.Channel;
import org.example.clip.ClipStorage;
import org.example.clip.entity.Clip;
import org.example.dvr.DvrStorage;
import org.example.dvr.entity.DvrSegment;
import org.example.exception.AppException;
import org.example.screenshot.ScreenshotStorage;
import org.example.screenshot.entity.Screenshot;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kanal silme — özet çıkarma, şifre doğrulama ve içerik temizliği.
 *
 * <h2>Neden {@link ChannelService}'ten ayrı</h2>
 * Silme artık tek satırlık bir iş değil: özet çıkarıyor, şifre doğruluyor,
 * üç ayrı kovadan nesne siliyor ve iki farklı sonuç üretiyor. {@code
 * ChannelService} kanalın kendi yaşam döngüsüyle ilgili; bunları oraya
 * koymak o sınıfı ikinci bir sorumlulukla doldururdu.
 *
 * <h2>Ne siliniyor</h2>
 * <table>
 *   <tr><th></th><th>seçenek</th><th>seçilmezse</th></tr>
 *   <tr><td>DVR segmentleri</td><td><b>yok</b> — hep silinir</td><td>—</td></tr>
 *   <tr><td>Klipler</td><td>{@code deleteClips}</td><td>kalır, kanal bağı kopar</td></tr>
 *   <tr><td>Ekran görüntüleri</td><td>{@code deleteScreenshots}</td><td>kalır, kanal bağı kopar</td></tr>
 * </table>
 *
 * <p>Klip ve ekran görüntüsü <b>ayrı ayrı</b> seçiliyor: klip emek harcanmış
 * bir çıktı, ekran görüntüsü tek tıkla yeniden alınabilir. Tek bir "içeriği
 * sil" bayrağı kullanıcıyı olmayan bir tercihe zorluyordu.
 *
 * <p><b>DVR her koşulda gidiyor.</b> Bir kayıt segmentinin tek başına anlamı
 * yok: geriye sarma "şu kanalın şu anı" demek ve kanal yoksa gösterilecek yer
 * de yok. Klipten farkı bu — klip bağımsız bir dosya, segment bir kanalın
 * parçası.
 *
 * <h2>Neden şifre</h2>
 * Silme <b>geri alınamaz</b> ve tek tıkla ulaşılabilir bir yerde duruyor.
 * Rol kontrolü "bu kişi silebilir mi" sorusunu cevaplıyor; şifre "şu an
 * gerçekten bu kişi mi ve bilerek mi" sorusunu. Açık kalmış bir oturumda
 * yanlışlıkla silinen bir kanalın 7 günlük kaydı geri gelmiyor.
 */
@ApplicationScoped
public class ChannelDeletionService {

    private static final Logger LOG = Logger.getLogger(ChannelDeletionService.class);

    /**
     * Nesneler tek tek siliniyor ama sayısı büyük olabilir (7 günlük DVR'da
     * kanal başına ~20 bin segment). Silme işi bu yüzden transaction
     * DIŞINDA ve parça parça yapılıyor: hepsini tek transaction'a almak onu
     * dakikalarca açık tutar ve veritabanı bağlantısını meşgul ederdi.
     */
    private static final int BATCH = 500;

    @Inject
    ChannelService channelService;

    @Inject
    AuthService authService;

    @Inject
    ClipStorage clipStorage;

    @Inject
    ScreenshotStorage screenshotStorage;

    @Inject
    DvrStorage dvrStorage;

    @Inject
    MediaMtxService mediaMtx;

    /**
     * Silinecek olanın dökümü.
     *
     * <p>Onay ekranında gösteriliyor. "Emin misiniz?" tek başına bilgi
     * taşımıyor; 3 klip ile 300 klip aynı karar değil.
     */
    @Transactional
    public ChannelDeletionSummary summary(UUID channelId) {
        Channel channel = require(channelId);

        List<DvrSegment> segments = DvrSegment.find("channel.id", channelId).list();
        long dvrBytes = segments.stream().mapToLong(s -> s.boyutBayt).sum();
        double dvrHours = segments.stream()
            .mapToLong(s -> java.time.Duration.between(s.basladi, s.bitti).toMillis())
            .sum() / 3_600_000.0;

        long clipBytes = Clip.<Clip>find("channel.id", channelId).stream()
            .mapToLong(c -> c.sizeBytes == null ? 0 : c.sizeBytes)
            .sum();

        // Yayin durumu MediaMTX'ten: yayindaki bir kanali silmek buyuk
        // ihtimalle kazadir ve onay ekraninda ayrica uyarilmali.
        var states = mediaMtx.pathStates();
        var state = states.get(channel.mediamtxPath);

        return new ChannelDeletionSummary(
            channel.name,
            Clip.count("channel.id", channelId),
            Screenshot.count("channel.id", channelId),
            segments.size(),
            dvrHours,
            dvrBytes,
            clipBytes,
            state != null && state.ready());
    }

    /**
     * Kanalı siler.
     *
     * <p><b>Sıra önemli:</b> önce şifre, sonra nesneler, en son satırlar.
     * Ters sırada şifre yanlışsa bile nesneler silinmiş olurdu.
     *
     * @param username      işlemi yapan — şifresi bununla doğrulanıyor
     * @param deleteClips       klipler de silinsin mi
     * @param deleteScreenshots ekran görüntüleri de silinsin mi
     */
    public void delete(UUID channelId, String username, String password,
                       boolean deleteClips, boolean deleteScreenshots) {
        // ONCE sifre. Gecersizse hicbir seye dokunulmadan cikiliyor.
        authService.verifyPassword(username, password);

        ChannelDeletionSummary ozet = summary(channelId);

        // DVR nesneleri HER KOSULDA siliniyor. Satirlar cascade ile gidecek
        // ama nesneleri MinIO'da birakmak, kimsenin ulasamayacagi ve yalnizca
        // yasam dongusu kurali doldugunda temizlenecek olu veri demek.
        long silinenSegment = removeDvrObjects(channelId);

        long silinenKlip = deleteClips ? removeClipObjects(channelId) : 0;
        long silinenEkran = deleteScreenshots ? removeScreenshotObjects(channelId) : 0;

        // Satirlarin silinmesi ve MediaMTX temizligi mevcut yolda kaliyor.
        // Silinmesi istenmeyen klip/ekran goruntusu satirlari icin FK'nin
        // SET NULL kurali bagi koparıyor (V21).
        channelService.delete(channelId);

        LOG.infof("Kanal silindi: %s — klipler %s (%d), ekran görüntüleri %s (%d), "
                + "DVR segmenti %d",
            ozet.channelName(),
            deleteClips ? "silindi" : "korundu", silinenKlip,
            deleteScreenshots ? "silindi" : "korundu", silinenEkran,
            silinenSegment);
    }

    // ------------------------------------------------------------------

    /**
     * Nesneleri parça parça siler.
     *
     * <p>Her turda transaction açılıp kapanıyor: 20 bin segmenti tek
     * transaction'da işlemek onu dakikalarca açık tutardı. Nesne silme
     * çağrıları zaten transaction dışında — MinIO geri alınamıyor.
     */
    private long removeDvrObjects(UUID channelId) {
        long toplam = 0;
        while (true) {
            List<String> anahtarlar = QuarkusTransaction.requiringNew().call(() ->
                DvrSegment.<DvrSegment>find("channel.id", channelId)
                    .page(0, BATCH).stream()
                    .map(s -> s.nesneAnahtari)
                    .toList());
            if (anahtarlar.isEmpty()) {
                return toplam;
            }
            anahtarlar.forEach(dvrStorage::remove);
            toplam += anahtarlar.size();

            // Satirlari da simdi siliyoruz: aksi halde bir sonraki tur ayni
            // anahtarlari getirir ve dongu hic bitmezdi.
            QuarkusTransaction.requiringNew().run(() ->
                DvrSegment.delete("nesneAnahtari in ?1", anahtarlar));
        }
    }

    private long removeClipObjects(UUID channelId) {
        List<String> anahtarlar = QuarkusTransaction.requiringNew().call(() -> {
            List<String> keys = new ArrayList<>();
            for (Clip clip : Clip.<Clip>find("channel.id", channelId).list()) {
                if (clip.objectKey != null) {
                    keys.add(clip.objectKey);
                }
            }
            return keys;
        });
        anahtarlar.forEach(clipStorage::delete);
        QuarkusTransaction.requiringNew().run(() -> Clip.delete("channel.id", channelId));
        return anahtarlar.size();
    }

    private long removeScreenshotObjects(UUID channelId) {
        List<String> anahtarlar = QuarkusTransaction.requiringNew().call(() -> {
            List<String> keys = new ArrayList<>();
            for (Screenshot shot : Screenshot.<Screenshot>find("channel.id", channelId).list()) {
                if (shot.objectKey != null) {
                    keys.add(shot.objectKey);
                }
            }
            return keys;
        });
        anahtarlar.forEach(screenshotStorage::delete);
        QuarkusTransaction.requiringNew().run(() -> Screenshot.delete("channel.id", channelId));
        return anahtarlar.size();
    }

    private Channel require(UUID channelId) {
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        return channel;
    }
}
