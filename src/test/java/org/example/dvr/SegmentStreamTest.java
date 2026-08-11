package org.example.dvr;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SegmentStream}'in kesme davranışı.
 *
 * <p>Buradaki iki kural bozulursa <b>kayıt sessizce bozulur</b>: nesneler
 * MinIO'ya yazılmaya devam eder, boyutları makul görünür, ama çözücü
 * senkronu bulamadığı için geriye sarmada görüntü gelmez. Hiçbir log satırı
 * bunu haber vermez -- bu yüzden kilitleniyor.
 *
 * <ol>
 *   <li><b>188 bayt hizalama.</b> Paketin ortasından kesilirse hem o
 *       segmentin sonu hem sonrakinin başı yarım paketle başlar.</li>
 *   <li><b>Alttaki boru kapanmamalı.</b> MinIO SDK yüklemeyi bitirince
 *       verilen akışı kapatıyor; gerçekten kapansaydı ffmpeg'in stdout'u ilk
 *       segmentten sonra kapanır ve kayıt tek segmentle biterdi.</li>
 * </ol>
 */
class SegmentStreamTest {

    private static final int PACKET = SegmentStream.TS_PACKET;

    @Test
    void kesimHepPaketSinirinda() throws IOException {
        // Sure hemen dolsun (0 ms): ilk okumadan sonra kesilecek.
        byte[] kaynak = tsVerisi(100 * PACKET);
        var stream = new SegmentStream(new ByteArrayInputStream(kaynak), 0);

        long okunan = tumunuOku(stream);

        assertEquals(0, okunan % PACKET,
            "kesim paket sınırında olmalı, yarım paket bırakılamaz");
    }

    @Test
    void yarimPaketOrtasindaKesilmiyor() throws IOException {
        // Kaynak paketleri TEK TEK veriyor ki kesim karari paket ortasinda
        // alinmak zorunda kalsin -- gercek boruda da kisa okumalar oluyor.
        byte[] kaynak = tsVerisi(10 * PACKET);
        var yavas = new ByteArrayInputStream(kaynak) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                // Paket boyutundan KUCUK parcalar: hizalama mantigi
                // kendi basina calismak zorunda.
                return super.read(b, off, Math.min(len, 50));
            }
        };
        var stream = new SegmentStream(yavas, 0);

        assertEquals(0, tumunuOku(stream) % PACKET);
    }

    @Test
    void surebitmedenTumVeriGeciyor() throws IOException {
        byte[] kaynak = tsVerisi(20 * PACKET);
        // Uzun sure: kaynak bitene kadar okunmali.
        var stream = new SegmentStream(new ByteArrayInputStream(kaynak), 60_000);

        assertEquals(kaynak.length, tumunuOku(stream));
        assertTrue(stream.sourceEnded(), "kaynak tükendiyse bildirilmeli");
    }

    @Test
    void kaynakTamSinirdaBitseDeBildiriliyor() throws IOException {
        // Regresyon: sourceEnded eskiden "bayt sayisi paket siniri DEGILSE"
        // diye hesaplaniyordu. Kaynak tam sinirda bittiginde yanlis cevap
        // veriyor ve ffmpeg oldugu halde yeniden baslatilmiyordu.
        byte[] kaynak = tsVerisi(5 * PACKET);
        var stream = new SegmentStream(new ByteArrayInputStream(kaynak), 60_000);
        tumunuOku(stream);

        assertEquals(0, stream.bytesRead() % PACKET, "kaynak tam sınırda bitti");
        assertTrue(stream.sourceEnded(), "tam sınırda bitse de kaynak tükendi sayılmalı");
    }

    @Test
    void sureDolduysaKaynakTukenmisSayilmiyor() throws IOException {
        // Ayrim onemli: "segment bitti" ffmpeg'i yeniden baslatmayi
        // GEREKTIRMEZ, "kaynak bitti" gerektirir. Ikisi de read() = -1 ile
        // gorunuyor ve karistirilirsa her segmentte ffmpeg yeniden kurulurdu.
        byte[] kaynak = tsVerisi(100 * PACKET);
        var stream = new SegmentStream(new ByteArrayInputStream(kaynak), 0);
        tumunuOku(stream);

        assertFalse(stream.sourceEnded(),
            "süre dolduğu için kesildi, kaynakta hâlâ veri var");
    }

    @Test
    void altakiBoruKapatilmiyor() throws IOException {
        byte[] kaynak = tsVerisi(10 * PACKET);
        var izlenen = new KapanmayiIzleyen(new ByteArrayInputStream(kaynak));

        var stream = new SegmentStream(izlenen, 0);
        tumunuOku(stream);
        stream.close();

        assertFalse(izlenen.kapandi,
            "alttaki boru kapanırsa ffmpeg ilk segmentten sonra susar");
    }

    @Test
    void ardArdaSegmentlerAyniBorudanDevamEdiyor() throws IOException {
        // Gercek kullanim: tek ffmpeg borusundan sirayla segmentler aliniyor.
        byte[] kaynak = tsVerisi(30 * PACKET);
        InputStream boru = new ByteArrayInputStream(kaynak);

        long toplam = 0;
        for (int i = 0; i < 3; i++) {
            var segment = new SegmentStream(boru, 0);
            long n = tumunuOku(segment);
            assertEquals(0, n % PACKET, "her segment paket sınırında bitmeli");
            toplam += n;
        }
        // Kalani da al.
        toplam += tumunuOku(new SegmentStream(boru, 60_000));

        assertEquals(kaynak.length, toplam, "hiçbir bayt kaybolmamalı");
    }

    // ------------------------------------------------------------------

    /** Her paketi {@code 0x47} ile başlatan sahte TS verisi. */
    private static byte[] tsVerisi(int uzunluk) {
        byte[] b = new byte[uzunluk];
        for (int i = 0; i < uzunluk; i++) {
            b[i] = (i % PACKET == 0) ? (byte) 0x47 : (byte) (i % 251);
        }
        return b;
    }

    private static long tumunuOku(InputStream in) throws IOException {
        byte[] tampon = new byte[4096];
        long toplam = 0;
        int n;
        while ((n = in.read(tampon, 0, tampon.length)) >= 0) {
            toplam += n;
        }
        return toplam;
    }

    /** Kapatma çağrısını alttaki akışa geçirip geçirmediğimizi izler. */
    private static final class KapanmayiIzleyen extends InputStream {
        private final InputStream inner;
        boolean kapandi;

        KapanmayiIzleyen(InputStream inner) {
            this.inner = inner;
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
            kapandi = true;
            inner.close();
        }
    }
}
