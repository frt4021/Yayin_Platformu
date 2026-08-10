package org.example.VAD;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Kare kare gelen olasılıkları <b>konuşma bölütlerine</b> çeviren durum
 * makinesi. VAD'ın asıl işi burada.
 *
 * <h2>Neden histerezis</h2>
 * Kare başına tek eşik karşılaştırması yapılsaydı, sınırda gezinen bir skor
 * bölütü saniyede birkaç kez açıp kapatırdı. Açma ve kapatma eşikleri bu
 * yüzden farklı ve kapatma ayrıca {@link VadConfig#MIN_SILENCE_MS} kadar
 * sessizlik bekliyor — cümle içi duraklamalar bölütü ikiye bölmesin.
 *
 * <h2>Üç durum</h2>
 * <pre>
 * KAPALI ──(N kare p&gt;0,50)──► AÇIK ──(600 ms p&lt;0,35)──► BEKLEYEN
 *    ▲                          ▲                            │
 *    │                          └────(p&gt;0,50 ile devam)──────┤
 *    └──────────(süre ≥ MIN_EMIT_MS, yayınla)────────────────┘
 * </pre>
 *
 * <p><b>BEKLEYEN neden var:</b> 1-2 saniyelik parçalar Whisper'da bağlamsız
 * kalıyor ve kalite düşüyor. Kapanan kısa bir bölüt hemen yayınlanmıyor;
 * sessizlik de biriktirilmeye devam ediyor ve konuşma yeniden başlarsa
 * <b>aynı bölüt</b> olarak sürüyor. Böylece kısa parçalar birleşiyor ama
 * <b>zaman damgaları dürüst kalıyor</b> — aradaki sessizlik gerçekten
 * seste var, atlanmış bir boşluk değil.
 *
 * <h2>Geriye dönük tampon</h2>
 * Konuşmanın başladığı anlaşıldığında o ses <b>çoktan geçmiştir</b>: hem
 * {@link VadConfig#SPEECH_PAD_MS} kadar pay, hem de kararı verdiren
 * {@link VadConfig#MIN_SPEECH_MS} kadar kare geride kalır. İkisi de halka
 * tampondan geri alınıyor.
 *
 * <p><b>İş parçacığı güvenli değil.</b> Kanal başına bir örnek.
 */
public final class SpeechSegmenter {

    private final UUID channelId;
    private final String channelName;
    private final Consumer<SpeechSegment> onSegment;

    private final int minSpeechFrames = VadConfig.msToFrames(VadConfig.MIN_SPEECH_MS);
    private final int minSilenceFrames = VadConfig.msToFrames(VadConfig.MIN_SILENCE_MS);
    private final int padSamples = VadConfig.msToSamples(VadConfig.SPEECH_PAD_MS);
    private final int maxSegmentSamples = VadConfig.msToSamples(VadConfig.MAX_SEGMENT_MS);
    private final int overlapSamples = VadConfig.msToSamples(VadConfig.FORCE_CUT_OVERLAP_MS);
    private final int minEmitSamples = VadConfig.msToSamples(VadConfig.MIN_EMIT_MS);

    /** Geriye dönük halka tampon — bayt cinsinden, {@code s16le}. */
    private final byte[] ring = new byte[VadConfig.msToSamples(VadConfig.PREROLL_MS) * 2];
    private int ringPos;
    private int ringFill;

    /** Açık/bekleyen bölütün sesi. */
    private byte[] segment = new byte[VadConfig.msToSamples(VadConfig.MAX_SEGMENT_MS) * 2];
    private int segmentLen;
    private Instant segmentStart;

    private boolean open;
    private boolean pending;
    private int speechFrames;
    private int silenceFrames;

    /** Bölütün sonundaki kesintisiz sessizlik — yayınlarken kırpılıyor. */
    private int trailingSilenceBytes;

    private long totalFrames;
    private long speechFrameCount;

    /**
     * @param channelId bölütlere yazılacak kanal kimliği
     * @param onSegment bölüt tamamlandığında çağrılır. <b>Bloklamamalı</b> —
     *                  burada STT beklenirse kare döngüsü durur, ffmpeg
     *                  borusu dolar ve akış bozulur
     */
    public SpeechSegmenter(UUID channelId, String channelName,
                           Consumer<SpeechSegment> onSegment) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.onSegment = onSegment;
    }

    /**
     * Bir kareyi işler.
     *
     * <p>Kareler <b>sırayla ve atlanmadan</b> verilmeli; zaman hesabı kare
     * sayısına dayanıyor.
     *
     * @param frame      ham kare ({@link VadConfig#FRAME_SAMPLES} örnek)
     * @param score      {@link SileroVad#score} sonucu
     * @param frameStart karenin <b>mutlak</b> başlangıç anı
     */
    public void accept(float[] frame, float score, Instant frameStart) {
        totalFrames++;
        if (score > 0.5f) {
            speechFrameCount++;
        }
        boolean speech = score >= VadConfig.OPEN_THRESHOLD;
        boolean silence = score < VadConfig.CLOSE_THRESHOLD;

        if (open) {
            appendFrame(frame, silence);
            if (silence) {
                if (++silenceFrames >= minSilenceFrames) {
                    open = false;
                    pending = true;
                }
            } else {
                silenceFrames = 0;
            }
            if (segmentLen >= maxSegmentSamples * 2) {
                forceCut(frameStart);
            }

        } else if (pending) {
            // Sessizlik de biriktiriliyor: konusma devam ederse ayni bolut
            // surer ve aradaki bosluk seste GERCEKTEN var kalir.
            appendFrame(frame, silence);
            if (speech) {
                open = true;
                pending = false;
                silenceFrames = 0;
            } else if (segmentLen >= minEmitSamples * 2) {
                emitPending();
            }

        } else {
            if (speech) {
                if (++speechFrames >= minSpeechFrames) {
                    openSegment(frame, frameStart);
                }
            } else {
                speechFrames = 0;
            }
        }

        ringPush(frame);
    }

    /**
     * Açık ya da bekleyen bölüt varsa kapatıp yayınlar.
     *
     * <p>Akış bittiğinde ve kapanışta <b>çağrılmalı</b>; aksi halde her
     * kanalın son konuşması sessizce kaybolur.
     */
    public void flush() {
        if (open || pending) {
            emitPending();
        }
        open = false;
        pending = false;
        speechFrames = 0;
        silenceFrames = 0;
    }

    /** Yeniden bağlanmada çağrılır — biriken her şey atılır. */
    public void reset() {
        open = false;
        pending = false;
        segmentLen = 0;
        trailingSilenceBytes = 0;
        speechFrames = 0;
        silenceFrames = 0;
        ringPos = 0;
        ringFill = 0;
    }

    // ------------------------------------------------------------------

    /**
     * Bölütü açar ve geriye dönük tampondan başı doldurur.
     *
     * <p>Geri alınan miktar iki parçadan oluşuyor: kararı verdiren
     * {@code minSpeechFrames - 1} kare (bunlar zaten konuşmaydı) ve üstüne
     * {@link VadConfig#SPEECH_PAD_MS} pay. İkincisi olmadan ilk hece
     * kırpılıyor.
     */
    private void openSegment(float[] frame, Instant frameStart) {
        int backfill = (padSamples + (minSpeechFrames - 1) * VadConfig.FRAME_SAMPLES) * 2;
        backfill = Math.min(backfill, ringFill);

        segmentLen = 0;
        trailingSilenceBytes = 0;
        ringCopyTail(backfill);
        appendFrame(frame, false);

        segmentStart = frameStart.minusMillis(backfill / 2L * 1000L / VadConfig.SAMPLE_RATE);
        open = true;
        pending = false;
        speechFrames = 0;
        silenceFrames = 0;
    }

    /**
     * Üst sınırı aşan bölütü keser ve örtüşmeyle yenisini başlatır.
     *
     * <p>Örtüşme, sonraki bölütle birleştirmeyi mümkün kılıyor: kesim cümle
     * ortasına denk gelirse Whisper'ın iki parçadaki metni örtüşen bölgeden
     * hizalayabilmesi gerekiyor.
     */
    private void forceCut(Instant frameStart) {
        int keep = Math.min(overlapSamples * 2, segmentLen);
        byte[] tail = new byte[keep];
        System.arraycopy(segment, segmentLen - keep, tail, 0, keep);

        Instant end = segmentStart.plusMillis(durationMs(segmentLen));
        emit(segmentStart, end, segmentLen, true);

        System.arraycopy(tail, 0, segment, 0, keep);
        segmentLen = keep;
        trailingSilenceBytes = 0;
        segmentStart = end.minusMillis(durationMs(keep));
        open = true;
        pending = false;
        silenceFrames = 0;
    }

    /** Bekleyen bölütü kırpıp yayınlar. */
    private void emitPending() {
        // Sondaki sessizlikten yalnizca pay kadari kaliyor; gerisi Whisper'a
        // bos ses vermek olurdu.
        int trim = Math.max(0, trailingSilenceBytes - padSamples * 2);
        int len = Math.max(0, segmentLen - trim);
        if (len > 0) {
            emit(segmentStart, segmentStart.plusMillis(durationMs(len)), len, false);
        }
        segmentLen = 0;
        trailingSilenceBytes = 0;
        pending = false;
    }

    private void emit(Instant start, Instant end, int len, boolean forceCut) {
        byte[] pcm = new byte[len];
        System.arraycopy(segment, 0, pcm, 0, len);
        onSegment.accept(new SpeechSegment(channelId, channelName, start, end, pcm, forceCut));
    }

    private void appendFrame(float[] frame, boolean silence) {
        ensureCapacity(segmentLen + VadConfig.FRAME_BYTES);
        writeFrame(segment, segmentLen, frame);
        segmentLen += VadConfig.FRAME_BYTES;
        trailingSilenceBytes = silence ? trailingSilenceBytes + VadConfig.FRAME_BYTES : 0;
    }

    private void ensureCapacity(int need) {
        if (need > segment.length) {
            byte[] bigger = new byte[Math.max(need, segment.length * 2)];
            System.arraycopy(segment, 0, bigger, 0, segmentLen);
            segment = bigger;
        }
    }

    private void ringPush(float[] frame) {
        for (int i = 0; i < VadConfig.FRAME_SAMPLES; i++) {
            short s = toShort(frame[i]);
            ring[ringPos] = (byte) (s & 0xFF);
            ring[(ringPos + 1) % ring.length] = (byte) ((s >> 8) & 0xFF);
            ringPos = (ringPos + 2) % ring.length;
        }
        ringFill = Math.min(ringFill + VadConfig.FRAME_BYTES, ring.length);
    }

    /** Halkanın son {@code bytes} baytını bölüt tamponunun başına kopyalar. */
    private void ringCopyTail(int bytes) {
        ensureCapacity(bytes);
        int start = Math.floorMod(ringPos - bytes, ring.length);
        for (int i = 0; i < bytes; i++) {
            segment[i] = ring[(start + i) % ring.length];
        }
        segmentLen = bytes;
    }

    private static void writeFrame(byte[] dst, int off, float[] frame) {
        for (int i = 0; i < VadConfig.FRAME_SAMPLES; i++) {
            short s = toShort(frame[i]);
            dst[off + i * 2] = (byte) (s & 0xFF);
            dst[off + i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
    }

    /** {@code [-1,1]} → {@code s16le}. Sınırlama şart: 1,0 kırpılmazsa taşar. */
    private static short toShort(float f) {
        int v = Math.round(f * 32768f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static long durationMs(int bytes) {
        return bytes / 2L * 1000L / VadConfig.SAMPLE_RATE;
    }

    // ------------------------------------------------------------------

    /**
     * Konuşma oranı.
     *
     * <p><b>Loglanmalı.</b> VAD'ın sessizce yanlış çalışması çok kolay ve tek
     * ucuz göstergesi bu oran: haber kanalında %90+ normal, müzik kanalında
     * %10-20 beklenir. <b>%0 ya da %100 çıkıyorsa bir şey bozuktur</b> — en
     * olası sebep {@link VadConfig#CONTEXT_SAMPLES} bağlamının verilmemesi.
     *
     * <p>Ayrıca bu oran GPU tasarrufunun ta kendisi: TRT Haber'de ölçülen %97,
     * o kanalda VAD'ın neredeyse hiç kazanç sağlamadığı anlamına geliyor.
     */
    public double speechRatio() {
        return totalFrames == 0 ? 0 : (double) speechFrameCount / totalFrames;
    }

    public long totalFrames() {
        return totalFrames;
    }
}
