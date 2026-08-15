package org.example.etkinlik.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param kullaniciAdi Yerel {@code users} tablosunda kaydı yoksa (hiç giriş
 *                      yapmamış) {@code null} — çağıran zaten bildiği adı gösterir.
 */
public record KullaniciAktiviteDto(
    String kullaniciAdi,
    long videoYuklemeSayisi,
    long klipSayisi,
    long toplamIzlemeSuresiMs,
    List<HedefIzlemeOzetiDto> izlenenKanallar,
    List<HedefIzlemeOzetiDto> dinlenenRadyolar,
    /** Klibin hedefi kanal değil klibin kendisi olduğu için isim bazlı — bkz. {@link AdSayiDto}. */
    List<AdSayiDto> klipAlinanKanallar,
    List<TopEtiketDto> manuelKayitAlinanKanallar,
    List<TopEtiketDto> geriSarilanKanallar,
    /** Yukarıdaki kategorilerin dışında kalan HER ŞEY dahil — türe göre süzülmemiş, en yeni önce. */
    List<EtkinlikDto> sonEtkinlikler,
    Instant sonGiris
) {
}
