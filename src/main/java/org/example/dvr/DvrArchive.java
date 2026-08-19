package org.example.dvr;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.dvr.dto.TimelineSpan;
import org.example.dvr.entity.DvrSegment;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO'daki DVR segmentlerinden zaman çizelgesi ve aralık üretir.
 *
 * <h2>MediaMTX playback sunucusunun yerini alıyor</h2>
 * Eskiden ikisi de MediaMTX'ten geliyordu: çizelge {@code /list}, aralık
 * {@code /get}. Kayıt nesne depolamaya taşınınca o sunucunun okuyacağı dizin
 * kalmadı; ikisini de kendimiz üretiyoruz.
 *
 * <h2>Aralık nasıl çıkarılıyor</h2>
 * <pre>
 * MinIO segmentleri ──birleştir──► ffmpeg -i pipe:0 -ss X -t Y -c copy ──► mp4
 * </pre>
 *
 * Segmentler MPEG-TS ve arka arkaya eklenince tam akış geri geliyor (ölçüldü);
 * ffmpeg bunu tek girdi gibi okuyup istenen aralığı kesiyor.
 *
 * <p><b>Yeniden kodlama yok</b> ({@code -c copy}): kaynak hangi kalitedeyse
 * klip de o kalitede. Kesim en yakın anahtar kareye oturuyor -- kare kare
 * kesmek yeniden kodlama gerektirirdi ve 2 saatlik bir aralıkta bu dakikalar
 * sürerdi.
 *
 * <h2>ffmpeg şart</h2>
 * Bu sınıf ffmpeg olmadan çalışmaz. Backend imajına bu yüzden ffmpeg eklendi;
 * daha önce gerek yoktu çünkü aralığı MediaMTX hazır veriyordu.
 */
@ApplicationScoped
public class DvrArchive {

    private static final Logger LOG = Logger.getLogger(DvrArchive.class);

    /**
     * İki segment arasındaki bu kadarlık boşluk <b>yok sayılıyor</b>.
     *
     * <p>Segmentler arka arkaya yazılıyor ama aralarında ffmpeg'in yeniden
     * bağlanması ya da yükleme gecikmesi yüzünden milisaniyeler oluşabiliyor.
     * Tolerans olmasaydı çizelge, aslında kesintisiz olan bir kaydı yüzlerce
     * parçaya bölünmüş gösterirdi.
     */
    private static final Duration GAP_TOLERANCE = Duration.ofSeconds(3);

    @Inject
    DvrStorage storage;

    @ConfigProperty(name = "dvr.extract-timeout-minutes")
    int extractTimeoutMinutes;

    @ConfigProperty(name = "dvr.gap-fill-enabled")
    boolean gapFillEnabled;

    @ConfigProperty(name = "dvr.gap-fill-min-seconds")
    int gapFillMinSeconds;

    @ConfigProperty(name = "dvr.gap-fill-timeout-seconds")
    int gapFillTimeoutSeconds;

    /**
     * Kanalın kayıtlı aralıkları, bitişik segmentler birleştirilmiş hâlde.
     *
     * <p>Segment başına bir aralık dönmek arayüzde 30 saniyelik yüzlerce
     * parça demek olurdu; kullanıcının görmesi gereken şey "şu saatler
     * arasında kayıt var".
     */
    @Transactional
    public List<TimelineSpan> spans(UUID channelId, Instant from, Instant to) {
        List<DvrSegment> segments = DvrSegment.covering(channelId, from, to);
        List<TimelineSpan> spans = new ArrayList<>();

        Instant basi = null;
        Instant sonu = null;
        for (DvrSegment s : segments) {
            if (basi == null) {
                basi = s.basladi;
                sonu = s.bitti;
                continue;
            }
            if (Duration.between(sonu, s.basladi).compareTo(GAP_TOLERANCE) <= 0) {
                sonu = s.bitti;
            } else {
                spans.add(new TimelineSpan(basi, sonu));
                basi = s.basladi;
                sonu = s.bitti;
            }
        }
        if (basi != null) {
            spans.add(new TimelineSpan(basi, sonu));
        }
        return spans;
    }

    /**
     * İstenen aralığı kapsayan segmentleri getirir.
     *
     * <p>Ayrı metot: aktarım transaction <b>dışında</b> yapılıyor (2 saatlik
     * bir aralık GB'larca eder, transaction boyunca tutmak sunucuyu düşürür)
     * ve orada veritabanına dokunmak {@code ContextNotActiveException} verir.
     * Aynı sorun klip işçisinde de yaşanmıştı.
     */
    @Transactional
    public List<SegmentRef> plan(UUID channelId, Instant start, Instant end) {
        return DvrSegment.covering(channelId, start, end).stream()
            .map(s -> new SegmentRef(s.nesneAnahtari, s.basladi, s.bitti))
            .toList();
    }

    /** Aktarım için gereken asgari segment bilgisi — varlık taşınmıyor. */
    public record SegmentRef(String objectKey, Instant basladi, Instant bitti) {
    }

    /**
     * Segmentlerden istenen aralığı çıkarır ve <b>akış olarak</b> verir.
     *
     * <p>Dönen akış ffmpeg'in stdout'u. Kapatıldığında süreç sonlandırılıyor
     * ve besleyici iş parçacığı durduruluyor — çağıran
     * {@code try-with-resources} kullanmalı.
     *
     * <p><b>Neden {@code StreamingOutput} değil:</b> iki tüketici var ve
     * ihtiyaçları zıt. REST ucu gövdeye <i>yazmak</i> istiyor, klip işçisi
     * MinIO'ya vermek için <i>okumak</i>. Yalnızca {@code StreamingOutput}
     * verilince klip işçisi {@code readEntity(InputStream.class)} ile almaya
     * çalışıyordu ve bu <b>çalışmıyor</b>: {@code readEntity} istemci
     * yanıtları için, sunucuda kurulmuş bir {@code Response}'ta entity zaten
     * nesnenin kendisi. Yaşandı — klipler "Request could not be mapped to
     * type InputStream" ile düşüyordu. Akış vermek ikisini de karşılıyor.
     *
     * @param plan     {@link #plan} çıktısı, zaman sırasında
     * @param start    istenen başlangıç
     * @param duration istenen süre
     */
    public InputStream extract(List<SegmentRef> plan, Instant start, Duration duration) {
        if (plan.isEmpty()) {
            throw AppException.notFound(
                "Bu aralıkta kayıt bulunamadı. Kayıt silinmiş veya o sırada yayın olmamış olabilir.");
        }

        // Ilk segment istenen andan ONCE baslamis olabilir; aradaki fark
        // ffmpeg'e atlanacak sure olarak veriliyor.
        long offsetMs = Math.max(0, start.toEpochMilli() - plan.get(0).basladi().toEpochMilli());
        boolean adtsAac = sesAacMi(plan.get(0).objectKey());

        Process ffmpeg;
        try {
            ffmpeg = spawn(offsetMs, duration, adtsAac);
        } catch (IOException e) {
            throw AppException.internalError("Aralık çıkarma başlatılamadı.", e);
        }
        Thread besleyici = feed(ffmpeg, plan, start, start.plus(duration));
        return new FfmpegStream(ffmpeg, besleyici, extractTimeoutMinutes);
    }

    /**
     * ffmpeg çıktısı — kapanışta süreci de toplayan akış.
     *
     * <p>Ayrı sınıf: {@code process.getInputStream()} doğrudan verilseydi
     * çağıran onu kapattığında ffmpeg <b>hayatta kalırdı</b> ve her klipte
     * bir zombi süreç birikirdi.
     */
    private static final class FfmpegStream extends InputStream {
        private final Process process;
        private final Thread besleyici;
        private final InputStream inner;
        private final int timeoutMinutes;

        FfmpegStream(Process process, Thread besleyici, int timeoutMinutes) {
            this.process = process;
            this.besleyici = besleyici;
            this.inner = process.getInputStream();
            this.timeoutMinutes = timeoutMinutes;
        }

        @Override
        public int read() throws IOException {
            return inner.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return inner.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                inner.close();
            } finally {
                besleyici.interrupt();
                try {
                    // Normal bitiste ffmpeg zaten cikmis olur; bekleme yalnizca
                    // erken kapatmada (istemci baglantiyi kesti) is goruyor.
                    if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                } finally {
                    process.destroy();
                }
            }
        }
    }

    /**
     * Segmentin ses izi AAC mi.
     *
     * <h2>Neden bilmek zorundayız</h2>
     * MPEG-TS'te AAC <b>ADTS çerçeveli</b> taşınıyor; MP4 ise ham AAC + ASC
     * başlığı bekliyor. {@code -c copy} ile aktarırken araya
     * {@code aac_adtstoasc} bit akışı filtresi girmezse muxer birkaç kareden
     * sonra <i>"Malformed AAC bitstream"</i> deyip duruyor.
     *
     * <p>Filtreyi <b>koşulsuz eklemek de olmuyor</b>: AAC olmayan bir ses
     * izinde ffmpeg <i>"Codec 'mp3' is not supported by the bitstream filter"</i>
     * diyerek hiç başlamıyor ve çıktı 0 bayt kalıyor. İkisi de ölçüldü:
     *
     * <table>
     *   <tr><th>ses</th><th>filtre var</th><th>filtre yok</th></tr>
     *   <tr><td>AAC</td><td>25,0 sn / 964 KB</td><td>0,13 sn — muxer duruyor</td></tr>
     *   <tr><td>MP3</td><td>0 bayt — hiç başlamıyor</td><td>10,0 sn / 287 KB</td></tr>
     * </table>
     *
     * @return AAC ise {@code true}; belirlenemezse {@code false} (yanlış
     *         filtre eklemek, eksik filtreden kötü: ilki hiç çalışmıyor)
     */
    private boolean sesAacMi(String objectKey) {
        List<String> cmd = List.of(
            "ffprobe", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=codec_name",
            "-of", "default=nw=1:nk=1",
            "-f", "mpegts", "-i", "pipe:0");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            // Yalnizca bas kismi besleniyor: ffprobe akis basligini okumak icin
            // tum segmente ihtiyac duymuyor ve 11 MB'in tamamini indirmek
            // her aralik istegine gereksiz bir tur ekler.
            Thread.ofVirtual().start(() -> {
                try (InputStream in = storage.get(objectKey);
                     var out = p.getOutputStream()) {
                    out.write(in.readNBytes(PROBE_BYTES));
                } catch (IOException | RuntimeException e) {
                    // ffprobe yeterli veriyi almis olabilir; sessizce geciyoruz.
                }
            });
            String cikti = new String(p.getInputStream().readAllBytes());
            p.waitFor(15, TimeUnit.SECONDS);
            p.destroy();

            // ILK SATIR aliniyor, ciktinin tamami degil. Birlestirilmis TS'te
            // program bilgisi tekrar ettigi icin ffprobe ayni izi BIRDEN COK
            // KEZ basabiliyor ("aac\naac"); tam esitlik arayan karsilastirma
            // bu durumda false donuyordu.
            //
            // Sonucu sessiz ve agirdi: filtre eklenmiyor, ffmpeg AAC'yi MP4'e
            // yazamayip hemen oluyor ve geriye yalnizca 1276 baytlik fMP4
            // basligi kaliyor. Klip "HAZIR" isaretleniyor ama BOS. Olculdu.
            String ilkSatir = cikti.lines().findFirst().orElse("").strip();
            return "aac".equalsIgnoreCase(ilkSatir);
        } catch (IOException e) {
            LOG.warnf("Ses biçimi belirlenemedi (%s): %s", objectKey, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Ses biçimini anlamak için okunan bayt. Akış başlığı için fazlasıyla yeterli. */
    private static final int PROBE_BYTES = 512 * 1024;

    private Process spawn(long offsetMs, Duration duration, boolean adtsAac) throws IOException {
        List<String> cmd = new java.util.ArrayList<>(List.of(
            "ffmpeg", "-v", "error",
            // Girdi bicimi ACIKCA veriliyor: boru geriye sarilamadigi icin
            // ffmpeg bicimi tahmin etmek zorunda kalir ve TS'i bazen
            // taniyamaz.
            "-f", "mpegts", "-i", "pipe:0",
            // -ss GIRDIDEN SONRA: boru uzerinde girdi tarafi arama yapilamaz.
            "-ss", String.format(java.util.Locale.ROOT, "%.3f", offsetMs / 1000.0),
            "-t", String.format(java.util.Locale.ROOT, "%.3f", duration.toMillis() / 1000.0),
            "-c", "copy"));

        // TS'teki ADTS cerceveli AAC'i MP4'un bekledigi ham bicime cevirir.
        // Bkz. sesAacMi(): olmazsa muxer birkac kareden sonra duruyor,
        // gereksiz yere eklenirse hic baslamiyor.
        if (adtsAac) {
            cmd.addAll(List.of("-bsf:a", "aac_adtstoasc"));
        }

        cmd.addAll(List.of(
            // Parcali mp4 SART: cikis bir boru ve normal mp4 sonunda basa
            // donup moov kutusunu yazmak istiyor -- boruda bu imkansiz.
            //
            // Bayrak adi "default_base_moof"; "default_base_is_moof" DEGIL.
            // Ikincisi ozelligin adi (tfhd kutusundaki default-base-is-moof
            // bayragi) ama ffmpeg secenegi kisa yazimla tanimli. Yanlis ad
            // ffmpeg'i baslatmadan dusuruyor ve geriye 0 baytlik bir cikti
            // kaliyor -- once tam bu sekilde yazildi ve ancak ucdan uca
            // denenince goruldu.
            "-movflags", "frag_keyframe+empty_moov+default_base_moof",
            "-f", "mp4", "pipe:1"));

        // stderr ayri kaliyor: hata metni mp4 verisinin icine karisirsa
        // dosya bozulur.
        Process p = new ProcessBuilder(cmd).start();
        Thread.ofVirtual().start(() -> {
            try (InputStream err = p.getErrorStream()) {
                String metin = new String(err.readAllBytes());
                if (!metin.isBlank()) {
                    LOG.warnf("Aralık çıkarma ffmpeg çıktısı: %s", metin.strip());
                }
            } catch (IOException e) {
                // Surec kapanirken beklenen.
            }
        });
        return p;
    }

    /**
     * Segmentleri sırayla ffmpeg'in girdisine akıtır.
     *
     * <p>Ayrı iş parçacığı <b>şart</b>: aynı iş parçacığından hem yazıp hem
     * okumaya çalışmak kilitlenme demek. ffmpeg'in çıktı borusu dolduğunda
     * ffmpeg yazmayı bekler, biz de yazmayı beklediğimiz için hiç okumayız.
     *
     * <p><b>Boşluk doldurma:</b> segmentler arasında (ya da istenen başlangıç/
     * bitişle ilk/son segment arasında) {@code gapFillMinSeconds}'i aşan bir
     * boşluk varsa, oraya gerçek video yerine karanlık+sessiz bir dolgu
     * yazılır. Amaç: çıkan dosyanın süresi istenen süreyle eşleşsin ve
     * kullanıcı zaman çizelgesinde sessizce ileri zıplanan değil, gerçekten
     * kesintiye uğramış bir an görsün. Dolgu üretimi başarısız olursa
     * (ör. ffprobe/ffmpeg çökerse) o boşluk eskisi gibi sessizce atlanır —
     * bu özellik olmadan da klip yine üretilebilmeli.
     */
    private Thread feed(Process ffmpeg, List<SegmentRef> plan, Instant istenenBaslangic, Instant istenenBitis) {
        return Thread.ofVirtual().start(() -> {
            try (OutputStream in = ffmpeg.getOutputStream()) {
                Instant oncekiBitti = istenenBaslangic;
                VideoProfil profil = null;
                boolean adtsAac = plan.isEmpty() ? false : sesAacMi(plan.get(0).objectKey());

                for (SegmentRef ref : plan) {
                    Duration bosluk = Duration.between(oncekiBitti, ref.basladi());
                    if (gapFillEnabled && bosluk.compareTo(Duration.ofSeconds(gapFillMinSeconds)) > 0) {
                        if (profil == null) {
                            profil = profilCikar(ref.objectKey());
                        }
                        bosluguDoldur(in, bosluk, profil, adtsAac, ref.objectKey());
                    }

                    try (InputStream segment = storage.get(ref.objectKey())) {
                        segment.transferTo(in);
                    } catch (RuntimeException e) {
                        // Tek segmentin kaybi tum araligi dusurmemeli: kalan
                        // segmentler yine de verilir, sonucta o kadarlik bir
                        // boslugu olan bir dosya cikar (dolgu da basarisiz
                        // olursa ayni sonuc).
                        LOG.warnf("DVR segmenti atlandı (%s): %s",
                            ref.objectKey(), e.getMessage());
                    }
                    oncekiBitti = ref.bitti();
                }

                // Kuyruktaki bosluk: son segment istenen bitisten once bitiyorsa.
                // profil onceki dongude hic hesaplanmamis olabilir (araliktaki
                // TEK boslugun kendisi kuyrukta ise) -- burada da son segmentten
                // tembel olarak cikariliyor.
                Duration kuyrukBoslugu = Duration.between(oncekiBitti, istenenBitis);
                if (gapFillEnabled && kuyrukBoslugu.compareTo(Duration.ofSeconds(gapFillMinSeconds)) > 0) {
                    if (profil == null && !plan.isEmpty()) {
                        profil = profilCikar(plan.get(plan.size() - 1).objectKey());
                    }
                    if (profil != null) {
                        bosluguDoldur(in, kuyrukBoslugu, profil, adtsAac, "kuyruk");
                    }
                }
            } catch (IOException e) {
                // ffmpeg erken kapandi (ornegin -t suresi doldu ve cikti
                // tamamlandi). Beklenen bir durum, gurultu yapmiyoruz.
                LOG.debugf("Aralık beslemesi erken bitti: %s", e.getMessage());
            }
        });
    }

    /** Karanlık dolgu için hedef video profili — bir gerçek segmentten çıkarılıyor. */
    private record VideoProfil(int genislik, int yukseklik, String fps) {
    }

    /** Boyut/kare hızı belirlenemezse düşülecek değer — yaygın bir IPTV çözünürlüğü. */
    private static final VideoProfil VARSAYILAN_PROFIL = new VideoProfil(1280, 720, "25");

    /**
     * Dolgunun gerçek segmentlerle aynı çözünürlük/kare hızında olması şart:
     * {@code -c copy} ile tek geçişte birleştirildiği için ortada bir
     * çözünürlük değişikliği olursa oynatıcı şaşırabilir. Belirlenemezse
     * {@link #VARSAYILAN_PROFIL} kullanılır — dolgu yine de üretilir, yalnızca
     * gerçek segmentle tam eşleşmeyebilir.
     */
    private VideoProfil profilCikar(String objectKey) {
        List<String> cmd = List.of(
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height,r_frame_rate",
            "-of", "csv=p=0:s=,",
            "-f", "mpegts", "-i", "pipe:0");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            Thread.ofVirtual().start(() -> {
                try (InputStream in = storage.get(objectKey);
                     var out = p.getOutputStream()) {
                    out.write(in.readNBytes(PROBE_BYTES));
                } catch (IOException | RuntimeException e) {
                    // ffprobe yeterli veriyi almis olabilir; sessizce geciyoruz.
                }
            });
            String cikti = new String(p.getInputStream().readAllBytes());
            p.waitFor(15, TimeUnit.SECONDS);
            p.destroy();

            String ilkSatir = cikti.lines().findFirst().orElse("").strip();
            String[] parcalar = ilkSatir.split(",");
            if (parcalar.length == 3) {
                int genislik = Integer.parseInt(parcalar[0].trim());
                int yukseklik = Integer.parseInt(parcalar[1].trim());
                String fps = parcalar[2].trim();
                if (genislik > 0 && yukseklik > 0 && !fps.isBlank()) {
                    return new VideoProfil(genislik, yukseklik, fps);
                }
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            LOG.debugf("Dolgu için video profili belirlenemedi (%s): %s", objectKey, e.getMessage());
        }
        return VARSAYILAN_PROFIL;
    }

    /**
     * Karanlık+sessiz bir dolgu üretip doğrudan ffmpeg'in girdi borusuna yazar.
     *
     * <p>Süre saniyeler mertebesinde olduğu için tamamı belleğe alınıyor —
     * ayrı bir geçici dosya açmaya gerek yok.
     */
    private void bosluguDoldur(OutputStream hedef, Duration bosluk, VideoProfil profil,
                                boolean adtsAac, String baglamAdi) {
        double saniye = bosluk.toMillis() / 1000.0;
        Path out = null;
        try {
            out = Files.createTempFile("dvr-dolgu", ".ts");
            List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-v", "error", "-nostdin",
                "-f", "lavfi", "-i",
                "color=c=black:s=" + profil.genislik() + "x" + profil.yukseklik() + ":r=" + profil.fps(),
                "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                "-t", String.format(java.util.Locale.ROOT, "%.3f", saniye),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"));
            // Gercek segmentler ADTS AAC ise dolgu da oyle olmali -- karisik
            // bicimde tek bir aac_adtstoasc filtresi ya baslamiyor ya da
            // yarisinda duruyor (bkz. sesAacMi javadoc'u, aynı gerekce).
            cmd.addAll(adtsAac
                ? List.of("-c:a", "aac")
                : List.of("-c:a", "mp2"));
            cmd.addAll(List.of("-shortest", "-f", "mpegts", "-y", out.toString()));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String cikti = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(gapFillTimeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("dolgu üretimi zaman aşımına uğradı");
            }
            if (p.exitValue() != 0) {
                throw new IOException("dolgu üretimi başarısız (kod " + p.exitValue() + "): " + cikti.strip());
            }

            Files.copy(out, hedef);
            LOG.infof("DVR boşluğu dolduruldu (%s öncesi/sonrası, %.1f sn)", baglamAdi, saniye);
        } catch (IOException | InterruptedException e) {
            // Dolgu basarisiz olsa bile klip uretimi devam etmeli -- bu
            // ozellik olmadan da eski davranis (sessizce atlama) gecerliydi.
            LOG.warnf("DVR boşluğu doldurulamadı (%s, %.1f sn): %s", baglamAdi, saniye, e.getMessage());
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException e) {
                    LOG.debugf("Geçici dolgu dosyası silinemedi: %s", out);
                }
            }
        }
    }
}
