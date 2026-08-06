package org.example.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.clip.entity.Clip;
import org.example.exception.AppException;
import org.example.screenshot.entity.Screenshot;
import org.example.video.entity.Video;

import java.util.Locale;

/**
 * Kullanıcı başına depolama kotası.
 *
 * <p><b>Ayrı sayaç tablosu yok.</b> Boyutlar zaten {@code clips.size_bytes},
 * {@code screenshots.size_bytes} ve {@code videos.size_bytes} sütunlarında
 * duruyor; toplamları sorguyla alınıyor. Ayrı bir sayaç, her silme ve
 * eklemeden sonra tutarlı kalması gereken <b>ikinci bir doğruluk kaynağı</b>
 * olurdu ve er geç kayardı.
 *
 * <p><b>Kota dolunca yeni iş reddediliyor, var olan silinmiyor.</b> Sessizce
 * silmek kullanıcının verisini habersiz yok etmek olurdu; ne silineceğine
 * kullanıcı karar vermeli.
 */
@ApplicationScoped
public class QuotaService {

    /** {@code 0} = sınırsız. */
    @ConfigProperty(name = "storage.user-quota-bytes")
    long quotaBytes;

    @Inject
    org.example.screenshot.ScreenshotStorage screenshotStorage;

    /**
     * Kullanıcının kullanımı.
     *
     * @param quotaBytes {@code 0} ise sınırsız
     */
    public record Usage(long clipBytes, long screenshotBytes, long videoBytes,
                        long totalBytes, long quotaBytes) {

        public boolean unlimited() {
            return quotaBytes <= 0;
        }

        public int percentUsed() {
            return unlimited() ? 0 : (int) Math.min(100, totalBytes * 100 / quotaBytes);
        }

        public long remainingBytes() {
            return unlimited() ? Long.MAX_VALUE : Math.max(0, quotaBytes - totalBytes);
        }
    }

    /**
     * Kullanıcının üç türdeki toplam kullanımı.
     *
     * <p>Kütüphane videoları da <b>dahil</b>: kütüphane artık kişisel (herkes
     * kendi videosunu yüklüyor ve yalnızca kendininkini görüyor), dolayısıyla
     * kotadan muaf tutmanın gerekçesi kalmadı.
     */
    public Usage usageOf(String keycloakId) {
        long clips = Clip.totalBytesOf(keycloakId);
        long shots = Screenshot.totalBytesOf(keycloakId);
        long videos = Video.totalBytesOf(keycloakId);
        return new Usage(clips, shots, videos, clips + shots + videos, quotaBytes);
    }

    /**
     * Yeni bir iş için yer var mı.
     *
     * <p>Klip ve kayıtta boyut <b>önceden bilinmiyor</b> (dosya arka planda
     * üretiliyor), o yüzden orada yalnızca "kota zaten dolu mu" sorulabiliyor.
     * Yükleme ve ekran görüntüsünde beklenen boyut biliniyor ve hesaba
     * katılıyor.
     *
     * @param incomingBytes eklenmesi beklenen boyut; bilinmiyorsa 0
     */
    public void requireRoom(String keycloakId, long incomingBytes) {
        Usage usage = usageOf(keycloakId);
        if (usage.unlimited()) {
            return;
        }
        if (usage.totalBytes() + incomingBytes > usage.quotaBytes()) {
            throw AppException.conflict(String.format(Locale.ROOT,
                "Depolama kotanız dolu: %s / %s kullanılmış. "
                    + "Yer açmak için klip, kayıt, ekran görüntüsü veya video silin.",
                human(usage.totalBytes()), human(usage.quotaBytes())));
        }
    }

    /** Boyutu insan okur biçime çevirir; hata mesajlarında paylaşılıyor. */
    public static String human(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        }
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }
}
