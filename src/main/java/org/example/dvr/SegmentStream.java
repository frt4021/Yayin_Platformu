package org.example.dvr;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sürekli bir TS akışından <b>tek segmenti</b> okutan görünüm.
 *
 * <h2>Ne işe yarıyor</h2>
 * ffmpeg kesintisiz bir MPEG-TS akışı üretiyor; MinIO SDK ise "başı ve sonu
 * olan bir {@link InputStream}" bekliyor. Bu sınıf araya girip akışı segment
 * süresi dolduğunda <b>bitmiş gibi</b> gösteriyor: SDK dosya sonu görüp
 * yüklemeyi tamamlıyor, alttaki boru ise açık kalıyor ve bir sonraki segment
 * kaldığı yerden devam ediyor.
 *
 * <h2>Neden 188 baytlık hizalama</h2>
 * MPEG-TS sabit 188 baytlık paketlerden oluşuyor ve her paket {@code 0x47}
 * ile başlıyor. Kesim <b>paket sınırında</b> olursa parçalar tek başına
 * ayrıştırılabiliyor ve arka arkaya eklenince tam akış geri geliyor
 * (ölçüldü: parçalar birleştirilince bayt bayt aynı dosya, ortadan alınan
 * üç parçadan 268 kare çözüldü).
 *
 * <p>Paketin ortasından kesilseydi hem o segmentin sonu hem sonrakinin başı
 * yarım paketle başlar, çözücü senkronu bulana kadar veri atardı.
 *
 * <h2>Neden fMP4 değil</h2>
 * fMP4 rastgele kesilemez: her nesneye {@code ftyp}+{@code moov} başlığının
 * yeniden yazılması ve {@code moof}/{@code mdat} kutularının ayrıştırılması
 * gerekirdi. TS'te böyle bir başlık yok, biçim kendi kendini senkronluyor --
 * HLS'in TS kullanmasının sebebi de bu.
 *
 * <h2>Süre neden duvar saatiyle ölçülüyor</h2>
 * Bayt sayısından süre çıkarılamaz (bit hızı değişken) ve TS'teki PCR alanını
 * ayrıştırmak ayrı bir iş. ffmpeg RTSP'yi <b>gerçek zamanlı</b> okuduğu için
 * duvar saati yeterince yakın kalıyor: sapma boru tamponu kadar, 3 Mbps'te
 * saniyenin altında. Geriye sarmada saniye altı doğruluk aranmıyor.
 */
final class SegmentStream extends InputStream {

    /** MPEG-TS paket boyutu. Sabit ve biçimin tanımı gereği değişmez. */
    static final int TS_PACKET = 188;

    private final InputStream source;
    private final long limitMillis;

    /** Segmentin başladığı an — ilk bayt okunduğunda konuyor. */
    private long startedAt;
    private boolean started;

    /** Bu segmentte okunan bayt; paket hizasını takip etmek için. */
    private long read;

    /** Süre doldu ve paket sınırına gelindi: artık dosya sonu veriyoruz. */
    private boolean finished;

    /**
     * Kaynak akış tükendi (ffmpeg öldü).
     *
     * <p>Ayrı bayrak <b>gerekli</b>: "segment bitti" ile "akış bitti" farklı
     * şeyler ve ikisi de {@code read} = -1 ile görünüyor. Bayt sayısının
     * paket sınırında olup olmamasına bakarak ayırmak yanlış olurdu --
     * kaynak tam sınırda da bitebilir.
     */
    private boolean sourceEnded;

    SegmentStream(InputStream source, long limitMillis) {
        this.source = source;
        this.limitMillis = limitMillis;
    }

    @Override
    public int read() throws IOException {
        byte[] tek = new byte[1];
        int n = read(tek, 0, 1);
        return n < 0 ? -1 : tek[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (finished) {
            return -1;
        }
        if (!started) {
            started = true;
            startedAt = System.currentTimeMillis();
        }

        // Paket sınırına kadar okunacak miktar. Süre dolduysa yalnızca içinde
        // bulunduğumuz paketi tamamlıyoruz -- yarım paket bırakmak, hem bu
        // segmentin sonunu hem sonrakinin başını bozardı.
        int kalanPakette = (int) (TS_PACKET - (read % TS_PACKET));
        boolean sureDoldu = System.currentTimeMillis() - startedAt >= limitMillis;
        if (sureDoldu && kalanPakette == TS_PACKET) {
            // Tam paket sınırındayız ve süre dolmuş: segment burada biter.
            finished = true;
            return -1;
        }

        int istenen = sureDoldu ? Math.min(len, kalanPakette) : len;
        int n = source.read(b, off, istenen);
        if (n < 0) {
            // Kaynak bitti (ffmpeg öldü). Segment eldeki kadarıyla kapanıyor;
            // çağıran süreç ölümünü fark edip yeniden başlatıyor.
            finished = true;
            sourceEnded = true;
            return -1;
        }
        read += n;
        return n;
    }

    /**
     * Alttaki boru <b>kapatılmıyor</b>.
     *
     * <p>MinIO SDK yüklemeyi bitirince verilen akışı kapatıyor. Burada
     * gerçekten kapatılsaydı ffmpeg'in stdout'u ilk segmentten sonra kapanır
     * ve kayıt tek segmentle biterdi.
     */
    @Override
    public void close() {
        finished = true;
    }

    /** Bu segmentte okunan bayt sayısı. */
    long bytesRead() {
        return read;
    }

    /** Kaynak akış tükendi mi — ffmpeg öldüyse yeniden başlatmak gerekiyor. */
    boolean sourceEnded() {
        return sourceEnded;
    }

    /** Segmentin başladığı an; hiç bayt okunmadıysa {@code 0}. */
    long startedAt() {
        return startedAt;
    }
}
