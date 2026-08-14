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
     * @param readers o an MediaMTX'e bağlı oturumlar (HLS + RTSP karışık).
     *                "İzleyici sayısı" artık BURADAN değil, sekme bazlı
     *                {@link org.example.viewer.ViewerPresence}'ten geliyor
     *                — bkz. o sınıfın Javadoc'u (MediaMTX her yeniden
     *                bağlanmayı ayrı sayıyor, aynı sekme birden çok kez
     *                sayılabiliyordu).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        String name,
        boolean ready,
        List<String> tracks,
        List<Reader> readers,
        long bytesReceived
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reader(String type, String id) {
    }
}
