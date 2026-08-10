package org.example.storage;

import org.example.user.entity.AppUser;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Nesne depolamadaki klasör düzeni.
 *
 * <h2>Düzen</h2>
 * <pre>
 *   &lt;kullanıcı&gt;/&lt;kanal&gt;/&lt;id&gt;.mp4     klip ve kayıt
 *   &lt;kullanıcı&gt;/&lt;kanal&gt;/&lt;id&gt;.jpg     ekran görüntüsü
 *   &lt;kullanıcı&gt;/&lt;id&gt;/kaynak.&lt;uzantı&gt;  kütüphane videosu (kanalı yok)
 * </pre>
 *
 * <p><b>Neden kullanıcı en üstte:</b> içerik zaten kullanıcıya özel — herkes
 * yalnızca kendi klibini, kaydını ve videosunu görüyor. Kovaya konsoldan bakan
 * biri de aynı ayrımı görsün; kim ne üretmiş, klasöre bakınca anlaşılsın.
 * Kanala göre gruplamak, tek bir kullanıcının dosyalarını onlarca kanala
 * dağıtıyordu.
 *
 * <h2>Kullanıcı adı klasör adı olarak</h2>
 * Ad okunabilirlik için kullanılıyor, kimlik olarak değil. Türkçe harfler
 * sadeleştiriliyor ve S3 anahtarında sorun çıkaran karakterler ayıklanıyor.
 *
 * <p><b>Bilinen sınır:</b> Keycloak'ta kullanıcı adı değişirse yeni dosyalar
 * yeni klasöre gider; eskiler yerinde kalır. Anahtar veritabanında saklandığı
 * için hiçbiri kaybolmaz, yalnızca iki klasöre dağılır. Kimlik olarak UUID
 * kullanmak bunu çözerdi ama okunabilirliği tamamen yok ederdi — istenen tam
 * tersiydi.
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    /** Kullanıcının kök klasörü. */
    public static String userFolder(AppUser user) {
        String slug = slug(user.username);
        // Ad tamamen ayiklanirsa (orn. yalnizca noktalama) kimlige dusuyoruz:
        // anahtarin "//" ile baslamasi nesneyi erisilemez kilardi.
        return slug.isEmpty() ? user.keycloakId : slug;
    }

    /** Bir kanala ait dosya: {@code <kullanıcı>/<kanal>/<ad>}. */
    public static String channelFile(AppUser user, String channelPath, String fileName) {
        return userFolder(user) + "/" + slug(channelPath) + "/" + fileName;
    }

    /** Kanalı olmayan dosya (kütüphane videosu): {@code <kullanıcı>/<klasör>/<ad>}. */
    public static String userFile(AppUser user, String folder, String fileName) {
        return userFolder(user) + "/" + folder + "/" + fileName;
    }

    /**
     * Bir metni klasör adı olarak güvenli hale getirir.
     *
     * <p>Nesne depolama dışında da kullanılıyor: VAD bölütleri diske kanal
     * adıyla yazılıyor ve orada da aynı sadeleştirme gerekiyor.
     *
     * <p>Aksanlar ayrıştırılıp atılıyor; {@code ı} ve {@code ğ} bu yolla
     * çözülmediği için önce elle eşleniyor.
     */
    public static String slug(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(new Locale("tr", "TR"))
            .replace("ı", "i").replace("ğ", "g").replace("ş", "s")
            .replace("ç", "c").replace("ö", "o").replace("ü", "u");
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replaceAll("[^a-z0-9._-]+", "-");
        s = s.replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
        return s;
    }
}
