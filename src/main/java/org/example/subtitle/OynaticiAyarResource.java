package org.example.subtitle;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

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
     * @param hlsGeride izleyicinin canlı kenardan kaç bölüt geriden izleyeceği.
     *                  Ayrıntı: {@link #ayarlar()}
     */
    public record OynaticiAyarlari(int hlsGeride) {
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
        return new OynaticiAyarlari(hlsGeride);
    }
}
