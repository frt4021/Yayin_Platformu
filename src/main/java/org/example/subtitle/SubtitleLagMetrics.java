package org.example.subtitle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   üretim gecikmesi  &lt;  HLS gecikmesi
 *   └ burada ölçülen ┘     └ bütçe (altyazi.butce-ms) ┘
 * </pre>
 *
 * <p><b>Üretim gecikmesi</b> = bölüt sesinin bittiği an ile altyazının
 * yayınlandığı an arasındaki fark. Ses bittiği andan itibaren sayılıyor,
 * başladığı andan değil: bölüt kapanmadan çözümleme başlayamıyor.
 *
 * <p><b>Bölüt süresi bütçeye EKLENMİYOR.</b> İlk sürümde eklenmişti ve
 * yanlıştı. Arayüz süzgeci {@code bitis > playingDate()} diyor: altyazının
 * izleyici o bölütü <b>bitirmeden</b> gelmesi gerekiyor. Altyazı
 * {@code bitis + üretim} anında hazır oluyor, izleyici oraya
 * {@code bitis + HLS} anında varıyor; koşul sadeleşince geriye
 * {@code üretim < HLS} kalıyor. Eklemek kapsamayı olduğundan iyi
 * gösteriyordu -- bölüt süresi kadar.
 *
 * <p>Bölüt süresi yine de anlamlı, ama başka bir eşikte: altyazının bölütün
 * <b>tamamı</b> boyunca ekranda kalması için {@code üretim < HLS - bölüt
 * süresi} gerekiyor. Arası "kısmi": cümlenin yalnızca sonu görünüyor.
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

    @Inject
    MeterRegistry meterRegistry;

    /** Kanal başına biriken pencere. */
    private final Map<UUID, Pencere> pencereler = new ConcurrentHashMap<>();

    /**
     * Grafana'nın okuduğu son rapor değerleri, kanal başına — bu, WARN/INFO
     * log satırındaki ("ALTYAZI KAPSAMA ...") sayıların birebir metrik
     * karşılığı. Log grep'e alternatif: Loki eklemeden aynı bilgi sorgulanabilir
     * ve zaman içinde grafiklenebilir olsun diye.
     *
     * <p>Dizi indeksleri: 0 ortalama, 1 p50, 2 p95, 3 en kötü, 4 kapsama yüzdesi.
     * Gauge'lar bu diziye canlı referans tutuyor; her rapor döngüsünde
     * içerik güncelleniyor, nesne değişmiyor.
     */
    private final Map<UUID, double[]> sonRapor = new ConcurrentHashMap<>();
    private final Set<UUID> gaugeKayitli = ConcurrentHashMap.newKeySet();

    /**
     * İzleyicilerin bildirdiği <b>gerçek</b> HLS gecikmesi, kanal başına.
     *
     * <p>{@code altyazi.butce-ms} yalnızca bir <b>varsayım</b>: sunucu
     * izleyicinin canlı kenardan ne kadar geride olduğunu bilemez, bu
     * tamponuna ve ağına bağlı. Ölçüm geldiğinde varsayımın yerine geçiyor
     * ve kapsama kararı iki ölçülmüş sayının karşılaştırması oluyor.
     *
     * <p>Değer <b>uçucu</b>: son bildirim tutuluyor, geçmiş biriktirilmiyor.
     * İzleyici ayrıldığında eski değer bir süre kalıyor -- kısa bir pencerede
     * yanlış olması, varsayıma geri dönmekten iyi.
     */
    private final Map<UUID, Long> hlsGecikmeleri = new ConcurrentHashMap<>();

    /**
     * Bir izleyicinin ölçtüğü HLS gecikmesini kaydeder.
     *
     * <p>Değer tarayıcıda {@code Date.now() - playingDate()} ile bulunuyor.
     * Aynı kanalı birden fazla kişi izliyorsa son bildiren geçerli oluyor;
     * ortalama almak, tamponu bozuk tek bir istemcinin ölçümü kaydırmasını
     * engellemezdi ve karmaşıklığa değmiyor.
     *
     * @param ms izleyicinin canlı kenardan geride olma süresi
     */
    public void hlsGecikmeBildir(UUID channelId, long ms) {
        // Akil disi degerler yok sayiliyor: saati bozuk bir istemci ya da
        // geriye sarma modundaki bir oynatici saatlerce gecikme bildirebilir
        // ve butceyi anlamsizlastirirdi.
        if (ms < 0 || ms > 120_000) {
            LOG.debugf("HLS gecikmesi yok sayıldı (%s): %d ms", channelId, ms);
            return;
        }
        hlsGecikmeleri.put(channelId, ms);
    }

    /**
     * Bir altyazının yayınlandığını kaydeder.
     *
     * <p><b>Hiçbir koşulda patlamamalı:</b> ölçüm altyazı hattının yan ürünü;
     * burada atılan bir istisna bölütü kaybettirirdi.
     *
     * @param bitis      bölüt sesinin bittiği an
     * @param sureMs     bölüt süresi — bütçeye değil, kısmi/tam görünürlük
     *                   eşiğine giriyor
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
            // Olculen HLS gecikmesi varsa VARSAYIMIN yerine geciyor.
            Long olculen = hlsGecikmeleri.get(channelId);
            pencereler.computeIfAbsent(channelId, id -> new Pencere(channelName))
                .ekle(gecikme, olculen != null ? olculen : butceMs, sureMs,
                    olculen != null);
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
            metrikGuncelle(id, o);
            // Yetisemeyen varsa WARN: bu, arayuzde SESSIZCE eksik altyazi
            // demek ve baska hicbir yerde belirti vermiyor.
            if (o.gecKalan() > 0) {
                LOG.warnf("ALTYAZI KAPSAMA %s — %d bölüt: %d tam, %d kısmi, "
                        + "%d görünmedi (%%%.0f yetişti) | gecikme ort %d ms, "
                        + "p50 %d ms, p95 %d ms, en kötü %d ms | bütçe %d ms (%s)",
                    o.kanal(), o.adet(), o.tamGorunen(), o.kismiGorunen(),
                    o.gecKalan(), o.kapsamaYuzde(),
                    o.ortalama(), o.p50(), o.p95(), o.enKotu(),
                    o.butce(), o.butceKaynagi());
            } else {
                LOG.infof("ALTYAZI KAPSAMA %s — %d bölüt: %d tam, %d kısmi "
                        + "(%%100 yetişti) | gecikme ort %d ms, p50 %d ms, "
                        + "p95 %d ms, en kötü %d ms | bütçe %d ms (%s)",
                    o.kanal(), o.adet(), o.tamGorunen(), o.kismiGorunen(),
                    o.ortalama(), o.p50(), o.p95(), o.enKotu(),
                    o.butce(), o.butceKaynagi());
            }
        }
    }

    /**
     * Rapor değerlerini Grafana'nın okuyabileceği gauge'lara yazar.
     *
     * <p>Gauge'lar kanal başına <b>bir kez</b> kaydediliyor ({@code
     * gaugeKayitli}); sonraki her çağrıda yalnızca arkasındaki dizi
     * güncelleniyor. Aksi halde her rapor döngüsünde yeni bir meter
     * kaydedilir ve Prometheus'ta aynı kanal için sonsuz seri birikirdi.
     */
    private void metrikGuncelle(UUID channelId, Ozet o) {
        double[] deger = sonRapor.computeIfAbsent(channelId, id -> new double[5]);
        deger[0] = o.ortalama();
        deger[1] = o.p50();
        deger[2] = o.p95();
        deger[3] = o.enKotu();
        deger[4] = o.kapsamaYuzde();

        if (gaugeKayitli.add(channelId)) {
            for (var istatistik : java.util.List.of(
                    java.util.Map.entry("ortalama", 0), java.util.Map.entry("p50", 1),
                    java.util.Map.entry("p95", 2), java.util.Map.entry("en_kotu", 3))) {
                int idx = istatistik.getValue();
                Gauge.builder("altyazi_gecikme_ms", deger, d -> d[idx])
                    .tag("kanal", o.kanal())
                    .tag("istatistik", istatistik.getKey())
                    .description("Üretim gecikmesi (bölüt bitişi -> yayın anı), rapor penceresi özeti")
                    .register(meterRegistry);
            }
            Gauge.builder("altyazi_kapsama_yuzde", deger, d -> d[4])
                .tag("kanal", o.kanal())
                .description("Bölütlerin yüzde kaçı bütçe içinde yayınlandı (izleyicinin görebileceği kadar hızlı)")
                .register(meterRegistry);
        }
    }

    /**
     * Kanal kapandığında çağrılır — kayıtlı gauge'ları ve biriken durumu siler.
     *
     * <p>{@code metrikGuncelle} gauge'ları {@code gaugeKayitli} korumasıyla
     * kanal başına bir kez kaydediyor; bu koruma olmadan yayından çıkmış bir
     * kanal için {@code sonRapor}'daki dizi donmuş son değerinde Prometheus'ta
     * sonsuza dek raporlanmaya devam ederdi (kanal silinse bile).
     */
    public void temizle(UUID channelId, String channelName) {
        meterRegistry.find("altyazi_gecikme_ms").tag("kanal", channelName)
            .meters().forEach(meterRegistry::remove);
        meterRegistry.find("altyazi_kapsama_yuzde").tag("kanal", channelName)
            .meters().forEach(meterRegistry::remove);
        gaugeKayitli.remove(channelId);
        sonRapor.remove(channelId);
        pencereler.remove(channelId);
        hlsGecikmeleri.remove(channelId);
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
    public record Ozet(String kanal, int adet, int gecKalan, int kismiGorunen,
                       long ortalama, long p50, long p95, long enKotu, long butce,
                       boolean butceOlculdu) {

        /** Rapor satırında bütçenin kaynağını belirtiyor. */
        public String butceKaynagi() {
            return butceOlculdu ? "ölçüldü" : "varsayım";
        }

        /** Hiç görünmeyenler dışındakiler. */
        public double kapsamaYuzde() {
            return adet == 0 ? 0 : 100.0 * (adet - gecKalan) / adet;
        }

        /** Bölütün <b>tamamı</b> boyunca ekranda kalanlar. */
        public int tamGorunen() {
            return adet - gecKalan - kismiGorunen;
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
        /** Bütçe ölçülen bir değerden mi geldi, yoksa varsayımdan mı. */
        private boolean butceOlculdu;
        /** Yetişti ama bölütün yalnızca sonunda göründü. */
        private int kismiGorunen;
        private long toplam;
        private long enKotu;
        /** Bütçe bölüt süresine bağlı olduğu için pencerede ortalaması alınıyor. */
        private long butceToplam;

        Pencere(String kanal) {
            this.kanal = kanal;
        }

        synchronized void ekle(long gecikme, long butce, long sureMs, boolean olculdu) {
            butceOlculdu = olculdu;
            adet++;
            toplam += gecikme;
            butceToplam += butce;
            enKotu = Math.max(enKotu, gecikme);
            gecikmeler.add(gecikme);
            if (gecikme >= butce) {
                gecKalan++;
            } else if (gecikme >= butce - sureMs) {
                // Yetisti ama gec: bolutun yalnizca SON kismi boyunca ekranda
                // kaldi, izleyici cumlenin basini goremedi.
                kismiGorunen++;
            }
        }

        synchronized Ozet ozetle() {
            List<Long> sirali = new ArrayList<>(gecikmeler);
            sirali.sort(null);
            return new Ozet(kanal, adet, gecKalan, kismiGorunen, toplam / adet,
                yuzdelik(sirali, 50), yuzdelik(sirali, 95), enKotu,
                butceToplam / adet, butceOlculdu);
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
