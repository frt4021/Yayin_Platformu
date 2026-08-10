package org.example.VAD;

import java.time.Instant;
import java.util.UUID;

/**
 * VAD'ın ayırdığı tek bir konuşma bölütü — STT'ye verilecek birim.
 *
 * <p>Zaman damgaları <b>mutlak</b>, süreye göreli değil. Altyazının doğru
 * kareye oturması buna bağlı: izleyici canlı yayında 6-12 saniye geride
 * olduğu için "şimdi üretildi, şimdi göster" mantığı altyazıyı erken gösterir.
 * Eşleştirme {@code PROGRAM-DATE-TIME} üzerinden yapılacak.
 *
 * @param channelId   kanalın kimliği
 * @param channelName kanalın adı — bölütler diske bu adla yazılıyor;
 *                    UUID klasörleri 20 kanal çalışırken hangisinin
 *                    hangisi olduğunu bulmayı imkânsız kılıyordu
 * @param startedAt bölütün başladığı <b>mutlak</b> an (pay dahil)
 * @param endedAt   bölütün bittiği <b>mutlak</b> an (pay dahil)
 * @param pcm       16 kHz, tek kanal, {@code s16le} ham ses
 * @param forceCut  bölüt {@link VadConfig#MAX_SEGMENT_MS} aşıldığı için mi
 *                  kesildi. Doğruysa cümle ortasında bölünmüş olabilir ve
 *                  sonraki bölütle örtüşmeli birleştirme gerekir.
 */
public record SpeechSegment(
    UUID channelId,
    String channelName,
    Instant startedAt,
    Instant endedAt,
    byte[] pcm,
    boolean forceCut
) {

    /** Bölütün süresi. */
    public long durationMs() {
        return java.time.Duration.between(startedAt, endedAt).toMillis();
    }

    /** Ses uzunluğu — {@link #durationMs()} ile tutarsızsa bir yerde kayma var. */
    public long pcmDurationMs() {
        return (pcm.length / 2L) * 1000L / VadConfig.SAMPLE_RATE;
    }
}
