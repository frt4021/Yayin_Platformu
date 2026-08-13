package org.example.video;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.exception.AppException;
import org.example.user.entity.AppUser;
import org.example.video.dto.CreateVideoRequest;
import org.example.video.dto.UpdateVideoRequest;
import org.example.video.dto.UploadTicket;
import org.example.video.dto.VideoDto;
import org.example.video.dto.VideoLinks;
import org.example.video.entity.Video;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Video kütüphanesinin iş mantığı.
 *
 * <p>Yükleme <b>iki adımlı</b>: önce kayıt açılıp imzalı adres verilir,
 * dosya doğrudan depolamaya gider, sonra tamamlanma bildirilir. Dosya hiçbir
 * aşamada backend'den geçmez — 5 GB'lık bir dosyayı backend üzerinden
 * akıtmak, canlı yayın API'siyle aynı süreci dakikalarca meşgul ederdi.
 *
 * <p>Metadata ve küçük resim üretimi bu sınıfta değil; işçi tarafından
 * arka planda yapılır.
 */
@ApplicationScoped
public class VideoService {

    private static final Logger LOG = Logger.getLogger(VideoService.class);

    /** Dosya adından alınan uzantı; anahtara girmeden önce daraltılıyor. */
    private static final Pattern SAFE_EXTENSION = Pattern.compile("^[a-z0-9]{1,5}$");

    @Inject
    VideoStorage storage;

    @Inject
    org.example.storage.QuotaService quota;

    /**
     * Dinleyici AFTER_SUCCESS ile bağlı: bildirim ancak transaction commit
     * edildikten sonra gider. Öncesinde gönderilseydi işçi henüz görünmeyen
     * bir satırı okumaya çalışırdı.
     */
    @Inject
    Event<VideoQueuedEvent> queued;

    @ConfigProperty(name = "videos.max-upload-bytes")
    long maxUploadBytes;

    /**
     * Kabul edilen uzantılar. Bu <b>erken bir süzgeç</b>, güvenlik sınırı
     * değil: uzantı yalan söyleyebilir ve imzalı adrese herhangi bir bayt
     * dizisi yazılabilir. Gerçek doğrulama işçide, ffprobe ile yapılır.
     * Buradaki amacı, kullanıcıya dosyayı yüklemeden önce hızlı geri bildirim
     * vermek — 4 GB yükleyip sonra "bu format desteklenmiyor" demek zalimce olurdu.
     */
    @ConfigProperty(name = "videos.allowed-extensions")
    List<String> allowedExtensions;

    /**
     * Bu süreden eski ve hâlâ tamamlanmamış yüklemeler süpürülür.
     * Yükleme süresinden uzun olmalı: 5 GB'lık bir dosya yavaş bağlantıda
     * uzun sürer ve devam eden bir yüklemeyi iptal etmek en kötü sonuç olurdu.
     */
    @ConfigProperty(name = "videos.upload-timeout-minutes")
    int uploadTimeoutMinutes;

    /** Küçük resim backend'den geçtiği için sınır dar tutuluyor. */
    @ConfigProperty(name = "videos.max-thumbnail-bytes")
    long maxThumbnailBytes;

    // ------------------------------------------------------------------
    // Yükleme
    // ------------------------------------------------------------------

    /**
     * Yüklemeyi başlatır: kaydı {@code YUKLENIYOR} olarak açar ve imzalı bir
     * PUT adresi döner.
     *
     * <p>Boyut kontrolü istemcinin <b>beyanına</b> göre yapılıyor. Bu bir
     * güven değil, nezaket: gerçek boyut yükleme bitince depolamadan okunup
     * yeniden doğrulanır ({@link #completeUpload}). Beyanı burada denetlemek,
     * kullanıcının 6 GB'lık dosyayı yükleyip sonunda reddedilmesini önlüyor.
     */
    @Transactional
    public UploadTicket startUpload(CreateVideoRequest req, String keycloakId) {
        if (req.sizeBytes() != null && req.sizeBytes() > maxUploadBytes) {
            throw AppException.badRequest(
                "Dosya en fazla " + humanSize(maxUploadBytes) + " olabilir "
                    + "(seçilen: " + humanSize(req.sizeBytes()) + ").");
        }

        // Yukleme boyutu biliniyor; kotaya sigmayacaksa 5 GB'i bosuna
        // yukletmeden burada reddediliyor.
        quota.requireRoom(keycloakId, req.sizeBytes() == null ? 0 : req.sizeBytes());

        String extension = extensionOf(req.fileName());

        Video video = new Video();
        video.title = req.title().trim();
        video.description = blankToNull(req.description());
        video.originalFilename = req.fileName().trim();
        video.contentType = blankToNull(req.contentType());
        // Anahtar SUNUCUDA uretiliyor. Istemcinin verdigi dosya adindan
        // turetilseydi yol ayraci veya baska bir kaydin anahtari
        // gonderilebilir, imzali adres o nesneyi ezmeye yarardi.
        //
        // Kova adi anahtarin icinde TEKRARLANMIYOR: "videolar" kovasinda
        // "videolar/..." anahtari, adreslerde videolar/videolar/... gibi
        // kafa karistirici bir yol uretiyordu.
        // Sahip ONCE atanmali: anahtar kullanici klasoruyle basliyor.
        video.uploadedBy = requireLocalUser(keycloakId);
        // Kutuphane videosunun kanali yok: <kullanici>/<uuid>/kaynak.<uzanti>
        // Klasor icin ayri bir UUID uretiliyor; kaydin kendi kimligi persist
        // oncesi henuz yok. Kucuk resim ve onizleme bu yoldan turetildigi
        // icin ayrica tasinmalari gerekmiyor.
        video.objectKey = org.example.storage.StoragePaths.userFile(
            video.uploadedBy, UUID.randomUUID().toString(), "kaynak." + extension);
        video.status = VideoStatus.YUKLENIYOR;
        video.updatedAt = Instant.now();
        video.persist();

        LOG.infof("Video yüklemesi başlatıldı: %s (%s)", video.title, video.objectKey);
        return new UploadTicket(
            video.id,
            storage.uploadUrl(video.objectKey),
            video.contentType,
            Instant.now().plus(Duration.ofMinutes(uploadTimeoutMinutes)));
    }

    /**
     * Yüklemenin bittiğini bildirir; kayıt işlenmeye alınır.
     *
     * <p>İstemcinin sözüne değil <b>depolamaya</b> bakılıyor: nesne gerçekten
     * var mı, boyutu ne. Bu çağrı hiç gelmezse süpürücü aynı işi yapar
     * ({@link #reconcileStaleUploads}) — yani bildirim bir hızlandırma,
     * doğruluk kaynağı değil.
     */
    @Transactional
    public VideoDto completeUpload(UUID id, String keycloakId, boolean isAdmin) {
        // Sahiplik burada da dogrulaniyor: uc artik giris yapmis herkese acik,
        // yoksa bir kullanici baskasinin yuklemesini tamamlayip isleme
        // sokabilirdi.
        Video video = requireVisible(id, keycloakId, isAdmin);
        if (video.status != VideoStatus.YUKLENIYOR) {
            // Süpürücü önce davranmış olabilir; tekrarlanan çağrı hata değil.
            return toDto(video);
        }
        var stat = storage.stat(video.objectKey)
            .orElseThrow(() -> AppException.badRequest(
                "Yüklenen dosya depolamada bulunamadı. Yükleme tamamlanmamış olabilir."));

        if (stat.size() > maxUploadBytes) {
            storage.delete(video.objectKey);
            fail(video, "Dosya boyut sınırını aşıyor: " + humanSize(stat.size()));
            throw AppException.badRequest(
                "Dosya en fazla " + humanSize(maxUploadBytes) + " olabilir.");
        }

        video.sizeBytes = stat.size();
        video.status = VideoStatus.ISLENIYOR;
        video.updatedAt = Instant.now();

        queued.fire(new VideoQueuedEvent(video.id));
        LOG.infof("Video işlenmeye alındı: %s (%,d bayt)", video.title, stat.size());
        return toDto(video);
    }

    /**
     * Yarım kalmış yüklemeleri düzeltir. İşçinin süpürücüsü çağırır.
     *
     * <p>Dosya backend'den geçmediği için yüklemenin bittiğini yalnızca
     * tarayıcı haber verebiliyor. Kullanıcı sekmeyi kapatır, ağ koparsa
     * kayıt sonsuza kadar {@code YUKLENIYOR}'da kalırdı.
     *
     * @return durumu değişen kayıt sayısı
     */
    @Transactional
    public int reconcileStaleUploads() {
        Instant cutoff = Instant.now().minus(uploadTimeoutMinutes, ChronoUnit.MINUTES);
        List<Video> stale = Video.staleUploads(cutoff, 50);
        int changed = 0;
        for (Video video : stale) {
            var stat = storage.stat(video.objectKey);
            if (stat.isPresent()) {
                // Dosya aslinda yuklenmis, yalnizca bildirim ulasmamis.
                video.sizeBytes = stat.get().size();
                video.status = VideoStatus.ISLENIYOR;
                video.updatedAt = Instant.now();
                queued.fire(new VideoQueuedEvent(video.id));
                LOG.infof("Yarım kalan bildirim tamamlandı: %s", video.id);
            } else {
                fail(video, "Yükleme tamamlanmadı.");
                LOG.infof("Tamamlanmayan yükleme iptal edildi: %s", video.id);
            }
            changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    /**
     * Kütüphane listesi.
     *
     * <p>Yönetici hepsini görür, diğerleri yalnızca kendi yüklediklerini —
     * kliplerdeki kuralın aynısı. Kütüphane kişisel bir arşiv olarak
     * kurgulandığı için varsayılan kapalı.
     */
    public List<VideoDto> list(String query, int offset, int limit,
                               String keycloakId, boolean isAdmin) {
        // Kutuphane HERKESE ACIK: giris yapmis her kullanici tum videolari
        // gorur. Klip ve ekran goruntusunden ayriliyor -- onlar kisisel kayit
        // icerigi, kutuphane ise paylasilan bir arsiv. Yukleme yetkisi
        // ayrica kisitli (Yonetici/Moderator), goruntuleme degil.
        return Video.search(query, null, offset, limit)
            .stream().map(this::toDto).toList();
    }

    public VideoDto get(UUID id, String keycloakId, boolean isAdmin) {
        // Okuma herkese acik; sahiplik yalnizca DEGISTIRMEYI kisitliyor.
        return toDto(require(id));
    }

    /**
     * İzleme, indirme ve küçük resim adresleri.
     *
     * <p>Listede değil ayrı bir uçta: her video için imza hesaplamak
     * gereksiz olurdu ve kullanıcı listeye bakarken adreslerin süresi
     * işlemeye başlardı.
     *
     * <p>İzlenme sayısı burada artırılıyor — kullanıcı "Oynat" düğmesine
     * bastığında. Gerçek oynatma başlamamış olabilir ama izlenme niyetini
     * yeterince yansıtıyor.
     */
    @Transactional
    public VideoLinks links(UUID id, String keycloakId, boolean isAdmin) {
        // Izleme ve indirme adresleri de herkese acik.
        Video video = require(id);
        if (!video.isPlayable()) {
            throw AppException.badRequest(
                "Video henüz hazır değil (durum: " + video.status + ").");
        }
        // Sayaci artir: bu, kullanici oynat düğmesine bastiginda gerceklesiyor.
        video.viewCount++;
        String name = downloadNameOf(video);
        return new VideoLinks(
            storage.streamUrl(video.objectKey),
            storage.downloadUrl(video.objectKey, name),
            video.thumbnailKey == null ? null : storage.thumbnailUrl(video.thumbnailKey),
            name);
    }

    /**
     * Başlık, açıklama ve küçük resim anını günceller.
     *
     * <p>Dosyanın kendisi, boyutu, süresi ve çözünürlüğü değiştirilemez:
     * bunlar işçinin dosyayı okuyarak tespit ettiği gerçekler, kullanıcı
     * tercihi değil.
     *
     * <p>Küçük resim anı değişirse kayıt yeniden işlenmeye alınır.
     * <b>İşçi sözleşmesi:</b> bu yalnızca küçük resmi ilgilendiren bir
     * yeniden çalıştırma olduğu için, üretim başarısız olursa eski küçük
     * resim korunup durum {@code HAZIR}'a döndürülmeli — video dosyası
     * sapasağlam dururken kaydı {@code HATA}'ya düşürmek onu izlenemez kılardı.
     */
    @Transactional
    public VideoDto update(UUID id, UpdateVideoRequest req, String keycloakId, boolean isAdmin) {
        Video video = requireVisible(id, keycloakId, isAdmin);
        video.title = req.title().trim();
        video.description = blankToNull(req.description());
        video.updatedAt = Instant.now();

        boolean thumbnailChanged = req.thumbnailAtSeconds() != null
            && !req.thumbnailAtSeconds().equals(video.thumbnailAtSeconds);

        if (thumbnailChanged) {
            requireWithinDuration(video, req.thumbnailAtSeconds());
            video.thumbnailAtSeconds = req.thumbnailAtSeconds();
            if (video.status == VideoStatus.HAZIR) {
                video.status = VideoStatus.ISLENIYOR;
                video.attempts = 0;
                queued.fire(new VideoQueuedEvent(video.id));
            }
        }

        LOG.infof("Video güncellendi: %s", video.title);
        return toDto(video);
    }

    /**
     * Kullanıcının yüklediği görseli küçük resim olarak kullanır.
     *
     * <p>Videodan yakalanan hiçbir karenin uygun olmadığı durumlar için —
     * kapak tasarımı, logo, karanlık başlayan bir çekim.
     *
     * <p>Bu dosya <b>backend üzerinden</b> geçiyor, imzalı adresle değil:
     * birkaç yüz kilobaytlık bir görsel için iki adımlı imza dansı kurmak
     * gereksiz karmaşıklık olurdu. Video dosyasının backend'den geçmemesinin
     * sebebi boyutuydu; burada o sebep yok.
     *
     * <p>Yükleme, işçinin ürettiği kareyi <b>kalıcı olarak</b> devralır:
     * {@code thumbnailAtSeconds} temizleniyor, dolayısıyla sonraki bir
     * güncelleme kareyi yeniden üretmeye çalışmıyor.
     */
    @Transactional
    public VideoDto uploadThumbnail(UUID id, java.nio.file.Path file, String contentType,
                                    long size, String keycloakId, boolean isAdmin) {
        Video video = requireVisible(id, keycloakId, isAdmin);

        String extension = imageExtensionOf(contentType);
        if (size <= 0) {
            throw AppException.badRequest("Boş dosya yüklendi.");
        }
        if (size > maxThumbnailBytes) {
            throw AppException.badRequest(
                "Görsel en fazla " + humanSize(maxThumbnailBytes) + " olabilir.");
        }

        String previousKey = video.thumbnailKey;
        String key = thumbnailDirOf(video.objectKey) + "kucukresim-ozel." + extension;
        storage.putFile(key, file, contentType);

        video.thumbnailKey = key;
        video.thumbnailIsUpload = true;
        // Kare ani temizleniyor: kullanici artik bir kare secmis degil.
        // Birakilsaydi bir sonraki duzenlemede "degisti" sayilip isci
        // kullanicinin gorselini ezerdi.
        video.thumbnailAtSeconds = null;
        video.updatedAt = Instant.now();

        // Eski kucuk resim artik sahipsiz; anahtar degistigi icin uzerine
        // yazilmadi ve kalirsa depolamada birikirdi.
        if (previousKey != null && !previousKey.equals(key)) {
            storage.delete(previousKey);
        }

        LOG.infof("Küçük resim yüklendi: %s (%s, %,d bayt)", video.title, contentType, size);
        return toDto(video);
    }

    @Transactional
    public void delete(UUID id, String keycloakId, boolean isAdmin) {
        Video video = requireVisible(id, keycloakId, isAdmin);
        if (video.status == VideoStatus.ISLENIYOR) {
            throw AppException.conflict("İşlenmekte olan video silinemez.");
        }
        String title = video.title;
        storage.delete(video.objectKey);
        if (video.thumbnailKey != null) {
            storage.delete(video.thumbnailKey);
        }
        if (video.previewKey != null) {
            storage.delete(video.previewKey);
        }
        video.delete();
        LOG.infof("Video silindi: %s (%s)", title, id);
    }

    // ------------------------------------------------------------------

    private Video require(UUID id) {
        Video video = Video.findById(id);
        if (video == null) {
            throw AppException.notFound("Video bulunamadı: " + id);
        }
        return video;
    }

    /**
     * Kaydı getirir ve erişim hakkını doğrular.
     *
     * <p>Yalnızca <b>değiştiren</b> uçlarda kullanılıyor: kütüphaneyi görmek
     * herkese açık, ama başkasının videosunu düzenlemek ya da silmek değil.
     * Yönetici tümünü yönetebilir.
     *
     * <p>"Bulunamadı" değil "yasak" dönüyor: videonun varlığı zaten id'yi
     * bilene belli. Kliplerdeki {@code requireVisible} ile aynı gerekçe.
     */
    private Video requireVisible(UUID id, String keycloakId, boolean isAdmin) {
        Video video = require(id);
        if (!isAdmin && !video.uploadedBy.keycloakId.equals(keycloakId)) {
            throw AppException.forbidden("Bu videoya erişiminiz yok.");
        }
        return video;
    }

    /** Süresi bilinen bir videoda, seçilen kare anı videonun dışında olamaz. */
    private void requireWithinDuration(Video video, int seconds) {
        if (video.durationSeconds != null && seconds >= video.durationSeconds) {
            throw AppException.badRequest(
                "Seçilen an videonun süresinden uzun: " + seconds + " sn / "
                    + video.durationSeconds + " sn.");
        }
    }

    private void fail(Video video, String reason) {
        video.status = VideoStatus.HATA;
        video.error = reason;
        video.completedAt = Instant.now();
        video.updatedAt = Instant.now();
    }

    /**
     * Dosya adından uzantıyı çıkarır ve kabul listesine karşı doğrular.
     *
     * <p>Uzantı anahtara giren tek istemci kaynaklı parça olduğu için
     * daraltılmış bir kümeye zorlanıyor.
     */
    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SAFE_EXTENSION.matcher(ext).matches() || !allowedExtensions.contains(ext)) {
            throw AppException.badRequest(
                "Desteklenmeyen dosya türü: '" + ext + "'. Kullanılabilir: "
                    + String.join(", ", allowedExtensions));
        }
        return ext;
    }

    /**
     * Yüklenen görselin uzantısı — <b>bildirilen içerik tipinden</b>, dosya
     * adından değil. Dosya adı istemciden geliyor ve anahtara girecek bir
     * değerin oradan türetilmesi yol ayracı sokmaya açık kapı bırakırdı.
     */
    private static String imageExtensionOf(String contentType) {
        return switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw AppException.badRequest(
                "Desteklenmeyen görsel türü: " + contentType + ". JPEG, PNG veya WebP yükleyin.");
        };
    }

    /** Küçük resimler kaynak dosyayla aynı klasörde durur. */
    private static String thumbnailDirOf(String objectKey) {
        return objectKey.substring(0, objectKey.lastIndexOf('/') + 1);
    }

    /** İndirilirken kullanılacak ad; anahtar bir uuid olduğu için ona güvenilmez. */
    private static String downloadNameOf(Video video) {
        if (video.originalFilename != null && !video.originalFilename.isBlank()) {
            return video.originalFilename;
        }
        return video.title.replaceAll("[^A-Za-z0-9._-]", "_") + ".mp4";
    }

    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
    }

    /**
     * Küçük resmin yazılacağı anahtar; kaynak dosyayla aynı klasörde.
     * İşçi kullanır.
     */
    public static String thumbnailKeyFor(String objectKey) {
        return thumbnailDirOf(objectKey) + "kucukresim.jpg";
    }

    /** Önizleme klibinin anahtarı; kaynak dosyayla aynı klasörde. İşçi kullanır. */
    public static String previewKeyFor(String objectKey) {
        return thumbnailDirOf(objectKey) + "onizleme.mp4";
    }

    VideoDto toDto(Video video) {
        return new VideoDto(
            video.id,
            video.title,
            video.description,
            video.originalFilename,
            video.contentType,
            video.sizeBytes,
            video.durationSeconds,
            video.width,
            video.height,
            video.status,
            video.error,
            video.thumbnailKey == null ? null : storage.thumbnailUrl(video.thumbnailKey),
            video.previewKey == null ? null : storage.thumbnailUrl(video.previewKey),
            video.thumbnailIsUpload,
            video.thumbnailAtSeconds,
            video.viewCount,
            video.uploadedBy == null ? null : video.uploadedBy.username,
            video.createdAt,
            video.completedAt
        );
    }

    /**
     * Yönetici <b>başkasının videosunu da yönetebilir</b> (düzenler, siler).
     *
     * <p>Görmek için bu artık gerekmiyor: kütüphane paylaşılan bir arşiv ve
     * giriş yapmış herkes tümünü görüyor. Moderatör yönetici sayılmıyor —
     * video yükleyebilir ama başkasınınkine dokunamaz.
     */
    public static boolean isAdmin(Set<String> roles) {
        return roles.contains(org.example.user.Roles.YONETICI);
    }
}
