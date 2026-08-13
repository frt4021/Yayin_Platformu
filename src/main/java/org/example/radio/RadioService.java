package org.example.radio;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.channel.MediaMtxService;
import org.example.channel.dto.MediaMtxPathList;
import org.example.channel.entity.Channel;
import org.example.exception.AppException;
import org.example.radio.dto.CreateRadioRequest;
import org.example.radio.dto.RadioDto;
import org.example.radio.dto.UpdateRadioRequest;
import org.example.radio.entity.Radio;
import org.example.user.entity.AppUser;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Radyo CRUD ve MediaMTX yansıtması.
 *
 * <p>Kanallarla aynı ilke: veritabanı kalıcı doğruluk kaynağı, MediaMTX
 * türetilmiş durum. Her yazma işlemi ikisini birlikte tutarlı bırakır.
 */
@ApplicationScoped
public class RadioService {

    private static final Logger LOG = Logger.getLogger(RadioService.class);

    @Inject
    MediaMtxService mediaMtx;

    @ConfigProperty(name = "mediamtx.hls-base-url")
    String hlsBaseUrl;

    /**
     * Radyolar {@code channels.max-active} sayacına dahil DEĞİL: o sınır video
     * kodlama ve bant genişliği bütçesinden geliyor. Ölçümde bir radyo köprüsü
     * %2.6 CPU, bir video rendition'ı %14 — aynı ölçekte değiller.
     */
    @ConfigProperty(name = "radios.max-active")
    int maxActiveRadios;

    @ConfigProperty(name = "radios.default-bitrate")
    String defaultBitrate;

    public List<RadioDto> list() {
        Map<String, MediaMtxPathList.Item> states = mediaMtx.pathStates();
        return Radio.listAllSorted().stream()
            .map(radio -> toDto(radio, states.get(radio.mediamtxPath)))
            .toList();
    }

    public RadioDto get(UUID id) {
        Radio radio = require(id);
        return toDto(radio, mediaMtx.pathStates().get(radio.mediamtxPath));
    }

    public Capacity capacity() {
        return new Capacity(Radio.countActive(null), maxActiveRadios);
    }

    @Transactional
    public RadioDto create(CreateRadioRequest req, String keycloakId) {
        requireNameFree(req.name(), null);
        requirePathFree(req.mediamtxPath(), null);
        if (req.active()) {
            requireCapacity(null);
        }

        Radio radio = new Radio();
        apply(radio, req.name(), req.sourceUrl(), req.sourceKind(), req.mediamtxPath(),
            req.bitrate(), req.active(), req.logoUrl(), req.sortOrder());
        radio.createdBy = requireLocalUser(keycloakId);
        radio.persist();

        if (radio.active) {
            push(radio);
        }
        LOG.infof("Radyo oluşturuldu: %s (path=%s, tür=%s, aktif=%s)",
            radio.name, radio.mediamtxPath, radio.sourceKind, radio.active);
        return toDto(radio, null);
    }

    @Transactional
    public RadioDto update(UUID id, UpdateRadioRequest req) {
        Radio radio = require(id);
        requireNameFree(req.name(), id);
        requirePathFree(req.mediamtxPath(), id);
        if (req.active()) {
            // Radyonun kendisi sayımdan düşülüyor: zaten aktif olanı düzenlemek
            // kapasiteyi artırmıyor.
            requireCapacity(id);
        }

        String previousPath = radio.mediamtxPath;
        boolean wasActive = radio.active;

        apply(radio, req.name(), req.sourceUrl(), req.sourceKind(), req.mediamtxPath(),
            req.bitrate(), req.active(), req.logoUrl(), req.sortOrder());

        // Path adı değiştiyse eski path artık hiçbir radyoya ait değil;
        // kaldırılmazsa MediaMTX'te sahipsiz bir yayın olarak akmaya devam eder
        // ve KOPRU modunda ffmpeg süreci de boşuna çalışmayı sürdürür.
        if (!previousPath.equals(radio.mediamtxPath) && wasActive) {
            mediaMtx.removePath(previousPath);
        }

        if (radio.active) {
            push(radio);
        } else if (wasActive) {
            mediaMtx.removePath(radio.mediamtxPath);
        }

        LOG.infof("Radyo güncellendi: %s (path=%s, tür=%s, aktif=%s)",
            radio.name, radio.mediamtxPath, radio.sourceKind, radio.active);
        return toDto(radio, null);
    }

    @Transactional
    public void delete(UUID id) {
        Radio radio = require(id);
        String path = radio.mediamtxPath;
        String name = radio.name;
        radio.delete();
        mediaMtx.removePath(path);
        LOG.infof("Radyo silindi: %s (path=%s)", name, path);
    }

    /**
     * Veritabanındaki aktif radyoları MediaMTX'e yeniden yazar.
     *
     * <p>Kanallardaki {@code restoreActiveChannels} ile aynı gerekçe: MediaMTX
     * path'leri bellekte tutuyor ve yeniden başladığında hepsini kaybediyor.
     * Tek tek hata yönetiliyor — bir radyonun kaynağı erişilemez olduğunda
     * diğerlerinin de ayağa kalkmaması saçma olurdu.
     *
     * @return başarıyla yazılan radyo sayısı
     */
    public int restoreActiveRadios() {
        List<Radio> active = Radio.listActive();
        int restored = 0;
        for (Radio radio : active) {
            try {
                push(radio);
                restored++;
            } catch (RuntimeException e) {
                LOG.errorf(e, "Radyo MediaMTX'e yazılamadı: %s (path=%s)",
                    radio.name, radio.mediamtxPath);
            }
        }
        LOG.infof("Aktif radyolar geri yüklendi: %d/%d", restored, active.size());
        return restored;
    }

    // ------------------------------------------------------------------

    /**
     * Radyoyu MediaMTX'e yazar.
     *
     * <p>{@code KOPRU} modunda köprü komutu üretilir, {@code DOGRUDAN} modunda
     * adres MediaMTX'in kendi {@code source} alanına verilir ve ffmpeg hiç
     * devreye girmez.
     */
    private void push(Radio radio) {
        String bridge = radio.sourceKind == RadioSourceKind.KOPRU
            ? AudioBridgeCommand.build(radio.sourceUrl, radio.bitrate)
            : null;
        mediaMtx.applyAudioPath(radio.mediamtxPath, radio.sourceUrl, bridge);
    }

    private void apply(Radio radio, String name, String sourceUrl, RadioSourceKind kind,
                       String path, String bitrate, boolean active, String logoUrl, int sortOrder) {
        // Adres doğrulaması her iki modda da yapılıyor. KOPRU'da kabuk
        // enjeksiyonuna karşı zorunlu; DOGRUDAN'da ise MediaMTX'in kabul edip
        // çalıştıramayacağı bir şemayı erkenden yakalamak için — yoksa radyo
        // hatasız kaydedilir ve sessizce hiç yayına girmez.
        AudioBridgeCommand.requireSafeUrl(sourceUrl.trim());

        radio.name = name.trim();
        radio.sourceUrl = sourceUrl.trim();
        radio.sourceKind = kind;
        radio.mediamtxPath = path.trim();
        radio.bitrate = (bitrate == null || bitrate.isBlank()) ? defaultBitrate : bitrate.trim();
        radio.active = active;
        radio.logoUrl = (logoUrl == null || logoUrl.isBlank()) ? null : logoUrl.trim();
        radio.sortOrder = sortOrder;
    }

    private Radio require(UUID id) {
        Radio radio = Radio.findById(id);
        if (radio == null) {
            throw AppException.notFound("Radyo bulunamadı: " + id);
        }
        return radio;
    }

    private void requireCapacity(UUID exceptId) {
        long active = Radio.countActive(exceptId);
        if (active >= maxActiveRadios) {
            throw AppException.conflict(
                "Aynı anda en fazla " + maxActiveRadios + " radyo yayında olabilir "
                    + "(şu an " + active + "). Önce birini pasife alın.");
        }
    }

    private void requireNameFree(String name, UUID exceptId) {
        if (Radio.nameTaken(name, exceptId)) {
            throw AppException.conflict("Bu radyo adı zaten kullanılıyor: " + name);
        }
    }

    /**
     * Path hem radyolarda hem kanallarda benzersiz olmalı.
     *
     * <p>İkisi MediaMTX'te <b>aynı isim alanını</b> paylaşıyor: yalnızca kendi
     * tablosuna bakan bir kontrol, aynı path'i kullanan bir kanal ile radyonun
     * birbirinin yayınını ezmesine izin verirdi. Veritabanında iki ayrı unique
     * kısıt bunu yakalayamaz.
     */
    private void requirePathFree(String path, UUID exceptId) {
        if (Radio.pathTaken(path, exceptId)) {
            throw AppException.conflict("Bu MediaMTX path'i zaten kullanılıyor: " + path);
        }
        if (Channel.pathTaken(path, null)) {
            throw AppException.conflict(
                "Bu MediaMTX path'i bir kanal tarafından kullanılıyor: " + path);
        }
    }

    private AppUser requireLocalUser(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            throw AppException.internalError(
                "Oturum sahibinin yerel kaydı yok: " + keycloakId, null);
        }
        return user;
    }

    private RadioDto toDto(Radio radio, MediaMtxPathList.Item state) {
        return new RadioDto(
            radio.id,
            radio.name,
            radio.sourceUrl,
            radio.sourceKind,
            radio.mediamtxPath,
            radio.bitrate,
            radio.active,
            radio.logoUrl,
            radio.sortOrder,
            hlsBaseUrl + "/" + radio.mediamtxPath + "/index.m3u8",
            state == null ? null : state.ready(),
            state == null ? null : state.hlsReaderCount(),
            radio.createdBy == null ? null : radio.createdBy.username,
            radio.createdAt
        );
    }

    /** @param max {@code radios.max-active} ayarı */
    public record Capacity(long active, int max) {
    }
}
