package org.example.subtitle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubtitleLagMetrics}'in kapsama hesabı.
 *
 * <p>Bu ölçümün <b>tek işi</b> arayüzde görünmeyen bir şeyi görünür kılmak:
 * geç kalan altyazı geç değil <b>hiç</b> gösterilmiyor, dolayısıyla ekrana
 * bakarak yetişmeyen bölüt sayılamıyor. Hesap yanlışsa ölçüm sessizce yanlış
 * güven verir ve arayüzde de belirti olmadığı için kimse fark etmez.
 *
 * <p>Saat testlerde dışarıdan veriliyor: {@code Instant.now()} ile ölçüm
 * birkaç milisaniye kayar ve asıl doğrulanması gereken şey — bütçeye
 * <b>tam eşit</b> gecikmenin ne sayıldığı — hiç test edilemezdi.
 */
class SubtitleLagMetricsTest {

    private static final Instant SIMDI = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void butceyeEsitGecikmeYetismemisSayilir() {
        // Arayuz suzgeci kati: "bitis > now". Damganin tam ustune denk gelen
        // altyazi GOSTERILMIYOR. Olcum gevsek olsaydi kapsamayi oldugundan
        // iyi raporlardi -- ve bunu fark ettirecek baska bir belirti yok.
        var m = metrics(1_000);
        kaydet(m, 1_000, 0);

        SubtitleLagMetrics.Ozet o = tekOzet(m);
        assertEquals(1, o.adet());
        assertEquals(1, o.gecKalan(), "bütçeye eşit gecikme yetişmiş sayılmamalı");
        assertEquals(0.0, o.kapsamaYuzde());
    }

    @Test
    void butceninAltindakiGecikmeYetismisSayilir() {
        var m = metrics(1_000);
        kaydet(m, 999, 0);

        SubtitleLagMetrics.Ozet o = tekOzet(m);
        assertEquals(0, o.gecKalan());
        assertEquals(100.0, o.kapsamaYuzde());
    }

    @Test
    void bolutSuresiButceyeEkleniyor() {
        // 5 sn gecikme, 2 sn butce varsayimi, 4 sn bolut -> butce 6 sn, yetisir.
        // Bolut suresi eklenmeseydi yetismemis sayilirdi: bir altyazi, ait
        // oldugu anin oynatilmasi BITENE KADAR gelirse hala gorunur.
        var m = metrics(2_000);
        kaydet(m, 5_000, 4_000);

        SubtitleLagMetrics.Ozet o = tekOzet(m);
        assertEquals(0, o.gecKalan());
        assertEquals(6_000, o.butce());
    }

    @Test
    void yuzdelikDegerlerGercek() {
        // Histogram kovasi degil ham degerler tutuluyor; "p95 4-8 sn arasi"
        // demek yerine kesin sayi verilebiliyor.
        var m = metrics(100_000);   // hicbiri gec kalmasin
        for (long gecikme : new long[]{100, 200, 300, 400, 500, 600, 700, 800, 900, 1000}) {
            kaydet(m, gecikme, 0);
        }

        SubtitleLagMetrics.Ozet o = tekOzet(m);
        assertEquals(10, o.adet());
        assertEquals(550, o.ortalama());
        assertEquals(500, o.p50());
        assertEquals(1000, o.p95());
        assertEquals(1000, o.enKotu());
    }

    @Test
    void kapsamaYuzdesiDogru() {
        var m = metrics(1_000);
        for (long gecikme : new long[]{100, 200, 5_000, 6_000}) {
            kaydet(m, gecikme, 0);
        }

        SubtitleLagMetrics.Ozet o = tekOzet(m);
        assertEquals(4, o.adet());
        assertEquals(2, o.gecKalan());
        assertEquals(50.0, o.kapsamaYuzde());
    }

    @Test
    void kanallarAyriRaporlaniyor() {
        // 20 kanal calisirken sorulan soru "HANGI kanal yetisemiyor" --
        // tek bir toplam bunu gizlerdi.
        var m = metrics(1_000);
        m.kaydet(UUID.randomUUID(), "hizli", SIMDI.minusMillis(100), 0, SIMDI);
        m.kaydet(UUID.randomUUID(), "yavas", SIMDI.minusMillis(50_000), 0, SIMDI);

        List<SubtitleLagMetrics.Ozet> hepsi = m.anlikOzet();
        assertEquals(2, hepsi.size());
        assertTrue(hepsi.stream().anyMatch(o -> o.kanal().equals("hizli") && o.gecKalan() == 0));
        assertTrue(hepsi.stream().anyMatch(o -> o.kanal().equals("yavas") && o.gecKalan() == 1));
    }

    @Test
    void olcumHicbirKosuldaPatlamaz() {
        // Olcum altyazi hattinin YAN URUNU: burada atilan bir istisna bolutu
        // kaybettirirdi.
        var m = metrics(1_000);
        m.kaydet(UUID.randomUUID(), "kanal", null, 0, SIMDI);
        assertTrue(m.anlikOzet().isEmpty(), "hatalı kayıt pencereye girmemeli");
    }

    // ------------------------------------------------------------------

    private static SubtitleLagMetrics metrics(long butceMs) {
        var m = new SubtitleLagMetrics();
        m.butceMs = butceMs;
        return m;
    }

    /** Tek kanala, verilen gecikmeyi üretecek bir bölüt kaydeder. */
    private static void kaydet(SubtitleLagMetrics m, long gecikmeMs, long sureMs) {
        m.kaydet(KANAL, "kanal", SIMDI.minusMillis(gecikmeMs), sureMs, SIMDI);
    }

    private static SubtitleLagMetrics.Ozet tekOzet(SubtitleLagMetrics m) {
        List<SubtitleLagMetrics.Ozet> hepsi = m.anlikOzet();
        assertEquals(1, hepsi.size(), "tek kanal bekleniyordu");
        return hepsi.get(0);
    }

    private static final UUID KANAL = UUID.randomUUID();
}
