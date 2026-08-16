package org.example.sistemlog;

import org.example.sistemlog.dto.SistemLogDto;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ham log satırlarını Türkçe, kullanıcı dostu mesajlara çeviren saf kural
 * motoru — DI yok, test edilebilir statik metotlar.
 *
 * <p><b>Yalnızca bilinen bir kurala uyan (ya da genel bir hata/uyarı
 * sinyali taşıyan) satırlar gösterilir</b> — rutin bilgi gürültüsü
 * (health-check tekrarları, her istek logu vb.) bilerek süzülüyor. Aksi
 * halde bu ekran "süslü bir {@code docker logs}" olurdu, kullanıcının
 * istediği "anlamlı mesaj" değil.
 *
 * <p>Kural listesi bu oturumda gerçekten karşılaşılan örüntülerle
 * sınırlı — kapsamlı değil, ama genişletilebilir: yeni bir örüntü için
 * {@link #KURALLAR} listesine tek satır eklemek yeterli.
 */
public final class SistemLogYorumlayici {

    private SistemLogYorumlayici() {
    }

    public enum Seviye {
        BASARI, BILGI, UYARI, HATA
    }

    private record Kural(String servisDeseni, Pattern desen, Seviye seviye, String sablon) {
    }

    /**
     * Sıralı liste — ilk eşleşen kazanır. {@code servisDeseni}, Promtail'in
     * verdiği {@code servis} etiketinde ({@code String.contains}) aranıyor
     * — konteyner adları tam eşleşmeyebilir (örn. `video-worker` ile
     * başlayan farklı adlandırmalar).
     */
    private static final List<Kural> KURALLAR = List.of(
        new Kural("triton",
            Pattern.compile("Failed to process the request\\(s\\) for model '(\\w+)'.*CUDA failed with error out of memory"),
            Seviye.HATA, "{1} modeli GPU belleği dolduğu için isteği işleyemedi (VRAM yetersiz)."),
        new Kural("triton",
            Pattern.compile("Failed to process the request\\(s\\) for model '(\\w+)'.*invalid device ordinal"),
            Seviye.HATA, "{1} modelinde GPU bağlantı hatası (genelde bellek dolduktan sonra ortaya çıkar)."),
        new Kural("triton",
            Pattern.compile("Failed to process the request\\(s\\) for model '(\\w+)'"),
            Seviye.HATA, "{1} modeli isteği işleyemedi."),

        new Kural("video-worker",
            Pattern.compile("ALTYAZI (\\S+) \\[.*?\\] (\\S+) →"),
            Seviye.BASARI, "{1} için altyazı üretildi ({2} dilinden çevrildi)."),
        new Kural("video-worker",
            Pattern.compile("Bölüt yazılamadı"),
            Seviye.UYARI, "Bir ses bölütü diske yazılamadı (altyazı üretimini etkilemez)."),
        new Kural("video-worker",
            Pattern.compile("[Bb]ölüt.*bütçeyi.*aşmış"),
            Seviye.UYARI, "Bir ses bölütü bütçeyi aştığı için işlenmeden atlandı."),
        new Kural("video-worker",
            Pattern.compile("[Kk]anal kuyruğu dolu"),
            Seviye.UYARI, "Kanal kuyruğu doldu, en eski bölüt atıldı (GPU yetişemiyor olabilir)."),
        new Kural("video-worker",
            Pattern.compile("Video hazır:"),
            Seviye.BASARI, "Bir video işlendi ve izlenebilir hale geldi."),
        new Kural("video-worker",
            Pattern.compile("Video işlenemedi"),
            Seviye.HATA, "Bir video işlenirken kalıcı hata oluştu."),
        new Kural("video-worker",
            Pattern.compile("Video altyazısı hazır:"),
            Seviye.BASARI, "Bir video için altyazı üretildi."),
        new Kural("video-worker",
            Pattern.compile("Video altyazısı üretilemedi"),
            Seviye.HATA, "Bir video için altyazı üretilemedi (videonun kendisi etkilenmez)."),
        new Kural("video-worker",
            Pattern.compile("Klip hazır:"),
            Seviye.BASARI, "Bir klip üretildi."),
        new Kural("video-worker",
            Pattern.compile("Klip üretilemedi"),
            Seviye.HATA, "Bir klip üretilemedi."),

        new Kural("backend",
            Pattern.compile("No handler waiting for message: \\[psubscribe"),
            Seviye.BILGI, "Altyazı aboneliği henüz açılmadı (ilk izleyiciyle otomatik açılır, normal)."),
        new Kural("backend",
            Pattern.compile("Giriş başarılı: (\\S+)"),
            Seviye.BASARI, "{1} giriş yaptı."),
        new Kural("backend",
            Pattern.compile("Giriş başarısız"),
            Seviye.UYARI, "Başarısız bir giriş denemesi oldu."),

        new Kural("mediamtx",
            Pattern.compile("is not configured"),
            Seviye.HATA, "MediaMTX'te tanımsız bir path'e istek geldi."),

        // Bir konteyner yeniden oluşturulunca (rebuild/restart) Promtail'in
        // Docker keşfi en fazla refresh_interval (15s) kadar eski ID'yi
        // listesinde tutup ona ulaşmaya çalışıyor -- kendi kendine düzeliyor,
        // bilinen ve zararsız. HATA'ya düşmesin diye erken yakalanıyor.
        new Kural("promtail",
            Pattern.compile("could not inspect container info.*No such container"),
            Seviye.BILGI, "Bir konteyner yeniden oluşturulurken geçici bir loglama uyarısı (kendiliğinden düzelir, zararsız).")
    );

    private static final Pattern JSON_LEVEL = Pattern.compile("\"level\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern JSON_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * @return eşleşen bir kural ya da genel bir hata/uyarı sinyali varsa
     *         yorumlanmış DTO; aksi halde {@code null} — çağıran bu
     *         durumda satırı GÖSTERMEMELİ (rutin gürültü).
     */
    public static SistemLogDto yorumla(String servis, Instant zaman, String hamMesaj) {
        for (Kural k : KURALLAR) {
            if (!servis.contains(k.servisDeseni())) {
                continue;
            }
            Matcher m = k.desen().matcher(hamMesaj);
            if (m.find()) {
                return new SistemLogDto(zaman, servis, k.seviye().name(), sablonDoldur(k.sablon(), m), hamMesaj);
            }
        }

        // Yapisal (JSON) log -- kendi level alanina bak (Quarkus JSON
        // formatinda backend/video-worker loglari boyle geliyor).
        Matcher seviyeEslesme = JSON_LEVEL.matcher(hamMesaj);
        if (seviyeEslesme.find()) {
            String jsonSeviye = seviyeEslesme.group(1);
            if ("ERROR".equals(jsonSeviye) || "WARN".equals(jsonSeviye)) {
                String mesaj = jsonMesajCikar(hamMesaj);
                return new SistemLogDto(zaman, servis,
                    "ERROR".equals(jsonSeviye) ? Seviye.HATA.name() : Seviye.UYARI.name(),
                    mesaj, hamMesaj);
            }
            return null; // INFO/DEBUG -- rutin, gosterme
        }

        // JSON degil (mediamtx, postgres, redis, keycloak, minio, nginx) --
        // yalnizca genel hata/uyari sinyali tasiyan satirlar gosterilir.
        String kucukHarf = hamMesaj.toLowerCase();
        if (kucukHarf.contains("error") || kucukHarf.contains("fatal")
            || kucukHarf.contains("exception") || kucukHarf.contains("panic")) {
            return new SistemLogDto(zaman, servis, Seviye.HATA.name(), hamMesaj, hamMesaj);
        }
        return null;
    }

    private static String sablonDoldur(String sablon, Matcher m) {
        String sonuc = sablon;
        for (int i = 1; i <= m.groupCount(); i++) {
            String deger = m.group(i);
            sonuc = sonuc.replace("{" + i + "}", deger == null ? "?" : deger);
        }
        return sonuc;
    }

    private static String jsonMesajCikar(String hamMesaj) {
        Matcher m = JSON_MESSAGE.matcher(hamMesaj);
        return m.find() ? m.group(1) : hamMesaj;
    }
}
