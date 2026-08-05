package org.example.channel;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS playlist çözümleyicisi — yalnızca ihtiyaç duyulan iki bilgi için.
 *
 * <p>Tam bir HLS ayrıştırıcısı değil ve olmasına gerek yok: master
 * playlist'teki varyant listesi ve bir medya playlist'indeki hedef segment
 * süresi dışında hiçbir şey okunmuyor. Kütüphane eklemek, bu iki satırlık
 * ihtiyaç için bağımlılık taşımak olurdu.
 */
final class HlsPlaylist {

    private static final Pattern STREAM_INF = Pattern.compile("^#EXT-X-STREAM-INF:(.*)$");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern TARGET_DURATION = Pattern.compile("#EXT-X-TARGETDURATION:(\\d+)");

    private HlsPlaylist() {
    }

    /**
     * Master playlist'teki bir kalite seçeneği.
     *
     * @param bandwidth bit/sn. Segment boyutu tahmini bunun üzerine kuruluyor:
     *                  MediaMTX'i düşüren şey bant genişliği değil, <b>segment
     *                  başına düşen bayt</b> — 2 saniyelik segmentlerde 6 Mbps
     *                  sorunsuz, 6 saniyelik segmentlerde aynı bit hızı sınırı
     *                  aşıyor.
     * @param height    bilinmiyorsa 0; bazı playlist'ler RESOLUTION vermiyor
     */
    record Variant(String uri, int bandwidth, int width, int height) {

        /** Segment başına tahmini bayt. */
        long estimatedSegmentBytes(int targetDurationSeconds) {
            return (long) bandwidth / 8 * targetDurationSeconds;
        }
    }

    /** Gövde bir master playlist mi (varyant listesi içeriyor mu). */
    static boolean isMaster(String body) {
        return body != null && body.contains("#EXT-X-STREAM-INF");
    }

    /**
     * Varyantları <b>kaliteden düşüğe</b> sıralı döner: önce yükseklik,
     * eşitse bant genişliği.
     */
    static List<Variant> parseMaster(String body) {
        List<Variant> variants = new ArrayList<>();
        String[] lines = body.split("\\R");

        for (int i = 0; i < lines.length; i++) {
            Matcher inf = STREAM_INF.matcher(lines[i].trim());
            if (!inf.matches()) {
                continue;
            }
            String attributes = inf.group(1);

            // URI, STREAM-INF satirindan SONRAKI ilk yorum olmayan satir.
            // Arada bos satirlar olabiliyor.
            String uri = null;
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.isEmpty() || candidate.startsWith("#")) {
                    continue;
                }
                uri = candidate;
                break;
            }
            if (uri == null) {
                continue;
            }

            Matcher bw = BANDWIDTH.matcher(attributes);
            int bandwidth = bw.find() ? Integer.parseInt(bw.group(1)) : 0;

            // Tek bir find() ile iki grubu birden okuyoruz; ayri cagrilar
            // eslestiriciyi ilerletip ikinci grubu kaybettiriyordu.
            Matcher res = RESOLUTION.matcher(attributes);
            int width = 0;
            int height = 0;
            if (res.find()) {
                width = Integer.parseInt(res.group(1));
                height = Integer.parseInt(res.group(2));
            }

            variants.add(new Variant(uri, bandwidth, width, height));
        }

        variants.sort(Comparator
            .comparingInt(Variant::height)
            .thenComparingInt(Variant::bandwidth)
            .reversed());
        return variants;
    }

    /** Medya playlist'indeki {@code #EXT-X-TARGETDURATION}; yoksa {@code null}. */
    static Integer parseTargetDuration(String body) {
        Matcher m = TARGET_DURATION.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * Playlist içindeki göreli adresi mutlak hale getirir.
     *
     * <p>Varyant adresleri neredeyse her zaman göreli
     * ({@code stream01/playlist.m3u8}); MediaMTX'e göreli bir adres
     * verilemez.
     */
    static String resolve(String playlistUrl, String uri) {
        return URI.create(playlistUrl).resolve(uri).toString();
    }
}
