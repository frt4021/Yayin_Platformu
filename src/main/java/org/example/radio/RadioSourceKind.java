package org.example.radio;

/**
 * Radyo kaynağının MediaMTX'e nasıl bağlanacağı.
 *
 * <p>Kullanıcıdan <b>açıkça</b> alınıyor, adresten tahmin edilmiyor.
 * Sebebi ölçülerek görüldü: MediaMTX {@code http(s)} kaynaklarını HLS
 * sayıyor ve düz bir Icecast MP3 adresini path yazılırken kabul ediyor —
 * ama çalışma zamanında m3u8 bekleyip boş dönüyor. Yani yanlış tahminin
 * cezası görünür bir hata değil, <b>hiç başlamayan bir yayın</b>.
 *
 * <p>Otomatik tahmin de güvenilir değil: Icecast adreslerinin çoğunda
 * uzantı yok ({@code /canli}, {@code /stream/1}), HLS adreslerinin hepsi
 * {@code .m3u8} ile bitmiyor.
 */
public enum RadioSourceKind {

    /**
     * Adres MediaMTX'in {@code source} alanına yazılır; ffmpeg araya girmez.
     * HLS ({@code *.m3u8}), RTSP, RTMP, SRT, UDP ve WHEP kaynakları için.
     */
    DOGRUDAN,

    /**
     * MediaMTX kaynağı okuyamıyor; ffmpeg adresi çekip AAC'ye kodlayarak
     * RTSP üzerinden MediaMTX'e basıyor. Icecast/Shoutcast düz MP3 ve AAC
     * yayınları bu moda girer.
     *
     * <p>Ölçülen maliyet: radyo başına %2.6 CPU (MP3 → AAC 128k).
     */
    KOPRU
}
