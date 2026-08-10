package org.example.VAD;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SileroVad}'ın <b>altın referansa</b> karşı doğrulaması.
 *
 * <p>Referans, aynı ses ve aynı model üzerinde Python/onnxruntime ile
 * üretildi. Java çıktısı bunu tutmuyorsa üç şüpheli var, olasılık sırasıyla:
 *
 * <ol>
 *   <li>64 örneklik bağlam eklenmiyor — <i>skorların hepsi sıfıra yakın çıkar</i></li>
 *   <li>Bayt sırası ters ({@code s16le} little-endian) — <i>skorlar rastgele</i></li>
 *   <li>Durum kareler arası taşınmıyor — <i>ilk kare doğru, sonrası bozuk</i></li>
 * </ol>
 *
 * <p>Bu test hattın <b>en kritik kontrol noktası</b>: burada tutmayan bir
 * model üzerine kurulan bölütleyici ve STT hatalarının nerede olduğu
 * anlaşılamaz.
 */
class SileroVadTest {

    private static final String MODEL = "/models/silero_vad.onnx";
    private static final String PCM = "/vad/ornek-60sn.pcm";
    private static final String GOLDEN = "/vad/altin-skorlar.txt";

    /** Python ile Java arasında bu kadar fark kabul edilebilir. */
    private static final float TOLERANCE = 1e-4f;

    @Test
    void altinReferansiTutturur() throws IOException {
        float[] audio = pcm();
        List<Float> beklenen = golden();

        try (SileroVad vad = new SileroVad(MODEL)) {
            for (int i = 0; i < beklenen.size(); i++) {
                float[] frame = new float[VadConfig.FRAME_SAMPLES];
                System.arraycopy(audio, i * VadConfig.FRAME_SAMPLES, frame, 0, frame.length);

                float actual = vad.score(frame);
                assertEquals(beklenen.get(i), actual, TOLERANCE,
                    "Kare " + i + " sapıyor. Bağlam, bayt sırası ve durum taşımayı kontrol et.");
            }
        }
    }

    /**
     * Bağlam olmadan modelin <b>sessizce</b> yanlış çalıştığını sabitler.
     *
     * <p>Bu davranış ölçülerek bulundu: aynı seste bağlamlı çağrım %97
     * konuşma verirken bağlamsız çağrım %0 veriyor ve hiçbir hata çıkmıyor.
     * Test, ileride biri bağlamı "gereksiz" diye kaldırırsa bunu yakalar.
     */
    @Test
    void baglamsizCagrimSessizceSifirVerir() throws Exception {
        float[] audio = pcm();
        var env = ai.onnxruntime.OrtEnvironment.getEnvironment();
        byte[] model;
        try (InputStream in = getClass().getResourceAsStream(MODEL)) {
            model = in.readAllBytes();
        }

        try (var session = env.createSession(model)) {
            float[][][] state = new float[2][1][128];
            int konusma = 0;
            int toplam = 100;

            for (int i = 0; i < toplam; i++) {
                float[][] girdi = new float[1][VadConfig.FRAME_SAMPLES];   // BAGLAM YOK
                System.arraycopy(audio, i * VadConfig.FRAME_SAMPLES, girdi[0], 0,
                    VadConfig.FRAME_SAMPLES);

                try (var a = ai.onnxruntime.OnnxTensor.createTensor(env, girdi);
                     var s = ai.onnxruntime.OnnxTensor.createTensor(env, state);
                     var r = ai.onnxruntime.OnnxTensor.createTensor(env,
                         java.nio.LongBuffer.wrap(new long[]{16000}), new long[0]);
                     var out = session.run(java.util.Map.of("input", a, "state", s, "sr", r))) {

                    if (((float[][]) out.get(0).getValue())[0][0] > 0.5f) konusma++;
                    state = (float[][][]) out.get(1).getValue();
                }
            }
            assertEquals(0, konusma,
                "Bağlamsız çağrım konuşma bulmamalı — bu sessiz hatanın kendisi.");
        }
    }

    /** Aynı ses, bağlamlı: konuşma oranı yüksek çıkmalı. */
    @Test
    void baglamliCagrimKonusmaBulur() throws IOException {
        float[] audio = pcm();
        int konusma = 0;
        int toplam = 100;

        try (SileroVad vad = new SileroVad(MODEL)) {
            for (int i = 0; i < toplam; i++) {
                float[] frame = new float[VadConfig.FRAME_SAMPLES];
                System.arraycopy(audio, i * VadConfig.FRAME_SAMPLES, frame, 0, frame.length);
                if (vad.score(frame) > 0.5f) konusma++;
            }
        }
        // Ornek TRT Haber'den; olculen oran %97.
        assertTrue(konusma > 80,
            "Konuşma oranı beklenenden düşük: " + konusma + "/" + toplam);
    }

    @Test
    void yanlisKareUzunluguReddedilir() {
        try (SileroVad vad = new SileroVad(MODEL)) {
            assertThrows(IllegalArgumentException.class, () -> vad.score(new float[256]));
        }
    }

    @Test
    void v4ModeliAcikcaReddedilir() {
        // v4'un girdileri h/c; bu sinif v5 bekliyor ve sessizce calismamali.
        var e = assertThrows(IllegalStateException.class,
            () -> new SileroVad("/models/silero_vad_v4.onnx"));
        assertTrue(e.getMessage().contains("v5"), "Hata mesajı sebebi anlatmalı: " + e.getMessage());
    }

    // ------------------------------------------------------------------

    private float[] pcm() throws IOException {
        byte[] raw;
        try (InputStream in = getClass().getResourceAsStream(PCM)) {
            raw = in.readAllBytes();
        }
        var bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[raw.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = bb.getShort() / 32768f;
        }
        return out;
    }

    private List<Float> golden() throws IOException {
        List<Float> out = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    out.add(Float.parseFloat(line.trim().split("\\s+")[1]));
                }
            }
        }
        return out;
    }
}
