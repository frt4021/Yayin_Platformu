package org.example.subtitle;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canlı altyazının yetişip yetişmediğini ölçer.
 *
 * <h2>Neden "gecikme" yanlış soru</h2>
 * Arayüz altyazıyı <b>geldiği ana göre değil, taşıdığı zaman damgasına göre</b>
 * gösteriyor ({@code SubtitleOverlay}: {@code baslangic <= playingDate() < bitis}).
 * Bunun doğrudan sonucu şu: <b>geç kalan altyazı geç gösterilmez, hiç
 * gösterilmez.</b> Oynatma kafası {@code bitis}'i geçtikten sonra gelen bölüt
 * süzgeçten düşer.
 *
 * <p>Yani izleyicinin algıladığı gecikme yapısal olarak <b>her zaman sıfır</b>.
 * "Gecikme yok" gözlemi boru hattının hızlı olduğunu göstermez; yalnızca
 * gördüğü altyazıların yetişenler olduğunu gösterir. Yetişemeyenler ekranda
 * zaten yok — ve hiçbir yerde sayılmıyordu.
 *
 * <h2>Ölçülen şey: kapsama</h2>
 * Bir bölütün görünebilmesinin koşulu:
 *
 * <pre>
 *   üretim gecikmesi  &lt;  HLS gecikmesi + bölüt süresi
 *   └ burada ölçülen ┘     └──── bütçe (altyazi.butce-ms) ────┘
 * </pre>
 *
 * <p><b>Üretim gecikmesi</b> = bölüt sesinin bittiği an ile altyazının
 * yayınlandığı an arasındaki fark. Ses bittiği andan itibaren sayılıyor,
 * başladığı andan değil: bölüt kapanmadan çözümleme başlayamıyor, dolayısıyla
 * bölüt süresi gecikmenin parçası değil <b>bütçenin</b> parçası.
 *
 * <h2>Bütçe neden yapılandırılabilir</h2>
 * Sağ taraf <b>sunucudan bilinemez</b>: HLS gecikmesi izleyicinin tamponuna,
 * ağına ve LL-HLS'in gerçekten devreye girip girmediğine bağlı. Varsayılan
 * değer bir <b>varsayım</b>; ölçüm bu varsayıma göre "yetişti/yetişmedi"
 * diyor. İzleyici sanılandan geride oturuyorsa gerçek kapsama raporlanandan
 * yüksektir.
 *
 * <h2>Neden özet log, sayaç değil</h2>
 * Bölüt başına log 20 kanalda saniyede onlarca satır ederdi ve asıl soru tek
 * bir bölüt değil <b>dağılım</b>. Dakikada bir kanal başına özet, sorunun
 * "hangi kanal yetişemiyor" biçimindeki gerçek hâline doğrudan cevap veriyor.
 */
@ApplicationScoped
public class SubtitleLagMetrics {

    private static final Logger LOG = Logger.getLogger(SubtitleLagMetrics.class);

    /**
     * İzleyicinin canlı kenardan ne kadar geride olduğu varsayımı.
     *
     * <p>Bölüt süresi buna eklenerek bütçe bulunuyor — bir altyazı, ait olduğu
     * anın oynatılması bitene kadar gelirse hâlâ görünür.
     */
    @ConfigProperty(name = "altyazi.butce-ms")
    long butceMs;

    /** Kanal başına biriken pencere. */
    private final Map<UUID, Pencere> pencereler = new ConcurrentHashMap<>();

    /**
     * Bir altyazının yayınlandığını kaydeder.
     *
     * <p><b>Hiçbir koşulda patlamamalı:</b> ölçüm altyazı hattının yan ürünü;
     * burada atılan bir istisna bölütü kaybettirirdi.
     *
     * @param bitis      bölüt sesinin bittiği an
     * @param sureMs     bölüt süresi — bütçeye ekleniyor
     */
    public void kaydet(UUID channelId, String channelName, Instant bitis, long sureMs) {
        kaydet(channelId, channelName, bitis, sureMs, Instant.now());
    }

    /**
     * Saat dışarıdan verilen biçim — <b>yalnızca testler için</b>.
     *
     * <p>{@code Instant.now()} ile ölçüm birkaç milisaniye kayıyor ve
     * yüzdelik hesabı sayı sayı doğrulanamıyordu. Sınırdaki davranış (bütçeye
     * <i>tam eşit</i> gecikme) tam da doğrulanması gereken şey olduğu için
     * saatin belirlenebilir olması şart.
     */
    void kaydet(UUID channelId, String channelName, Instant bitis, long sureMs,
                Instant simdi) {
        try {
            long gecikme = simdi.toEpochMilli() - bitis.toEpochMilli();
            pencereler.computeIfAbsent(channelId, id -> new Pencere(channelName))
                .ekle(gecikme, butceMs + sureMs);
        } catch (RuntimeException e) {
            LOG.debugf("Altyazı gecikmesi ölçülemedi: %s", e.getMessage());
        }
    }

    /**
     * Biriken pencereyi özetler ve sıfırlar.
     *
     * <p>Kümülatif değil <b>kayan</b>: saatler sonra "ortalama" tüm geçmişin
     * ortalaması olurdu ve GPU'ya geçmek gibi bir değişikliğin etkisi aylarca
     * görünmezdi.
     */
    @Scheduled(every = "{altyazi.rapor-araligi}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void rapor() {
        for (UUID id : List.copyOf(pencereler.keySet())) {
            Pencere p = pencereler.remove(id);
            if (p == null || p.adet == 0) {
                continue;
            }
            Ozet o = p.ozetle();
            // Yetisemeyen varsa WARN: bu, arayuzde SESSIZCE eksik altyazi
            // demek ve baska hicbir yerde belirti vermiyor.
            if (o.gecKalan() > 0) {
                LOG.warnf("ALTYAZI KAPSAMA %s — %d bölüt, %%%.0f yetişti "
                        + "(%d yetişemedi) | gecikme ort %d ms, p50 %d ms, p95 %d ms, "
                        + "en kötü %d ms | bütçe %d ms",
                    o.kanal(), o.adet(), o.kapsamaYuzde(), o.gecKalan(),
                    o.ortalama(), o.p50(), o.p95(), o.enKotu(), o.butce());
            } else {
                LOG.infof("ALTYAZI KAPSAMA %s — %d bölüt, %%100 yetişti "
                        + "| gecikme ort %d ms, p50 %d ms, p95 %d ms, en kötü %d ms "
                        + "| bütçe %d ms",
                    o.kanal(), o.adet(), o.ortalama(), o.p50(), o.p95(),
                    o.enKotu(), o.butce());
            }
        }
    }

    /** Son raporlanmamış pencerelerin özeti — testler ve teşhis için. */
    public List<Ozet> anlikOzet() {
        List<Ozet> sonuc = new ArrayList<>();
        for (Pencere p : pencereler.values()) {
            if (p.adet > 0) {
                sonuc.add(p.ozetle());
            }
        }
        return sonuc;
    }

    /** Bir kanalın rapor penceresi özeti. */
    public record Ozet(String kanal, int adet, int gecKalan, long ortalama,
                       long p50, long p95, long enKotu, long butce) {

        public double kapsamaYuzde() {
            return adet == 0 ? 0 : 100.0 * (adet - gecKalan) / adet;
        }
    }

    /**
     * Tek kanalın biriken ölçümleri.
     *
     * <p>Gecikmeler <b>olduğu gibi tutuluyor</b>, histogram kovalarına
     * bölünmüyor: bir raporlama aralığında kanal başına en fazla birkaç yüz
     * bölüt oluyor ve p95'i yaklaşık değil <b>gerçek</b> vermek, "p95 4-8 sn
     * arası" demekten çok daha kullanışlı.
     */
    private static final class Pencere {
        private final String kanal;
        private final List<Long> gecikmeler = new ArrayList<>();
        private int adet;
        private int gecKalan;
        private long toplam;
        private long enKotu;
        /** Bütçe bölüt süresine bağlı olduğu için pencerede ortalaması alınıyor. */
        private long butceToplam;

        Pencere(String kanal) {
            this.kanal = kanal;
        }

        synchronized void ekle(long gecikme, long butce) {
            adet++;
            toplam += gecikme;
            butceToplam += butce;
            enKotu = Math.max(enKotu, gecikme);
            gecikmeler.add(gecikme);
            if (gecikme >= butce) {
                gecKalan++;
            }
        }

        synchronized Ozet ozetle() {
            List<Long> sirali = new ArrayList<>(gecikmeler);
            sirali.sort(null);
            return new Ozet(kanal, adet, gecKalan, toplam / adet,
                yuzdelik(sirali, 50), yuzdelik(sirali, 95), enKotu,
                butceToplam / adet);
        }

        private static long yuzdelik(List<Long> sirali, int yuzde) {
            if (sirali.isEmpty()) {
                return 0;
            }
            int i = (int) Math.ceil(yuzde / 100.0 * sirali.size()) - 1;
            return sirali.get(Math.min(Math.max(i, 0), sirali.size() - 1));
        }
    }
}
