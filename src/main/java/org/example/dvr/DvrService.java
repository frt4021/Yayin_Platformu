package org.example.dvr;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.InputStream;
import org.example.channel.entity.Channel;
import org.example.dvr.dto.TimelineSpan;
import org.example.exception.AppException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Geriye sarma.
 *
 * <h2>Kaynak değişti</h2>
 * Eskiden MediaMTX'in playback sunucusunu ({@code :9996}) sarıyordu. Kayıt
 * nesne depolamaya taşınınca o sunucunun okuyacağı yerel dizin kalmadı; hem
 * çizelge hem aralık artık {@link DvrArchive} üzerinden MinIO'dan geliyor.
 *
 * <p>Dışarıya bakan sözleşme <b>değişmedi</b>: aynı uçlar, aynı DTO'lar.
 * Değişen tek şey verinin nereden geldiği.
 */
@ApplicationScoped
public class DvrService {

    /** Tek seferde çekilebilecek en uzun aralık. Bkz. {@link #requireSaneRange}. */
    public static final Duration MAX_CLIP = Duration.ofHours(2);

    /**
     * Çizelge sorgularının geriye bakabileceği en uzun süre.
     *
     * <p>{@link #recordedSpans} bir pencere almıyor ama sorgu bir pencereye
     * ihtiyaç duyuyor. Saklama süresinden geniş tutuluyor: daha eskisi zaten
     * ILM tarafından silinmiş oluyor.
     */
    private static final Duration LOOKBACK = Duration.ofDays(30);

    @Inject
    DvrArchive archive;

    /**
     * Kanalın verilen aralıkta kaydı olan bölümleri.
     */
    public List<TimelineSpan> timeline(UUID channelId, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw AppException.badRequest("Bitiş zamanı başlangıçtan sonra olmalı.");
        }
        requireDvrChannel(channelId);

        // Pencereye kirpma: kesisen bolumler tam dondugu icin uclar disari
        // tasabiliyor ve arayuz istemedigi araligi cizerdi.
        return archive.spans(channelId, from, to).stream()
            .map(span -> new TimelineSpan(
                span.start().isBefore(from) ? from : span.start(),
                span.end().isAfter(to) ? to : span.end()))
            .toList();
    }

    /**
     * Kaydın belirtilen bölümünü akış olarak döndürür.
     *
     * <p>DVR ŞARTI YOK. Kanalın geriye sarması kapalı olsa bile kayıt
     * <b>bulunabilir</b>: manuel ve planlı kayıt, iş süresince kaydı açıyor
     * ({@code ChannelRecordingGate}). Klip işçisi içeriği tam da buradan
     * çekiyor; şartı korumak, kaydın durdurulup hiçbir klip üretilmemesine
     * yol açıyordu.
     */
    public Response stream(UUID channelId, Instant start, Duration duration) {
        requireChannel(channelId);
        return streamChannel(channelId, start, duration);
    }

    /**
     * Aynı iş, kanal doğrulaması yapılmadan.
     *
     * <p>Klip işçisi için: aktarım bilerek transaction <b>dışında</b> yapılıyor
     * (2 saatlik bir bölüm GB'larca eder, tutmak sunucuyu düşürürdü) ve orada
     * veritabanına dokunmak {@code ContextNotActiveException} veriyor. Segment
     * listesi bu yüzden önce -- transaction içinde -- çıkarılıyor, aktarım
     * yalnızca elindeki listeyle çalışıyor.
     */
    public Response streamChannel(UUID channelId, Instant start, Duration duration) {
        InputStream akis = extractStream(channelId, start, duration);

        // StreamingOutput ile sarmalaniyor: govde belleğe alinmadan
        // aktariliyor ve aktarim bitince akis (dolayisiyla ffmpeg sureci)
        // kapaniyor.
        StreamingOutput govde = out -> {
            try (akis) {
                akis.transferTo(out);
            }
        };
        return Response.ok(govde).type(new MediaType("video", "mp4")).build();
    }

    /**
     * Aralığı <b>ham akış</b> olarak verir — HTTP yanıtı sarmalamadan.
     *
     * <p>Klip işçisi için: içeriği MinIO'ya aktarmak üzere <i>okuması</i>
     * gerekiyor. {@link #streamChannel} bir {@code Response} döndürüyor ve
     * ondan {@code readEntity(InputStream.class)} ile okumak <b>çalışmıyor</b>
     * — o metot istemci yanıtları için; sunucuda kurulmuş bir
     * {@code Response}'ta entity zaten nesnenin kendisi. Yaşandı.
     *
     * <p>Çağıran akışı <b>kapatmalı</b>: kapanışta ffmpeg süreci de
     * sonlandırılıyor.
     */
    public InputStream extractStream(UUID channelId, Instant start, Duration duration) {
        requireSaneRange(duration);
        var plan = archive.plan(channelId, start, start.plus(duration));
        return archive.extract(plan, start, duration);
    }

    /** Kanalın kayıt bulunan aralıkları — klip isteğinin doğrulanmasında da kullanılır. */
    public List<TimelineSpan> recordedSpans(UUID channelId) {
        // stream() ile ayni gerekce: DVR kapali kanalda da manuel/planli
        // kayit yuzunden kayit olabilir.
        requireChannel(channelId);
        Instant now = Instant.now();
        return archive.spans(channelId, now.minus(LOOKBACK), now.plus(Duration.ofMinutes(1)));
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
     * İstenen aralığı <b>gerçekten kaydedilmiş</b> bölüme kırpar.
     *
     * <h2>Neden gerekli</h2>
     * Kullanıcı "kaydı başlat"a bastığı an ile kaydın gerçekten başladığı an
     * aynı değil: kaydedici yayın durumunu yoklayarak çalışıyor ve arada
     * saniyeler geçiyor. Klip {@code [basılan, durdurulan]} aralığı için
     * isteniyordu; başlangıçta veri olmadığı için kullanıcı
     * <i>"Bu aralıkta kayıt bulunamadı"</i> alıyordu.
     *
     * <p>Kırpmak, sessizce eksik vermek değil — kaydedilen <b>tam olarak</b>
     * budur. Alternatif olan 404, kullanıcının elinde hiçbir şey bırakmıyordu.
     *
     * @return kırpılmış aralık; hiç örtüşme yoksa {@link Optional#empty()}
     */
    public Optional<TimelineSpan> clampToRecorded(UUID channelId, Instant start, Instant end) {
        // En genis ortusme aliniyor. Aralik birden fazla bolume yayilmis
        // olabilir (kayit sirasinda kaynak koptu); bosluklu aralik da
        // cikarilabildigi icin uclari daraltmak yerine en genis ortusme
        // dogru sonucu veriyor.
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
     * manuel/planlı kayıt yüzünden içerik olabilir.
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
