package org.example.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.example.channel.dto.MediaMtxPathConfig;
import org.example.channel.dto.MediaMtxPathList;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MediaMTX ile konuşmanın tek yeri. REST client'ın ham HTTP hatalarını
 * uygulamanın hata modeline çevirir ve "zaten var / zaten yok" gibi
 * durumları yutarak çağıranın işlemi tekrarlanabilir (idempotent) kılar.
 *
 * <p>Tekrarlanabilirlik önemli: açılışta kanalları geri yüklerken MediaMTX'in
 * bir önceki çalışmadan kalan path'leri hâlâ duruyor olabilir, ya da bir
 * kanal iki kez kaydedilmeye çalışılabilir. Bu durumlar hata değil.
 */
@ApplicationScoped
public class MediaMtxService {

    private static final Logger LOG = Logger.getLogger(MediaMtxService.class);

    /** {@code /v3/paths/list} sayfalı; tek çağrıda hepsini almak için yüksek tutuluyor. */
    private static final int PAGE_SIZE = 1000;

    @Inject
    @RestClient
    MediaMtxClient client;



    /**
     * Path'i istenen yapılandırmaya getirir. Yoksa oluşturur, varsa günceller —
     * çağıran hangi durumda olduğunu bilmek zorunda değil.
     */
    public void applyPath(String path, String sourceUrl, boolean record) {
        MediaMtxPathConfig config = MediaMtxPathConfig.alwaysOn(sourceUrl, record);
        try {
            client.addPath(path, config);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 400) {
                // MediaMTX "path already exists" için de 400 dönüyor; ayırt
                // edilebilir bir kod yok, bu yüzden güncellemeyi deniyoruz.
                patch(path, config);
                return;
            }
            throw upstream("path yazılamadı: " + path, e);
        }
    }

    private void patch(String path, MediaMtxPathConfig config) {
        try {
            client.patchPath(path, config);
        } catch (WebApplicationException e) {
            throw upstream("path güncellenemedi: " + path, e);
        }
    }

    /** Path'i siler. Zaten yoksa sessizce geçer. */
    public void removePath(String path) {
        try {
            client.deletePath(path);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 404 || status == 400) {
                LOG.debugf("Silinecek path MediaMTX'te yoktu: %s", path);
                return;
            }
            throw upstream("path silinemedi: " + path, e);
        }
    }

    /**
     * Tüm path'lerin anlık durumu, path adına göre indekslenmiş.
     *
     * <p>MediaMTX'e ulaşılamazsa hata fırlatmaz, <b>boş harita</b> döner:
     * bu bilgi kanal listesinde yardımcı bir alan; medya sunucusunun geçici
     * erişilemezliği kanal listesini tamamen kullanılamaz hale getirmemeli.
     */
    public Map<String, MediaMtxPathList.Item> pathStates() {
        try {
            MediaMtxPathList list = client.listPaths(PAGE_SIZE);
            List<MediaMtxPathList.Item> items = list.items();
            if (items == null) {
                return Map.of();
            }
            return items.stream().collect(Collectors.toMap(
                MediaMtxPathList.Item::name, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            LOG.warnf(e, "MediaMTX durum bilgisi alınamadı; kanal listesi durumsuz dönecek.");
            return Map.of();
        }
    }

    private AppException upstream(String message, WebApplicationException cause) {
        return AppException.upstreamError(
            "MediaMTX " + message + " (HTTP " + cause.getResponse().getStatus() + ").", cause);
    }
}
