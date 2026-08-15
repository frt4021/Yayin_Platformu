package org.example.etkinlik;

import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.example.channel.entity.Channel;
import org.example.clip.entity.Clip;
import org.example.etkinlik.dto.EtkinlikDto;
import org.example.etkinlik.dto.EtkinlikSayfasiDto;
import org.example.etkinlik.entity.EtkinlikKaydi;
import org.example.radio.entity.Radio;
import org.example.user.entity.AppUser;
import org.example.video.entity.Video;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kullanıcı davranışı denetim izinin tek giriş noktası — yazma ({@link #kaydet})
 * ve okuma ({@link #ara}). Cross-cutting bir annotation/interceptor yerine
 * çağrı noktalarında tek satırlık açık çağrılar tercih edildi; bu kod tabanında
 * böyle bir kesişen-ilgi deseni zaten yok, ~10 çağrı noktası için yeni bir
 * soyutlama kurmaya değmez.
 */
@ApplicationScoped
public class EtkinlikService {

    /**
     * Yaygın yol: çağıranın elinde Keycloak id'si (JWT {@code sub}) var.
     * Kullanıcı bulunamazsa (örn. eşzamanlı silinme) sessizce {@code null}
     * kullaniciId ile kaydedilir — denetim izinin kendisi bir istek akışını
     * asla durdurmamalı.
     */
    @Transactional
    public void kaydet(EtkinlikTuru tur, String keycloakId, String hedefTuru, UUID hedefId,
                        Map<String, Object> detay) {
        AppUser kullanici = keycloakId == null ? null : AppUser.byKeycloakId(keycloakId);
        yaz(tur, kullanici == null ? null : kullanici.id, kullanici == null ? null : kullanici.username,
            hedefTuru, hedefId, detay);
    }

    /**
     * Giriş denemesi (başarılı ya da başarısız) için özel yol: bu noktada
     * elimizde henüz keycloakId yok — {@code AuthService.login} sadece
     * kullanıcı adını parametre olarak alıyor, Keycloak'a sorup token/hata
     * dönmesini bekliyor. Yerel {@code users} tablosunda aynı adla biri varsa
     * kullaniciId doldurulur; yoksa (var olmayan kullanıcı adı, yalnızca
     * başarısız durumda mümkün) yalnızca ham ad saklanır.
     */
    @Transactional
    public void kaydetGirisDenemesi(EtkinlikTuru tur, String kullaniciAdi) {
        AppUser kullanici = kullaniciAdi == null ? null
            : AppUser.<AppUser>find("username", kullaniciAdi).firstResult();
        yaz(tur, kullanici == null ? null : kullanici.id, kullaniciAdi, null, null, Map.of());
    }

    private void yaz(EtkinlikTuru tur, UUID kullaniciId, String kullaniciAdi,
                      String hedefTuru, UUID hedefId, Map<String, Object> detay) {
        EtkinlikKaydi kayit = new EtkinlikKaydi();
        kayit.tur = tur;
        kayit.kullaniciId = kullaniciId;
        kayit.kullaniciAdi = kullaniciAdi;
        kayit.hedefTuru = hedefTuru;
        kayit.hedefId = hedefId;
        kayit.detay = detay == null ? Map.of() : detay;
        kayit.persist();
    }

    public EtkinlikSayfasiDto ara(EtkinlikTuru tur, UUID kullaniciId, String kullaniciAdi,
                                   Instant baslangic, Instant bitis, int first, int max) {
        StringBuilder sorgu = new StringBuilder("1=1");
        Parameters params = new Parameters();

        if (tur != null) {
            sorgu.append(" and tur = :tur");
            params.and("tur", tur);
        }
        if (kullaniciId != null) {
            sorgu.append(" and kullaniciId = :kullaniciId");
            params.and("kullaniciId", kullaniciId);
        }
        if (kullaniciAdi != null && !kullaniciAdi.isBlank()) {
            sorgu.append(" and lower(kullaniciAdi) like :kullaniciAdi");
            params.and("kullaniciAdi", "%" + kullaniciAdi.trim().toLowerCase() + "%");
        }
        if (baslangic != null) {
            sorgu.append(" and olusturmaZamani >= :baslangic");
            params.and("baslangic", baslangic);
        }
        if (bitis != null) {
            sorgu.append(" and olusturmaZamani <= :bitis");
            params.and("bitis", bitis);
        }

        var sorguNesnesi = EtkinlikKaydi.find(sorgu + " order by olusturmaZamani desc", params);
        long total = sorguNesnesi.count();
        List<EtkinlikDto> items = sorguNesnesi.<EtkinlikKaydi>range(first, first + max - 1)
            .list()
            .stream()
            .map(EtkinlikService::toDto)
            .toList();

        return new EtkinlikSayfasiDto(items, total, first, max);
    }

    private static EtkinlikDto toDto(EtkinlikKaydi k) {
        return new EtkinlikDto(k.id, k.kullaniciId, k.kullaniciAdi, k.tur, k.hedefTuru, k.hedefId,
            hedefAdi(k.hedefTuru, k.hedefId), k.detay, k.olusturmaZamani);
    }

    /**
     * {@code hedefId}'yi insan-okunur ada çeviriyor — liste ekranında (Etkinlikler
     * sayfası, kullanıcı aktivite dialog'u) yalnızca UUID görünüyordu, hangi
     * kanalın/radyonun izlenmeye/dinlenmeye başlandığı belli değildi.
     */
    private static String hedefAdi(String hedefTuru, UUID hedefId) {
        if (hedefTuru == null || hedefId == null) return null;
        return switch (hedefTuru) {
            case "kanal" -> {
                Channel kanal = Channel.findById(hedefId);
                yield kanal == null ? "Silinmiş kanal" : kanal.name;
            }
            case "radyo" -> {
                Radio radyo = Radio.findById(hedefId);
                yield radyo == null ? "Silinmiş radyo" : radyo.name;
            }
            case "video" -> {
                Video video = Video.findById(hedefId);
                yield video == null ? "Silinmiş video" : video.title;
            }
            case "klip" -> {
                Clip klip = Clip.findById(hedefId);
                yield klip == null ? "Silinmiş klip" : klip.channelName;
            }
            case "kullanici" -> {
                AppUser kullanici = AppUser.findById(hedefId);
                yield kullanici == null ? "Silinmiş kullanıcı" : kullanici.username;
            }
            default -> null;
        };
    }
}
