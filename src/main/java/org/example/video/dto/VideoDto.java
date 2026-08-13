package org.example.video.dto;

import org.example.video.VideoStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Videonun dışarıya açılan gösterimi.
 *
 * <p><b>İzleme adresi burada yok, küçük resim adresi var.</b> Ayrım kasıtlı:
 * izleme adresi yalnızca kullanıcı oynata bastığında gerekiyor ve ayrı bir
 * uçtan ({@code /links}) alınıyor — kliplerdeki {@code ClipLinks} yaklaşımı.
 * Küçük resim ise <b>listenin kendisi</b> için gerekli ve her kart için ayrı
 * bir istek atmak ızgarayı N+1 çağrıya çevirirdi. {@code <img>} etiketi
 * Authorization başlığı gönderemediği için backend üzerinden sunmak da
 * mümkün değil. İmza hesabı yerel bir HMAC, maliyeti mikrosaniye.
 *
 * @param sizeBytes        işçi tarafından doğrulanmış gerçek boyut; yalnızca
 *                         dosya okunduktan sonra dolu
 * @param durationSeconds  ffprobe'dan; {@code ISLENIYOR} bitene kadar null
 * @param error            yalnızca {@code HATA} durumunda dolu, kullanıcıya gösterilir
 * @param thumbnailIsUpload küçük resim kullanıcı tarafından mı yüklendi.
 *                         {@code thumbnailAtSeconds} ile birlikte üç durumu
 *                         ayırıyor: ikisi de boşsa otomatik seçilmiş kare,
 *                         saniye doluysa kullanıcının seçtiği kare, bu bayrak
 *                         açıksa yüklenen görsel.
 */
public record VideoDto(
    UUID id,
    String title,
    String description,
    String originalFilename,
    String contentType,
    Long sizeBytes,
    Integer durationSeconds,
    Integer width,
    Integer height,
    VideoStatus status,
    String error,
    /** Süreli imzalı küçük resim adresi; henüz üretilmediyse {@code null}. */
    String thumbnailUrl,
    /**
     * Kısa önizleme klibinin imzalı adresi; üretilmediyse {@code null}.
     * Küçük resimle aynı gerekçeyle listede: ızgarada fare kartın üzerine
     * geldiği anda oynaması gerekiyor, o anda bir istek daha atmak gecikme
     * yaratırdı.
     */
    String previewUrl,
    boolean thumbnailIsUpload,
    Integer thumbnailAtSeconds,
    /** İzlenme sayısı — {@code /links} her çağrıldığında artar. */
    long viewCount,
    String uploadedBy,
    Instant createdAt,
    Instant completedAt
) {
}
