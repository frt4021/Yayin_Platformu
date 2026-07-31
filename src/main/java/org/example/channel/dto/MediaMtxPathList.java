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
     * @param readers o an izleyen oturumlar
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        String name,
        boolean ready,
        List<String> tracks,
        List<Object> readers,
        long bytesReceived
    ) {
    }
}
