package org.example.VAD;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpeechSegmenter}'ın davranış testleri.
 *
 * <p>İki katman: <b>yapay skorlarla</b> durum makinesinin kuralları, ve
 * <b>gerçek sesle</b> uçtan uca akış. Yapay skorlar kuralları kesin olarak
 * sınıyor; gerçek ses ikisinin birlikte çalıştığını gösteriyor.
 */
class SpeechSegmenterTest {

    private static final UUID KANAL = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Tek bir gürültüsüz kare — içeriği testlerde önemli değil. */
    private static float[] frame() {
        float[] f = new float[VadConfig.FRAME_SAMPLES];
        for (int i = 0; i < f.length; i++) {
            f[i] = 0.25f;
        }
        return f;
    }

    private static Instant at(int frameIndex) {
        return T0.plusMillis(frameIndex * 1000L * VadConfig.FRAME_SAMPLES / VadConfig.SAMPLE_RATE);
    }

    /** Verilen skor dizisini besler ve çıkan bölütleri döndürür. */
    private List<SpeechSegment> run(float... scores) {
        List<SpeechSegment> out = new ArrayList<>();
        var seg = new SpeechSegmenter(KANAL, "TRT Haber", out::add);
        for (int i = 0; i < scores.length; i++) {
            seg.accept(frame(), scores[i], at(i));
        }
        seg.flush();
        return out;
    }

    private static float[] repeat(float value, int count) {
        float[] a = new float[count];
        java.util.Arrays.fill(a, value);
        return a;
    }

    private static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int o = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    // ------------------------------------------------------------------

    @Test
    void kisaGurultuBolutAcmaz() {
        // MIN_SPEECH_MS'in altinda tek tuk yuksek skor: oksuruk, kapi sesi.
        int kisa = VadConfig.msToFrames(VadConfig.MIN_SPEECH_MS) - 1;
        var out = run(concat(repeat(0.9f, kisa), repeat(0.0f, 100)));
        assertTrue(out.isEmpty(), "Kısa gürültü bölüt açmamalı, açılan: " + out.size());
    }

    @Test
    void cumleIciDuraklamaBolutuBolmez() {
        // MIN_SILENCE_MS'in altinda bir sessizlik: nefes molasi.
        int kisaSessizlik = VadConfig.msToFrames(VadConfig.MIN_SILENCE_MS) - 2;
        var out = run(concat(
            repeat(0.9f, 200),
            repeat(0.0f, kisaSessizlik),
            repeat(0.9f, 200),
            repeat(0.0f, 100)));
        assertEquals(1, out.size(), "Duraklama bölütü bölmemeli, çıkan: " + out.size());
    }

    @Test
    void uzunSessizlikBolutuKapatir() {
        int uzunSessizlik = VadConfig.msToFrames(VadConfig.MIN_SILENCE_MS) + 5;
        // Iki bolut de MIN_EMIT_MS'i asacak kadar uzun olmali, yoksa
        // BEKLEYEN durumunda birlesirler.
        int uzunKonusma = VadConfig.msToFrames(VadConfig.MIN_EMIT_MS) + 40;
        var out = run(concat(
            repeat(0.9f, uzunKonusma),
            repeat(0.0f, uzunSessizlik),
            repeat(0.9f, uzunKonusma),
            repeat(0.0f, uzunSessizlik)));
        assertEquals(2, out.size(), "İki ayrı bölüt bekleniyordu, çıkan: " + out.size());
    }

    @Test
    void kisaBolutlerBirlesir() {
        // Iki kisa konusma, arada MIN_SILENCE'i asan ama MIN_EMIT'e
        // varmayan bir sessizlik: BEKLEYEN durumu bunlari tek bolut yapmali.
        int sessizlik = VadConfig.msToFrames(VadConfig.MIN_SILENCE_MS) + 2;
        var out = run(concat(
            repeat(0.9f, 30),
            repeat(0.0f, sessizlik),
            repeat(0.9f, 30),
            repeat(0.0f, 300)));
        assertEquals(1, out.size(), "Kısa bölütler birleşmeliydi, çıkan: " + out.size());
    }

    @Test
    void ustSinirZorlaKeser() {
        int cokUzun = VadConfig.msToFrames(VadConfig.MAX_SEGMENT_MS) * 2 + 50;
        var out = run(concat(repeat(0.9f, cokUzun), repeat(0.0f, 100)));

        assertTrue(out.size() >= 2, "Zorla kesim bekleniyordu, çıkan: " + out.size());
        assertTrue(out.get(0).forceCut(), "İlk bölüt forceCut işaretli olmalı");
        assertTrue(out.get(0).durationMs() <= VadConfig.MAX_SEGMENT_MS + 500,
            "Bölüt üst sınırı aşmamalı: " + out.get(0).durationMs());
    }

    @Test
    void bolutBaslangictanOnceBaslar() {
        // Pay + karar suresi kadar geriye alinmali; aksi halde ilk hece kirpilir.
        var out = run(concat(repeat(0.9f, 200), repeat(0.0f, 100)));
        assertEquals(1, out.size());

        int kararKare = VadConfig.msToFrames(VadConfig.MIN_SPEECH_MS);
        Instant kararAni = at(kararKare - 1);
        assertTrue(out.get(0).startedAt().isBefore(kararAni),
            "Bölüt karar anından önce başlamalı (pay + karar süresi)");
    }

    @Test
    void sesUzunluguZamanDamgasiylaTutarli() {
        var out = run(concat(repeat(0.9f, 200), repeat(0.0f, 100)));
        assertEquals(1, out.size());
        SpeechSegment s = out.get(0);
        // 1 kare tolerans: kirpma kare sinirinda yapiliyor.
        assertTrue(Math.abs(s.pcmDurationMs() - s.durationMs()) <= 40,
            "PCM uzunluğu " + s.pcmDurationMs() + " ms, damga " + s.durationMs() + " ms");
    }

    @Test
    void flushAcikBolutuKaybetmez() {
        List<SpeechSegment> out = new ArrayList<>();
        var seg = new SpeechSegmenter(KANAL, "TRT Haber", out::add);
        for (int i = 0; i < 300; i++) {
            seg.accept(frame(), 0.9f, at(i));
        }
        assertTrue(out.isEmpty(), "Sessizlik gelmeden bölüt kapanmamalı");
        seg.flush();
        assertEquals(1, out.size(), "flush açık bölütü yayınlamalı");
    }

    @Test
    void sessizAkistaBolutCikmaz() {
        var out = run(repeat(0.0f, 500));
        assertTrue(out.isEmpty(), "Sessizlikten bölüt çıkmamalı");
    }

    // ------------------------------------------------------------------
    // Gerçek ses — SileroVad ile uçtan uca
    // ------------------------------------------------------------------

    @Test
    void gercekSesteMakulBolutUretir() throws IOException {
        float[] audio = pcm();
        List<SpeechSegment> out = new ArrayList<>();
        var segmenter = new SpeechSegmenter(KANAL, "TRT Haber", out::add);

        try (SileroVad vad = new SileroVad("/models/silero_vad.onnx")) {
            int frames = audio.length / VadConfig.FRAME_SAMPLES;
            for (int i = 0; i < frames; i++) {
                float[] f = new float[VadConfig.FRAME_SAMPLES];
                System.arraycopy(audio, i * VadConfig.FRAME_SAMPLES, f, 0, f.length);
                segmenter.accept(f, vad.score(f), at(i));
            }
            segmenter.flush();
        }

        // Ornek 60 sn TRT Haber, olculen konusma orani %97 -> neredeyse
        // kesintisiz konusma. 25 sn ust siniri yuzunden 2-4 parca bekleniyor;
        // 40 parca cikiyorsa histerezis calismiyor demektir.
        assertFalse(out.isEmpty(), "Gerçek seste bölüt çıkmalı");
        assertTrue(out.size() <= 6, "Çok fazla bölüt — histerezis çalışmıyor: " + out.size());

        long toplam = out.stream().mapToLong(SpeechSegment::pcmDurationMs).sum();
        assertTrue(toplam > 40_000,
            "Toplam konuşma 60 sn'nin çoğu olmalı, çıkan: " + toplam + " ms");

        assertTrue(segmenter.speechRatio() > 0.8,
            "Konuşma oranı beklenenden düşük: " + segmenter.speechRatio());
    }

    private float[] pcm() throws IOException {
        byte[] raw;
        try (InputStream in = getClass().getResourceAsStream("/vad/ornek-60sn.pcm")) {
            raw = in.readAllBytes();
        }
        var bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[raw.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getShort() / 32768f;
        }
        return out;
    }
}
