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
 * DVR'ı <b>kapalı</b> bir kanalda kayıt gerektiğinde kaydın başlamasını
 * sağlayan kapı.
 *
 * <h2>Artık nasıl çalışıyor</h2>
 * Eskiden MediaMTX'in kaydını açıp kapıyordu ({@code applyPath(record=true)}).
 * Kayıt MediaMTX'ten alınınca o düğme kalmadı; yerine <b>veritabanı sinyali</b>
 * geçti: {@code ActiveRecording.dvrBizden} ve {@code ScheduledRecording}
 * satırları. {@code DvrRecorder} her eşitlemede bu satırlara bakıp DVR'ı
 * kapalı olsa bile o kanal için geçici bir kaydedici açıyor.
 *
 * <p><b>Bunun yan faydası:</b> eski yol {@code applyPath} çağırdığı için
 * MediaMTX path'ini yeniden başlatıyordu ve <b>canlı yayın tüm izleyiciler
 * için kısa süre kesiliyordu</b>. Yeni yol yalnızca fazladan bir ffmpeg
 * okuyucusu açıyor; yayına hiç dokunmuyor.
 *
 * <p><b>Bedeli neydi:</b> kayıt anında değil, kaydedicinin bir sonraki
 * eşitlemesinde başlıyordu ({@code dvr.sync-interval}, 10 sn) ve kısa
 * kayıtlar tamamen ıskalanıyordu. Artık satırın yanına bir de
 * {@code DvrSignalEvent.BASLAT} emri gidiyor; veritabanı yine doğruluk
 * kaynağı, sinyal yalnızca beklemeyi kaldırıyor.
 *
 * <p>Sayım mantığı korundu: hem manuel hem planlı kayıt aynı kanalda aynı
 * anda çalışabiliyor ve biri bitince diğerininki kesilmemeli. Çağıranlar
 * kendi satırlarını <b>önce</b> siler, sonra buraya gelir.
 */
@ApplicationScoped
public class ChannelRecordingGate {

    private static final Logger LOG = Logger.getLogger(ChannelRecordingGate.class);

    @Inject
    MediaMtxService mediaMtx;

    /**
     * Kaydediciye "şimdi başla" emri. Dinleyici {@code AFTER_SUCCESS} ile
     * bağlı: emir, sebebini yazan satır commit edilmeden gitmiyor.
     */
    @Inject
    jakarta.enterprise.event.Event<org.example.dvr.DvrSignalEvent> sinyal;

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
        // Kaydediciye haber gonderiliyor. DOGRULUK KAYNAGI YINE VERITABANI:
        // kaydediciyi tetikleyen sey cagiranin birazdan yazacagi
        // ActiveRecording/ScheduledRecording satiri ve sinyal kaybolsa bile
        // yoklama onu buluyor. Sinyalin tek isi BEKLEMEYI KALDIRMAK --
        // yoklama araligi 10 saniye ve 6 saniyelik bir kayit tamamen
        // iskalaniyordu.
        sinyal.fire(org.example.dvr.DvrSignalEvent.baslat(channel.id));
        LOG.infof("Kayıt için geçici DVR istendi: %s", channel.name);
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
            LOG.debugf("Geçici DVR açık bırakıldı, kanalda süren başka iş var: %s", channel.name);
            return;
        }
        // Kapatmak icin de bir sey YAPILMIYOR: cagiran kendi satirini zaten
        // sildi, kaydedici bir sonraki esitlemede o kanali hedef listesinde
        // bulamayip isciyi kendisi kapatiyor.
        LOG.infof("Geçici DVR bırakıldı: %s", channel.name);
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

}
