package org.example.VAD;

/**
 * Ses etkinliği tespitinin sabitleri.
 *
 * <p>Buradaki değerlerin bir kısmı <b>modelin dayattığı</b>, bir kısmı
 * ayarlanabilir. İkisini karıştırmamak önemli: kare boyutunu değiştirmek
 * modeli bozar, eşiği değiştirmek yalnızca davranışı ayarlar.
 */
public final class
VadConfig {

    /**
     * Gecikmeye doğrudan etki eden üç ayar {@code .env}'den geliyor.
     *
     * <p>Doğru değer donanıma bağlı: GPU'da STT çok hızlı olduğu için pencere
     * uzun tutulup kalite kazanılabilir; CPU'da kısa tutmak zorunlu.
     */
    private static int cfg(String key, int fallback) {
        return org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getOptionalValue(key, Integer.class).orElse(fallback);
    }

    private VadConfig() {
    }

    // ------------------------------------------------------------------
    // Modelin dayattıkları — DEĞİŞTİRİLEMEZ
    // ------------------------------------------------------------------

    /** Silero'nun beklediği örnekleme hızı. */
    public static final int SAMPLE_RATE = 16_000;

    /** Kare boyutu: 16 kHz'de 512 örnek = 32 ms. */
    public static final int FRAME_SAMPLES = 512;

    /**
     * Her kareye önceki kareden eklenen bağlam.
     *
     * <p><b>Atlanırsa model sessizce çalışmaz.</b> Hata vermez, yalnızca her
     * kareye "sessizlik" der. Ölçüldü: bağlamsız çağrımda TRT Haber'de konuşma
     * oranı %0, bağlamla %97. Ses RMS'i 0,11 ve tepe değeri 0,95 iken.
     */
    public static final int CONTEXT_SAMPLES = 64;

    /** Modele verilen tensörün uzunluğu. */
    public static final int INPUT_SAMPLES = CONTEXT_SAMPLES + FRAME_SAMPLES;

    /** LSTM durum tensörünün şekli: {@code [2][1][128]}. */
    public static final int STATE_SIZE = 2 * 1 * 128;

    /** Bir karenin bayt karşılığı — {@code s16le}, tek kanal. */
    public static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    // ------------------------------------------------------------------
    // Ayarlanabilir davranış
    // ------------------------------------------------------------------

    /**
     * Konuşmanın <b>başladığına</b> karar verilen olasılık.
     *
     * <p>Açma ve kapatma eşiği bilerek farklı: tek eşikle sınırda gezinen bir
     * skor bölütü saniyede birkaç kez açıp kapatır.
     */
    public static final float OPEN_THRESHOLD = 0.50f;

    /** Konuşmanın <b>bittiğine</b> karar verilen olasılık. */
    public static final float CLOSE_THRESHOLD = 0.35f;

    /** Bundan kısa konuşmalar yok sayılır — öksürük, kapı sesi, tık. */
    public static final int MIN_SPEECH_MS = 250;

    /**
     * Bölütü kapatmadan önce beklenen sessizlik.
     *
     * <p>Kısa tutulursa cümle içi duraklamalar bölütü ikiye böler ve Whisper
     * bağlamsız kalır.
     */
    public static final int MIN_SILENCE_MS = cfg("vad.min-silence-ms", 400);

    /**
     * Bölütün başına ve sonuna eklenen pay.
     *
     * <p>VAD konuşmanın başladığını ancak birkaç kare sonra anlıyor; pay
     * eklenmezse ilk hece kırpılır. Baştaki payı verebilmek için geriye dönük
     * tampon gerekiyor — bkz. {@link #PREROLL_MS}.
     */
    public static final int SPEECH_PAD_MS = 250;

    /** Geriye dönük PCM tamponu. {@link #SPEECH_PAD_MS}'den geniş olmalı. */
    public static final int PREROLL_MS = 500;

    /**
     * Bölüt bu süreyi aşarsa zorla kesilir — <b>gecikmenin ana belirleyicisi</b>.
     *
     * <p>Ölçüldü: 25 saniyeyle çalışırken toplam gecikme <b>22,5 sn</b> çıktı
     * ve bunun <b>14,5 sn'si bölütün kapanmasını beklemekti</b>. İzleyici HLS
     * yüzünden yalnızca 6-12 saniye geride olduğu için altyazı ona hiç
     * yetişemiyordu — ekranda görünmüyordu.
     *
     * <p>Canlı altyazıda bölüt <b>sessizliği beklemeden</b> kesilmeli. Bedeli
     * Whisper'ın daha az bağlam görmesi ve kalitenin düşmesi; karşılığında
     * altyazının izleyiciye yetişmesi.
     *
     * <p>GPU'da STT payı küçüldüğü için bu değer büyütülüp kalite geri
     * kazanılabilir — bu yüzden {@code .env}'den ayarlanıyor.
     */
    public static final int MAX_SEGMENT_MS = cfg("vad.max-segment-ms", 6_000);

    /** Zorla kesimde bırakılan örtüşme. */
    public static final int FORCE_CUT_OVERLAP_MS = cfg("vad.overlap-ms", 800);

    /**
     * Bundan kısa bölütler bir sonrakine eklenir.
     *
     * <p>1-2 saniyelik parçalar Whisper'da bağlamsız kalır ve kalite düşer.
     */
    public static final int MIN_EMIT_MS = cfg("vad.min-emit-ms", 0);

    // ------------------------------------------------------------------

    /** Milisaniyeyi kare sayısına çevirir. */
    public static int msToFrames(int ms) {
        return Math.max(1, (ms * SAMPLE_RATE) / (1000 * FRAME_SAMPLES));
    }

    /** Milisaniyeyi örnek sayısına çevirir. */
    public static int msToSamples(int ms) {
        return (ms * SAMPLE_RATE) / 1000;
    }
}
