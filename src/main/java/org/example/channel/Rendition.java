package org.example.channel;

import org.example.exception.AppException;

import java.util.List;

/**
 * Bir kanaldan üretilecek düşük çözünürlüklü sürüm.
 *
 * <p>Kaynağın verdiğinden <b>yüksek</b> çözünürlük üretilemez — büyütme
 * yalnızca dosya boyutunu artırır, ayrıntı kazandırmaz. Bu yüzden ladder
 * her zaman aşağı doğrudur.
 *
 * @param suffix  MediaMTX path'ine eklenecek son ek ({@code kanal1_720p})
 * @param width   hedef genişlik
 * @param height  hedef yükseklik
 * @param bitrate ffmpeg bit hızı ({@code 2500k})
 */
public record Rendition(String suffix, int width, int height, String bitrate) {

    /**
     * {@code 720p|1280x720|2500k,480p|854x480|1000k} biçimini çözer.
     *
     * <p>Ayraç olarak virgül ve dikey çizgi seçildi: MicroProfile Config
     * virgülü liste ayracı sayıyor, dikey çizgi ise çözünürlük ve bit hızı
     * gösteriminde geçmiyor.
     */
    public static List<Rendition> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(spec.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(Rendition::parseOne)
            .toList();
    }

    private static Rendition parseOne(String entry) {
        String[] parts = entry.split("\\|");
        if (parts.length != 3) {
            throw AppException.internalError(
                "Geçersiz rendition tanımı: '" + entry + "'. Beklenen biçim: ad|GENISLIKxYUKSEKLIK|bithizi", null);
        }
        String[] size = parts[1].toLowerCase().split("x");
        if (size.length != 2) {
            throw AppException.internalError(
                "Geçersiz çözünürlük: '" + parts[1] + "'. Beklenen biçim: 1280x720", null);
        }
        try {
            return new Rendition(parts[0].trim(),
                Integer.parseInt(size[0].trim()), Integer.parseInt(size[1].trim()),
                parts[2].trim());
        } catch (NumberFormatException e) {
            throw AppException.internalError("Çözünürlük sayı değil: " + parts[1], e);
        }
    }

    /** Bu rendition'ın MediaMTX'teki path adı. */
    public String pathFor(String basePath) {
        return basePath + "_" + suffix;
    }
}
