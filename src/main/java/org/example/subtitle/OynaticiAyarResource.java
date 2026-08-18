package org.example.subtitle;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.util.Arrays;
import java.util.List;

/**
 * Tarayıcıdaki oynatıcının {@code .env}'den gelen ayarları.
 *
 * <h2>Neden backend'den geliyor</h2>
 * Önyüz statik bir Vite derlemesi; içine gömülen bir değer ancak
 * {@code docker compose build frontend} ile değişir. Bu ayarın <b>ölçerek</b>
 * bulunması gerekiyor ve her denemede imaj kurmak makul değil. Backend
 * {@code .env}'i zaten okuyor, tek yeni şey bu uç.
 *
 * <p><b>Kimlik istemiyor.</b> İçinde gizli hiçbir şey yok — yalnızca bir
 * oynatıcı ayarı — ve önyüz bunu oturum açılmadan önce, uygulama
 * kurulurken bir kez okuyor.
 */
@Path("/api/ayarlar/oynatici")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class OynaticiAyarResource {

    @ConfigProperty(name = "altyazi.hls-geride")
    int hlsGeride;

    /**
     * {@code stt.target-langs} ile AYNI kaynak (VadService'in çeviri açtığı
     * diller) — önyüzdeki dil seçici burada sabit kodlanmasın diye tek
     * doğru kaynaktan (.env) besleniyor.
     */
    @ConfigProperty(name = "stt.target-langs")
    String hedefDillerHam;

    /**
     * Altyapı hazır (DVR önizlemesi de canlı ile aynı mutlak zaman damgası
     * eşlemesini kullanıyor) ama üretimde hiç denenmedi — bu yüzden ayrı bir
     * anahtar, {@code stt.target-langs}'a bağlı değil. Kapalıyken önyüz DVR
     * sayfasında dil seçiciyi/bindirmeyi hiç göstermiyor.
     */
    @ConfigProperty(name = "dvr.altyazi-acik")
    boolean dvrAltyaziAcik;

    /**
     * @param hlsGeride      izleyicinin canlı kenardan kaç bölüt geriden izleyeceği.
     *                       Ayrıntı: {@link #ayarlar()}
     * @param altyaziDilleri altyazının üretildiği hedef diller (ISO kodu,
     *                       ör. {@code ["tr","de","ru"]}) — pivot {@code en}
     *                       ve {@code kapali} seçeneği bu listede DEĞİL,
     *                       önyüz onları sabit ekliyor.
     * @param dvrAltyaziAcik DVR önizlemesinde altyazı gösterilsin mi
     *                       ({@code DVR_ALTYAZI_ACIK}, varsayılan kapalı).
     */
    public record OynaticiAyarlari(int hlsGeride, List<String> altyaziDilleri, boolean dvrAltyaziAcik) {
    }

    /**
     * <h2>{@code hlsGeride} tam olarak ne</h2>
     * hls.js oynatma kafasını canlı kenarın
     * {@code hlsGeride × EXT-X-TARGETDURATION} kadar gerisine koyuyor. Ölçülen
     * yayında hedef süre <b>2 saniye</b>, yani 3 → 6 saniye geride.
     *
     * <p><b>Bu sayı altyazının bütçesidir.</b> Arayüz altyazıyı
     * {@code baslangic <= playingDate() < bitis} kuralıyla eşliyor: altyazı,
     * izleyici o saniyeye varmadan üretilmiş olmalı. Geç kalan altyazı geç
     * değil <b>hiç</b> gösterilmiyor.
     *
     * <p>Ölçülen durum: üretim gecikmesi p50 ~13 sn, p95 ~23 sn; bütçe 6 sn.
     * Hiçbir altyazı yetişmiyordu.
     *
     * <p><b>Kaba kural: bütçe ≥ p95 gecikme.</b> Gecikme
     * {@code ALTYAZI KAPSAMA} log satırından okunuyor.
     *
     * <p><b>Değeri silmek/sıfırlamak tehlikeli.</b> Sunucu oynatma listesinde
     * {@code PART-HOLD-BACK=0.5} ilan ediyor ve {@code lowLatencyMode} açık;
     * hls.js kullanıcı ayarı verilmezse <b>onu</b> kullanıyor ve bütçe yarım
     * saniyeye düşüyor. O durumda altyazı GPU'yla bile yetişemez.
     */
    @GET
    @Operation(summary = "Oynatıcının .env'den gelen ayarları")
    public OynaticiAyarlari ayarlar() {
        List<String> diller = Arrays.stream(hedefDillerHam.split(","))
            .map(String::strip)
            .filter(dil -> !dil.isEmpty())
            .toList();
        return new OynaticiAyarlari(hlsGeride, diller, dvrAltyaziAcik);
    }
}
