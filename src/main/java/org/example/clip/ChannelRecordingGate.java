package org.example.clip;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.example.channel.MediaMtxService;
import org.example.channel.entity.Channel;
import org.example.exception.AppException;
import org.example.clip.entity.ActiveRecording;
import org.example.clip.entity.ScheduledRecording;
import org.jboss.logging.Logger;

/**
 * DVR'ı <b>kapalı</b> bir kanalda kayıt gerektiğinde MediaMTX'te kaydı açan ve
 * iş bitince geri kapatan kapı.
 *
 * <p><b>Neden var:</b> hem manuel kayıt ({@link RecordingService}) hem planlı
 * kayıt ({@link ScheduledRecordingService}) aynı şeye ihtiyaç duyuyor ve ikisi
 * <b>aynı kanalda aynı anda</b> çalışabilir. Her biri kendi başına kapatsaydı,
 * biri bitince diğerinin aralığı ortasından kesilirdi.
 *
 * <p>Kapatma kararı bu yüzden tek bir soruya bakıyor: <i>bu kanalda kaydı
 * kendisi açmış başka bir iş kaldı mı?</i> Çağıranlar kendi satırlarını
 * <b>önce</b> siler ya da durumunu değiştirir, sonra buraya gelir — böylece
 * sayım doğal olarak kendilerini dışarıda bırakır.
 */
@ApplicationScoped
public class ChannelRecordingGate {

    private static final Logger LOG = Logger.getLogger(ChannelRecordingGate.class);

    @Inject
    MediaMtxService mediaMtx;

    /**
     * Kanalın kaydettiğinden emin olur.
     *
     * @return kayıt <b>bu çağrı için</b> açıldıysa {@code true}. Çağıran bunu
     *         saklamalı: kapatma sorumluluğu ondadır. Kanalın kendi DVR'ı zaten
     *         açıksa {@code false} döner ve hiçbir şeye dokunulmaz.
     */
    public boolean acquire(Channel channel) {
        requireLive(channel);
        if (channel.dvrEnabled) {
            return false;
        }
        apply(channel, true);
        LOG.infof("Kayıt için geriye sarma geçici açıldı: %s", channel.name);
        return true;
    }

    /**
     * Kaydı geri kapatır — kanalda başka bir iş kullanmıyorsa.
     *
     * <p><b>Kanal kimlikle alınıyor, nesneyle değil.</b> Çağıranlar burayı
     * {@code finally} içinden, kendi transaction'ları kapandıktan sonra
     * çağırıyor; oraya bir {@code Channel} taşımak lazy proxy'yi oturumsuz
     * bırakıyor ve {@code LazyInitializationException} veriyordu. Yaşandı:
     * kayıt durduruluyor ama uç 500 dönüyordu.
     *
     * <p>{@code REQUIRED}: dışarıda transaction varsa <b>ona katılıyor</b>.
     * {@code REQUIRES_NEW} yanlış olurdu — planlı kayıtta emrin durumu dış
     * transaction'da değişiyor, ayrı bir transaction onu henüz göremez ve
     * "hâlâ süren iş var" sanıp kaydı hiç kapatmazdı.
     *
     * @param acilmisti {@link #acquire} {@code true} döndüyse
     */
    @jakarta.transaction.Transactional
    public void release(java.util.UUID channelId, boolean acilmisti) {
        if (!acilmisti) {
            return;
        }
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            // Kanal silinmis: MediaMTX path'i de onunla birlikte kaldirildi.
            return;
        }
        if (ActiveRecording.anyTemporaryOn(channelId)
            || ScheduledRecording.anyTemporaryOn(channelId)) {
            LOG.debugf("Kayıt açık bırakıldı, kanalda süren başka iş var: %s", channel.name);
            return;
        }
        try {
            apply(channel, channel.dvrEnabled);
            LOG.infof("Geçici geriye sarma kapatıldı: %s", channel.name);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Geçici geriye sarma kapatılamadı: %s", channel.name);
        }
    }

    /**
     * Kanalın <b>gerçekten yayında</b> olduğunu doğrular.
     *
     * <p>Yayın akmıyorsa MediaMTX kaydı açar, klasörü oluşturur ve içine
     * hiçbir şey yazmaz. Kullanıcı dakikalarca kaydettiğini sanır, durdurduğunda
     * "bu aralıkta kayıt bulunamadı" alır. Baştan reddetmek dürüst: kaybedilen
     * bir şey yok, çünkü kaydedilecek bir şey de yoktu.
     *
     * <p>MediaMTX'e ulaşılamıyorsa <b>engellemiyoruz</b>: {@code pathStates()}
     * o durumda boş harita dönüyor ve bunu "yayın yok" saymak, medya
     * sunucusunun anlık bir aksaklığında kaydı gereksizce reddederdi.
     */
    private void requireLive(Channel channel) {
        var states = mediaMtx.pathStates();
        if (states.isEmpty()) {
            return;
        }
        var state = states.get(channel.mediamtxPath);
        if (state == null || !state.ready()) {
            throw AppException.badRequest(
                "Bu kanal şu anda yayında değil, kayıt alınamaz: " + channel.name);
        }
    }

    private void apply(Channel channel, boolean record) {
        mediaMtx.applyPath(channel.mediamtxPath, channel.effectiveSourceUrl(),
            record, channel.renditions);
    }
}
