package org.example.radio;

import org.example.exception.AppException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Icecast/Shoutcast yayınını MediaMTX'e taşıyan ffmpeg komutunu üretir.
 *
 * <p>MediaMTX {@code http(s)} kaynaklarını HLS sayıyor, düz MP3 okuyamıyor.
 * Ölçümde {@code source} olarak verilen bir MP3 adresi
 * {@code hlsSource} olarak sınıflandı ve {@code bytesReceived} hiç artmadı.
 * Bu yüzden akış ffmpeg ile çekilip AAC'ye kodlanarak RTSP üzerinden geri
 * basılıyor — {@code runOnInit} kancasıyla, konteynerin içinde.
 *
 * <p>Kanallardaki {@code TranscodeCommand} ile aynı desen; fark, orada
 * beklenen bir kaynak olduğu için {@code runOnAvailable} kullanılması.
 * Burada kaynağı komutun kendisi üretiyor, dolayısıyla {@code runOnInit}.
 */
final class AudioBridgeCommand {

    /**
     * Komut <b>kabukta</b> çalıştığı için adres doğrudan gömülemez.
     * Kanallarda bu risk yok: orada adres MediaMTX'in {@code source} alanına
     * yazılıyor ve kabuğa hiç uğramıyor.
     *
     * <p>İki katmanlı savunma: adres tek tırnak içine alınıyor (aşağıda) ve
     * tek tırnak içeren adresler reddediliyor (burada). Tek tırnak yasak
     * olmasaydı {@code '; komut; '} biçiminde bir adres tırnaktan çıkıp
     * medya sunucusu konteynerinde komut çalıştırabilirdi.
     *
     * <p>İzinli küme RFC 3986'nın yaygın kullanılan alt kümesi: boşluk,
     * tırnak, ters tırnak, {@code ;}, {@code |}, {@code $}, {@code <},
     * {@code >} ve parantezler dışarıda kalıyor.
     */
    private static final Pattern SAFE_URL = Pattern.compile("^[A-Za-z0-9:/?&=._~%@#+,!*\\[\\]-]+$");

    /** ffmpeg'in girdi olarak açabileceği ve radyoda anlamı olan şemalar. */
    private static final Set<String> ALLOWED_SCHEMES =
        Set.of("http", "https", "rtsp", "rtmp", "rtmps", "srt", "udp");

    /** {@code 128k}, {@code 64k} … Sınırsız bırakılsa komuta rastgele metin girerdi. */
    private static final Pattern BITRATE = Pattern.compile("^[0-9]{1,4}k$");

    private AudioBridgeCommand() {
    }

    /**
     * @param sourceUrl doğrulanmış kaynak adresi
     * @param bitrate   üretilecek AAC bit hızı ({@code 128k})
     */
    static String build(String sourceUrl, String bitrate) {
        requireSafeUrl(sourceUrl);
        requireSafeBitrate(bitrate);

        // -reconnect: Icecast bağlantıları düşer. runOnInitRestart zaten
        // süreci yeniden başlatıyor ama ffmpeg'in kendi yeniden bağlanması
        // daha hızlı — süreç kurulum maliyeti ödenmiyor.
        //
        // -vn: bazı Icecast yayınları albüm kapağını video akışı olarak
        // taşıyor; seçilmezse ffmpeg onu RTSP'e göndermeye çalışıp düşüyor.
        //
        // $RTSP_PORT ve $MTX_PATH MediaMTX'in kancaya geçirdiği değişkenler;
        // tırnak dışında bırakılmalı ki kabuk genişletsin.
        return "ffmpeg -hide_banner -loglevel warning -nostdin"
            + " -reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5"
            + " -i '" + sourceUrl + "'"
            + " -vn -c:a aac -b:a " + bitrate
            + " -f rtsp rtsp://127.0.0.1:$RTSP_PORT/$MTX_PATH";
    }

    /**
     * Adresi hem kabuk hem protokol açısından doğrular.
     *
     * <p>{@link RadioSourceKind#DOGRUDAN} modunda da çağrılıyor: orada kabuk
     * riski yok ama şema doğrulaması yine gerekli — MediaMTX'in kabul edip
     * çalıştıramayacağı bir adres, kullanıcıya hata vermeden sessizce
     * çalışmayan bir radyo üretir.
     */
    static void requireSafeUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw AppException.badRequest("Kaynak adresi boş olamaz.");
        }
        if (!SAFE_URL.matcher(sourceUrl).matches()) {
            throw AppException.badRequest(
                "Kaynak adresi izin verilmeyen karakter içeriyor. Boşluk, tırnak ve "
                    + "kabuk karakterleri (; | $ ` < >) kullanılamaz.");
        }
        String scheme;
        try {
            scheme = new URI(sourceUrl).getScheme();
        } catch (URISyntaxException e) {
            throw AppException.badRequest("Kaynak adresi geçerli bir URL değil: " + sourceUrl);
        }
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw AppException.badRequest(
                "Desteklenmeyen adres şeması: " + scheme + ". Kullanılabilir: "
                    + String.join(", ", ALLOWED_SCHEMES));
        }
    }

    private static void requireSafeBitrate(String bitrate) {
        if (bitrate == null || !BITRATE.matcher(bitrate).matches()) {
            throw AppException.badRequest(
                "Geçersiz bit hızı: '" + bitrate + "'. Beklenen biçim: 128k");
        }
    }
}
