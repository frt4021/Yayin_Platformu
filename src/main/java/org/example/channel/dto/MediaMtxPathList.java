package org.example.channel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** {@code GET /v3/paths/list} yanıtı. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaMtxPathList(
    int itemCount,
    int pageCount,
    List<Item> items
) {

    /**
     * Bir path'in çalışma zamanı durumu.
     *
     * @param ready   yayın gerçekten akıyor mu. Path'in yapılandırmada var
     *                olması bunu garanti etmez — kaynak erişilemiyorsa
     *                path tanımlıdır ama {@code ready=false}'tur.
     * @param readers o an izleyen oturumlar — HLS (tarayıcı) VE RTSP
     *                (VAD/DVR'ın kendi arka plan bağlantıları) türlerini
     *                birlikte içerir; "izleyici sayısı" için ayıklanması
     *                gerekiyor, bkz. {@link #hlsReaderCount()}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        String name,
        boolean ready,
        List<String> tracks,
        List<Reader> readers,
        long bytesReceived
    ) {
        /**
         * Gerçekten izleyen tarayıcı sayısı.
         *
         * <p>{@code readers} listesi HLS izleyicileriyle birlikte kanalın
         * DVR kaydı ve VAD'ın altyazı için tuttuğu RTSP bağlantılarını da
         * taşıyor — bunlar DVR/VAD açık olan her kanalda, hiç izleyici
         * olmasa bile sürekli var. Ölçüldü: DVR+VAD açık bir kanalda
         * hiçbir tarayıcı bağlı değilken bile 2 "reader" görünüyordu, ikisi
         * de {@code type=rtspSession}. Filtrelenmezse "izleyici sayısı"
         * hiçbir zaman sıfıra dönmüyormuş gibi görünür.
         */
        public int hlsReaderCount() {
            if (readers == null) {
                return 0;
            }
            return (int) readers.stream().filter(r -> "hlsSession".equals(r.type())).count();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reader(String type, String id) {
    }
}
