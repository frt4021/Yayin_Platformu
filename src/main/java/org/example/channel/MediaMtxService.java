package org.example.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @Inject
    TranscodeCommand transcodeCommand;

    @ConfigProperty(name = "channels.rendition-start-timeout")
    String renditionStartTimeout;

    @ConfigProperty(name = "channels.rendition-close-after")
    String renditionCloseAfter;

    /**
     * Path'i istenen yapılandırmaya getirir. Yoksa oluşturur, varsa günceller —
     * çağıran hangi durumda olduğunu bilmek zorunda değil.
     *
     * <h2>Kayıt her zaman KAYNAK path'ine</h2>
     * Önceden kayıt, seçilen bir rendition'a ({@code dvrRendition}) yazılıyordu;
     * gerekçe disk tasarrufuydu. Bunun iki bedeli vardı ve ikisi de ağır bastı:
     *
     * <ul>
     *   <li><b>Kalite kaybı</b> — kaynak 1080p verse bile kayıt 720p/1500k
     *       oluyordu. Kayıt arşiv; kaynağın verdiği kalitede tutulmalı.</li>
     *   <li><b>Sessiz boş kayıt</b> — o rendition'ı üreten ffmpeg çalışmıyorsa
     *       (kaynak daha düşük çözünürlüklü, transcode düşmüş, GPU yok) MediaMTX
     *       kaydı açıyor, klasörü oluşturuyor ama içine hiçbir şey yazmıyordu.
     *       Kullanıcı dakikalarca kaydettiğini sanıp sonunda "bu aralıkta kayıt
     *       bulunamadı" alıyordu. Yaşandı.</li>
     * </ul>
     *
     * <p>Kaynak path'i her koşulda yayında; rendition'lar ondan türüyor.
     * Kayıt oraya yazılınca merdivenin sağlığından bağımsız hale geliyor.
     *
     * <h2>Rendition'lar talebe bağlı</h2>
     * Her rendition kendi {@code runOnDemand} kancasıyla, o path'e ilk
     * okuyucu geldiğinde kendi ffmpeg sürecini başlatıyor; MediaMTX son
     * okuyucu ayrıldıktan {@code channels.rendition-close-after} sonra
     * süreci kendiliğinden kapatıyor. Kaynak path'in kendisi (bu metotun
     * ikinci yarısı) buna dahil DEĞİL — DVR/kayıt bağımlılığı nedeniyle
     * her zaman canlı kalmaya devam ediyor (bkz.
     * docs/olcekleme-100-kullanici-plani.md §3).
     */
    public void applyPath(String path, String sourceUrl, String renditionSpec) {
        List<Rendition> renditions = Rendition.parse(renditionSpec);

        // Rendition path'leri talebe bagli: kaynaksiz kuruluyor, ilk okuyucu
        // gelince MediaMTX kendi runOnDemand kancasini tetikleyip ffmpeg'i
        // ayaga kaldiriyor. Hicbiri kayit yapmiyor -- kayit kaynakta.
        for (Rendition r : renditions) {
            MediaMtxPathConfig renditionConfig = MediaMtxPathConfig.onDemandRendition(
                transcodeCommand.buildOnDemand(path, r), renditionStartTimeout, renditionCloseAfter);
            ensurePath(r.pathFor(path), renditionConfig);
        }

        // record HER ZAMAN false: DVR kaydi artik MediaMTX'ten alinmiyor.
        // Kayit, RTSP'den ffmpeg ile cekilip MinIO'ya akitiliyor
        // (bkz. org.example.dvr.DvrRecorder). MediaMTX'in kendi kaydi acik
        // birakilsaydi ayni icerik hem diske hem MinIO'ya yazilirdi.
        // transcode komutu ARTIK TASIMIYOR -- rendition'lar yukarida kendi
        // runOnDemand'larini aldi.
        MediaMtxPathConfig config = MediaMtxPathConfig.alwaysOn(sourceUrl, false, null);
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

    /**
     * Ses-only bir path'i (radyo) istenen yapılandırmaya getirir.
     *
     * <p>Kanallardan ayrı bir metot: radyoda rendition merdiveni ve DVR yok,
     * buna karşılık {@code KOPRU} modunda {@code runOnInit} kancası var.
     * Aynı metoda sığdırmak, ikisi de kullanılmayan parametrelerle çağrılan
     * bir imza üretirdi.
     *
     * @param bridgeCommand ffmpeg köprü komutu; {@code null} ise kaynak
     *                      MediaMTX'e doğrudan verilir
     */
    public void applyAudioPath(String path, String sourceUrl, String bridgeCommand) {
        MediaMtxPathConfig config = bridgeCommand == null
            ? MediaMtxPathConfig.alwaysOn(sourceUrl, false, null)
            : MediaMtxPathConfig.bridged(bridgeCommand);
        ensurePath(path, config);
    }

    /**
     * Path'i istenen yapılandırmaya getirir; {@link #applyPath} ile aynı
     * "ekle, olmazsa güncelle" mantığı ama sade yapılandırmayla.
     */
    private void ensurePath(String path, MediaMtxPathConfig config) {
        try {
            client.addPath(path, config);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 400) {
                patch(path, config);
                return;
            }
            throw upstream("yardımcı path yazılamadı: " + path, e);
        }
    }

    /**
     * Path'i ve verilen rendition'larını siler. Zaten yoksa sessizce geçer.
     *
     * <p>Rendition'lar da silinmeli; kalırlarsa MediaMTX'te sahipsiz yayın
     * olarak akmaya devam eder ve GPU'yu boşuna meşgul ederler.
     */
    public void removePath(String path, String renditionSpec) {
        removeRenditions(path, renditionSpec);
        deleteQuietly(path);
    }

    /**
     * Tek bir path'i siler. Zaten yoksa sessizce geçer.
     *
     * <p>Radyolar için: rendition kavramı olmadığından kaldırılacak yardımcı
     * path de yok.
     */
    public void removePath(String path) {
        deleteQuietly(path);
    }

    /** Yalnızca rendition çıktılarını kaldırır — merdiven değiştiğinde kullanılır. */
    public void removeRenditions(String path, String renditionSpec) {
        for (Rendition r : Rendition.parse(renditionSpec)) {
            deleteQuietly(r.pathFor(path));
        }
    }

    private void deleteQuietly(String path) {
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
