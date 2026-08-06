package org.example.dvr;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.example.channel.entity.Channel;
import org.example.dvr.dto.RecordingSpan;
import org.example.dvr.dto.TimelineSpan;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Geriye sarma. MediaMTX'in playback sunucusunu sarar, yetkiyi uygular ve
 * dışarıya iç adres sızdırmadan sunar.
 */
@ApplicationScoped
public class DvrService {

    private static final Logger LOG = Logger.getLogger(DvrService.class);

    /** Tek seferde çekilebilecek en uzun aralık. Bkz. {@link #requireSaneRange}. */
    public static final Duration MAX_CLIP = Duration.ofHours(2);

    @Inject
    @RestClient
    MediaMtxPlaybackClient playback;

    /**
     * Kanalın verilen aralıkta kaydı olan bölümleri.
     *
     * <p>MediaMTX tüm kayıt geçmişini döndürüyor; istenen pencereye kırpma
     * burada yapılıyor ki arayüz 7 günlük veriyi süzmek zorunda kalmasın.
     */
    public List<TimelineSpan> timeline(UUID channelId, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw AppException.badRequest("Bitiş zamanı başlangıçtan sonra olmalı.");
        }
        Channel channel = requireDvrChannel(channelId);

        return fetchSpans(channel).stream()
            .map(span -> toTimelineSpan(span))
            .filter(span -> span != null)
            // Pencereyle kesişmeyenleri at, kesişenleri pencereye kırp.
            .filter(span -> span.end().isAfter(from) && span.start().isBefore(to))
            .map(span -> new TimelineSpan(
                span.start().isBefore(from) ? from : span.start(),
                span.end().isAfter(to) ? to : span.end()))
            .toList();
    }

    /**
     * Kaydın belirtilen bölümünü akış olarak döndürür.
     *
     * <p>Yanıt gövdesi belleğe alınmadan doğrudan aktarılır — saatlik bir
     * bölüm 6 Mbps'te ~2.7 GB eder, tamponlamak sunucuyu düşürürdü.
     *
     * @param format {@code mp4} indirme, {@code fmp4} tarayıcıda oynatma için
     */
    public Response stream(UUID channelId, Instant start, Duration duration, String format) {
        // DVR SARTI YOK. Kanalin geriye sarmasi kapali olsa bile diskte kayit
        // BULUNABILIR: manuel ve planli kayit, is suresince kaydi aciyor
        // (ChannelRecordingGate). Klip iscisi icerigi tam da buradan cekiyor;
        // sarti korumak, kaydin durdurulup hicbir klip uretilmemesine yol
        // aciyordu. Aralik gercekten yoksa MediaMTX 404 doner ve asagida
        // anlasilir bir hataya cevriliyor.
        return streamPath(requireChannel(channelId).recordingPath(), start, duration, format);
    }

    /**
     * Aynı iş, ama kanal <b>kimlikle değil path'le</b> veriliyor.
     *
     * <p>Klip işçisi için: aktarım bilerek transaction DIŞINDA yapılıyor
     * (saatlik bir bölüm ~2,7 GB, tutmak sunucuyu düşürürdü) ve orada
     * veritabanına dokunmak {@code ContextNotActiveException} veriyor. Yaşandı:
     * ilk deneme bu yüzden düşüyordu. Path zaten işi yüklerken — transaction
     * içindeyken — biliniyor; oraya kadar taşımak tek doğru çözüm.
     */
    public Response streamPath(String recordingPath, Instant start,
                               Duration duration, String format) {
        requireSaneRange(duration);

        try {
            // Kayit KAYNAK path'inde: rendition'a yazmak hem kaliteyi
            // dusuruyor hem de merdiven coktugunde sessizce bos kaliyordu.
            return playback.get(recordingPath, start.toString(),
                duration.toMillis() / 1000.0, format);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 404) {
                throw AppException.notFound(
                    "Bu aralıkta kayıt bulunamadı. Kayıt silinmiş veya o sırada yayın olmamış olabilir.");
            }
            throw AppException.upstreamError(
                "Kayıt okunamadı (HTTP " + status + ").", e);
        }
    }

    /** Kanalın kayıt bulunan aralıkları — klip isteğinin doğrulanmasında da kullanılır. */
    public List<TimelineSpan> recordedSpans(UUID channelId) {
        // stream() ile ayni gerekce: DVR kapali kanalda da manuel/planli
        // kayit yuzunden diskte bolum olabilir.
        return fetchSpans(requireChannel(channelId)).stream()
            .map(this::toTimelineSpan)
            .filter(span -> span != null)
            .toList();
    }

    /**
     * İstenen aralığın tamamı kayıtlı mı.
     *
     * <p>Kısmen kayıtlı bir aralık için klip üretmek, kullanıcının beklediğinden
     * kısa veya boşluklu bir dosya çıkarır. Baştan reddetmek daha dürüst.
     */
    public boolean isFullyRecorded(UUID channelId, Instant start, Instant end) {
        return recordedSpans(channelId).stream()
            .anyMatch(span -> !span.start().isAfter(start) && !span.end().isBefore(end));
    }

    /**
     * İstenen aralığı <b>diskte gerçekten olan</b> bölüme kırpar.
     *
     * <h2>Neden gerekli</h2>
     * Kullanıcı "kaydı başlat"a bastığı an ile MediaMTX'in yazmaya başladığı
     * an aynı değil: kaydı açmak path'i yeniden başlatıyor, kaynak yeniden
     * bağlanıyor ve arada saniyeler geçiyor. Klip {@code [basılan, durdurulan]}
     * aralığı için isteniyordu; başlangıçta veri olmadığı için MediaMTX 404
     * dönüyor ve kullanıcı <i>"Bu aralıkta kayıt bulunamadı"</i> alıyordu.
     * Ölçüldü: düğmeye basıldıktan sonra ilk bölüm 6-14 saniye gecikmeyle
     * başlıyor.
     *
     * <p>Kırpmak, sessizce eksik vermek değil — kaydedilen <b>tam olarak</b>
     * budur. Alternatif olan 404, kullanıcının elinde hiçbir şey bırakmıyordu.
     *
     * @return kırpılmış aralık; hiç örtüşme yoksa {@link Optional#empty()}
     */
    public Optional<TimelineSpan> clampToRecorded(UUID channelId, Instant start, Instant end) {
        // En cok ortusen bolum seciliyor. Aralik birden fazla bolume yayilmis
        // olabilir (kayit sirasinda kaynak koptu); MediaMTX bosluklu araligi
        // sorunsuz veriyor, o yuzden ucları genisletmek yerine EN GENIS
        // ortusmeyi almak dogru sonucu veriyor.
        Instant en = null, boy = null;
        for (TimelineSpan span : recordedSpans(channelId)) {
            Instant s = span.start().isAfter(start) ? span.start() : start;
            Instant e = span.end().isBefore(end) ? span.end() : end;
            if (!e.isAfter(s)) {
                continue;
            }
            en = (en == null || s.isBefore(en)) ? s : en;
            boy = (boy == null || e.isAfter(boy)) ? e : boy;
        }
        return en == null ? Optional.empty() : Optional.of(new TimelineSpan(en, boy));
    }

    // ------------------------------------------------------------------

    private List<RecordingSpan> fetchSpans(Channel channel) {
        try {
            List<RecordingSpan> spans = playback.list(channel.recordingPath());
            return spans == null ? List.of() : spans;
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                // Hiç kayıt yoksa MediaMTX 404 dönüyor; bu bir hata değil.
                return List.of();
            }
            throw AppException.upstreamError(
                "Kayıt listesi alınamadı (HTTP " + e.getResponse().getStatus() + ").", e);
        }
    }

    private TimelineSpan toTimelineSpan(RecordingSpan span) {
        try {
            Instant start = Instant.parse(span.start());
            return new TimelineSpan(start, start.plusMillis((long) (span.duration() * 1000)));
        } catch (DateTimeParseException e) {
            LOG.warnf("MediaMTX'ten çözülemeyen kayıt zamanı atlandı: %s", span.start());
            return null;
        }
    }

    private Channel requireChannel(UUID channelId) {
        Channel channel = Channel.findById(channelId);
        if (channel == null) {
            throw AppException.notFound("Kanal bulunamadı: " + channelId);
        }
        return channel;
    }

    /**
     * Kanalı getirir ve geriye sarmasının açık olmasını şart koşar.
     *
     * <p>Yalnızca <b>çizelge</b> uçlarında kullanılıyor. Kayıt okuma
     * ({@link #stream}) bu şarttan muaf: geriye sarması kapalı bir kanalda da
     * manuel/planlı kayıt yüzünden diskte içerik olabilir.
     */
    private Channel requireDvrChannel(UUID channelId) {
        Channel channel = requireChannel(channelId);
        if (!channel.dvrEnabled) {
            throw AppException.badRequest(
                "Bu kanalda geriye sarma kapalı: " + channel.name);
        }
        return channel;
    }

    private void requireSaneRange(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw AppException.badRequest("Süre sıfırdan büyük olmalı.");
        }
        if (duration.compareTo(MAX_CLIP) > 0) {
            throw AppException.badRequest(
                "En fazla " + MAX_CLIP.toHours() + " saatlik bir aralık istenebilir.");
        }
    }
}
