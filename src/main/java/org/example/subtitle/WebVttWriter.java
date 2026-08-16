package org.example.subtitle;

import java.time.Duration;
import java.util.List;

/**
 * Saf WebVTT yazıcı — hem klip (mevcut {@code Subtitle} satırlarından) hem
 * video STT (bellekteki toplu sonuçlardan) yolunun paylaştığı ortak parça.
 * JPA'dan bağımsız: girdi zaten dosyaya göreli {@link Duration} çiftleri.
 */
public final class WebVttWriter {

    private WebVttWriter() {
    }

    /** @param start/end klibin/videonun BAŞINDAN göreli, mutlak zaman değil */
    public record VttCue(Duration start, Duration end, String text) {
    }

    /**
     * @param cues sırasız verilebilir — yazıcı başlangıca göre sıralar
     * @return {@code cues} boşsa bile geçerli, boş bir WebVTT gövdesi döner;
     *         çağıran cue listesi boşsa hiç dosya yazmamayı kendi seçer
     */
    public static String yaz(List<VttCue> cues) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        cues.stream()
            .sorted((a, b) -> a.start().compareTo(b.start()))
            .forEach(cue -> sb
                .append(zaman(cue.start())).append(" --> ").append(zaman(cue.end())).append('\n')
                .append(cue.text()).append("\n\n"));
        return sb.toString();
    }

    /** {@code HH:MM:SS.mmm} — WebVTT'nin zorunlu biçimi. */
    private static String zaman(Duration d) {
        long ms = Math.max(0, d.toMillis());
        long h = ms / 3_600_000;
        long m = (ms / 60_000) % 60;
        long s = (ms / 1_000) % 60;
        long milis = ms % 1_000;
        return String.format("%02d:%02d:%02d.%03d", h, m, s, milis);
    }
}
