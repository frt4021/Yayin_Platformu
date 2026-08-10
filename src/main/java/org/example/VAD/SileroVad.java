package org.example.VAD;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Silero-VAD (v5) modelinin <b>tek kanala ait</b> örneği.
 *
 * <h2>Neden kanal başına ayrı nesne</h2>
 * Model durumlu: LSTM durumu ({@code state}) ve önceki karenin kuyruğu
 * ({@code context}) kareler arasında taşınıyor. İki kanal aynı nesneyi
 * kullanırsa iki yayının sesi birbirine karışır — <b>ve hata alınmaz</b>,
 * yalnızca sonuçlar sessizce bozulur. {@code OrtSession} zaten iş parçacığı
 * güvenli değil.
 *
 * <p>Model 2,2 MB olduğu için 20 örnek açmak sorun değil.
 *
 * <h2>Neden yalnızca v5</h2>
 * İki sürümün girdi biçimi farklı ve <b>ölçüldü</b>:
 *
 * <table>
 *   <tr><th></th><th>v4</th><th>v5</th></tr>
 *   <tr><td>Kare (16 kHz)</td><td>512-2048 arası serbest</td><td><b>yalnızca 512</b></td></tr>
 *   <tr><td>Bağlam</td><td>yok</td><td><b>64 örnek zorunlu</b></td></tr>
 *   <tr><td>LSTM durumu</td><td>{@code h} + {@code c}, ikisi de [2,1,64]</td>
 *       <td>tek {@code state} [2,1,128]</td></tr>
 * </table>
 *
 * <p>v5'in katılığı avantaj: yanlış pencere ONNX hatası veriyor. v4'te aynı
 * seste 512 → %100, 1536 → %96, 256 → %30 konuşma çıkıyordu — yanlış pencere
 * hata vermeden farklı sonuç üretiyordu.
 *
 * <h2>Ölçülen başarım</h2>
 * Tek çekirdekte <b>199× gerçek zaman</b>. Oturum bilerek tek iş parçacığına
 * sabitli; fazlası 20 kanalda birbirini yer.
 */
public final class SileroVad implements AutoCloseable {

    private static final String IN_AUDIO = "input";
    private static final String IN_STATE = "state";
    private static final String IN_SR = "sr";

    private final OrtEnvironment env;
    private final OrtSession session;

    /**
     * LSTM durumu, {@code [2][1][128]}.
     *
     * <p>Düz dizi yerine üç boyutlu: ONNX Runtime'ın Java API'si çok boyutlu
     * diziyi doğrudan tensöre çeviriyor, düzleştirme/geri açma adımı hem
     * gereksiz hem hata kaynağı olurdu.
     */
    private float[][][] state = new float[2][1][128];

    /**
     * Önceki karenin son {@link VadConfig#CONTEXT_SAMPLES} örneği.
     *
     * <p><b>Modelin girdisi bu kuyruk + yeni kare.</b> Verilmezse model
     * çalışır, sonuç döner, hiçbir uyarı çıkmaz — sadece her kareye
     * "sessizlik" der. Ölçüldü: bağlamsız çağrımda TRT Haber'de konuşma oranı
     * %0, bağlamla %97; ses RMS'i 0,11 ve tepe değeri 0,95 iken.
     */
    private final float[] context = new float[VadConfig.CONTEXT_SAMPLES];

    /** Modele verilen tampon — her karede yeniden ayırmamak için alanda. */
    private final float[][] input = new float[1][VadConfig.INPUT_SAMPLES];

    /**
     * Modeli yükler ve <b>imzasını doğrular</b>.
     *
     * @param modelPath önce sınıf yolunda aranır ({@code /models/...}),
     *                  bulunamazsa dosya sistemi denenir
     * @throws IllegalStateException model okunamazsa ya da imzası v5 değilse
     */
    public SileroVad(String modelPath) {
        byte[] model = read(modelPath);
        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            // Tek is parcacigi: 199x gercek zaman zaten fazlasiyla yeter ve
            // 20 kanal ayni anda calisirken havuzlar birbirini yer.
            opts.setIntraOpNumThreads(1);
            opts.setInterOpNumThreads(1);
            this.session = env.createSession(model, opts);
        } catch (OrtException e) {
            throw new IllegalStateException("VAD modeli yüklenemedi: " + modelPath, e);
        }
        requireV5Signature(modelPath);
    }

    /**
     * Modelin gerçekten v5 olduğunu doğrular.
     *
     * <p><b>Açıkça patlamak şart.</b> v4 modeli v5 koduyla çalıştırılmaya
     * kalkılırsa girdi adları tutmadığı için zaten hata alınır; asıl tehlike
     * ileride biçimi tutan ama semantiği tutmayan bir modelin sessizce boş
     * altyazı üretmesi. "Belki çalışır" diye devam etmek, saatlerce boş kayıt
     * demek.
     */
    private void requireV5Signature(String modelPath) {
        try {
            var names = session.getInputNames();
            if (!names.contains(IN_AUDIO) || !names.contains(IN_STATE) || !names.contains(IN_SR)) {
                throw new IllegalStateException(
                    "VAD modeli v5 değil. Beklenen girdiler [input, state, sr], bulunan: "
                        + names + " — dosya: " + modelPath
                        + ". v4 modeli h/c girdileri kullanır ve bu sınıfla çalışmaz.");
            }
            var stateInfo = (TensorInfo) session.getInputInfo().get(IN_STATE).getInfo();
            long[] shape = stateInfo.getShape();
            if (shape.length != 3 || shape[0] != 2 || shape[2] != 128) {
                throw new IllegalStateException(
                    "VAD durum tensörü beklenen [2,?,128] değil: " + Arrays.toString(shape)
                        + " — dosya: " + modelPath);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("VAD model imzası okunamadı: " + modelPath, e);
        }
    }

    /**
     * Bir kare için konuşma olasılığı.
     *
     * <p>Çağrı sonrası durum ve bağlam güncellenir — kareler <b>sırayla</b> ve
     * <b>atlanmadan</b> verilmeli. Bir kare atlanırsa model o noktadan sonra
     * tutarsız çalışır ve bunu belli etmez.
     *
     * @param frame tam {@link VadConfig#FRAME_SAMPLES} örnek, {@code [-1,1]}
     * @return konuşma olasılığı, {@code [0,1]}
     */
    public float score(float[] frame) {
        if (frame.length != VadConfig.FRAME_SAMPLES) {
            // Sessizce kabul etmek yerine patliyoruz: yanlis uzunluk v5'te
            // ONNX hatasi verir ama hata mesaji sebebi anlatmaz.
            throw new IllegalArgumentException(
                "Kare " + VadConfig.FRAME_SAMPLES + " örnek olmalı, gelen: " + frame.length);
        }

        // Girdi = onceki karenin kuyrugu + bu kare.
        System.arraycopy(context, 0, input[0], 0, VadConfig.CONTEXT_SAMPLES);
        System.arraycopy(frame, 0, input[0], VadConfig.CONTEXT_SAMPLES, VadConfig.FRAME_SAMPLES);

        // try-with-resources SART: tensorler yerel bellek. Kapatilmazsa
        // 32 ms'de bir sizdirir ve surec saatler icinde siser.
        try (OnnxTensor tAudio = OnnxTensor.createTensor(env, input);
             OnnxTensor tState = OnnxTensor.createTensor(env, state);
             OnnxTensor tSr = OnnxTensor.createTensor(
                 env, LongBuffer.wrap(new long[]{VadConfig.SAMPLE_RATE}), new long[0]);
             OrtSession.Result out = session.run(
                 Map.of(IN_AUDIO, tAudio, IN_STATE, tState, IN_SR, tSr))) {

            float p = ((float[][]) out.get(0).getValue())[0][0];
            state = (float[][][]) out.get(1).getValue();

            // Sonraki karenin baglami: bu karenin kuyrugu.
            System.arraycopy(frame, VadConfig.FRAME_SAMPLES - VadConfig.CONTEXT_SAMPLES,
                context, 0, VadConfig.CONTEXT_SAMPLES);
            return p;
        } catch (OrtException e) {
            throw new IllegalStateException("VAD çıkarımı başarısız", e);
        }
    }

    /**
     * Durumu sıfırlar.
     *
     * <p>ffmpeg yeniden başladığında <b>çağrılmalı</b>: yeni akış eski durumla
     * devam ederse ilk saniyeler bozuk skorlanır.
     */
    public void reset() {
        state = new float[2][1][128];
        Arrays.fill(context, 0f);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            // Kapanista yutuluyor: cagiran icin yapilabilecek bir sey yok.
        }
        // OrtEnvironment surec genelinde paylasilan bir tekil; burada
        // kapatilmiyor, aksi halde digerinin oturumu da olurdu.
    }

    // ------------------------------------------------------------------

    private static byte[] read(String modelPath) {
        String cp = modelPath.startsWith("/") ? modelPath : "/" + modelPath;
        try (InputStream in = SileroVad.class.getResourceAsStream(cp)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new IllegalStateException("VAD modeli sınıf yolundan okunamadı: " + cp, e);
        }
        try {
            // Sinif yolunda yoksa dosya sistemi: gelistirirken modeli imaja
            // gommeden denemeye izin veriyor.
            return Files.readAllBytes(Path.of(modelPath));
        } catch (IOException e) {
            throw new IllegalStateException(
                "VAD modeli bulunamadı — ne sınıf yolunda (" + cp
                    + ") ne dosya sisteminde (" + modelPath + ")", e);
        }
    }
}
