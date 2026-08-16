package org.example.etkinlik;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.VAD.TritonClient;
import org.keycloak.admin.client.Keycloak;
import org.example.channel.MediaMtxService;
import org.example.channel.dto.MediaMtxPathList;
import org.example.channel.entity.Channel;
import org.example.clip.ScheduledStatus;
import org.example.clip.entity.ActiveRecording;
import org.example.clip.entity.Clip;
import org.example.clip.entity.ScheduledRecording;
import org.example.dvr.entity.DvrSegment;
import org.example.etkinlik.entity.EtkinlikKaydi;
import org.example.etkinlik.dto.AdSayiDto;
import org.example.etkinlik.dto.BilesenSaglikDurumu;
import org.example.etkinlik.dto.CanliDurumDto;
import org.example.etkinlik.dto.DepolamaDto;
import org.example.etkinlik.dto.EtkinlikDto;
import org.example.etkinlik.dto.GenelAktiviteDto;
import org.example.etkinlik.dto.HedefIzlemeOzetiDto;
import org.example.etkinlik.dto.IcerikPerformansiDto;
import org.example.etkinlik.dto.KullaniciAktiviteDto;
import org.example.etkinlik.dto.KullaniciKullanimDto;
import org.example.etkinlik.dto.ServisMetrikleriDto;
import org.example.etkinlik.dto.SistemSagligiOzetDto;
import org.example.etkinlik.dto.TeknikDto;
import org.example.etkinlik.dto.TopEtiketDto;
import org.example.etkinlik.dto.VideoAnalitikOzetDto;
import org.example.etkinlik.dto.VideoIsiHaritasiDto;
import org.example.radio.entity.Radio;
import org.example.storage.QuotaService;
import org.example.user.entity.AppUser;
import org.example.video.VideoStatus;
import org.example.video.entity.Video;
import org.example.viewer.ViewerPresence;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Admin panelin "Faz 1" analitik dashboard'u için toplama sorguları.
 *
 * <p>Kural: buradaki hiçbir alan sahte/tahmini bir sayı üretmiyor — henüz
 * enstrümantasyonu olmayan veriler (bant genişliği, oynatma hatası oranı)
 * {@code null} dönüyor, frontend bunu "ölçülmüyor" olarak gösteriyor
 * (bkz. {@code docs/analitik-dashboard-plani-faz1.md} — Faz 2).
 */
@ApplicationScoped
public class AnalitikService {

    private static final Logger LOG = Logger.getLogger(AnalitikService.class);
    private static final int TOP_N = 10;
    private static final int SON_ETKINLIK_SAYISI = 10;

    @Inject
    ViewerPresence viewerPresence;

    @Inject
    QuotaService quotaService;

    @Inject
    EtkinlikService etkinlikService;

    @Inject
    MediaMtxService mediaMtxService;

    @Inject
    TritonClient tritonClient;

    @Inject
    MinioClient minioClient;

    @Inject
    Keycloak keycloak;

    @Inject
    RedisDataSource redis;

    @Inject
    PrometheusClient prometheusClient;

    @ConfigProperty(name = "videos.bucket")
    String videosBucket;

    @ConfigProperty(name = "keycloak.realm")
    String keycloakRealm;

    /**
     * MediaMTX'in {@code bytesReceived} alanı KÜMÜLATİF (path açıldığından beri
     * toplam) — anlık Mbps için iki örnekleme arasındaki FARK gerekiyor. İlk
     * çağrıda henüz bir önceki örnek yok, {@code null} dönüyor (uydurma bir
     * ilk değer yerine).
     */
    private record TrafikOrnegi(Instant zaman, long toplamBayt) {
    }

    private final AtomicReference<TrafikOrnegi> sonTrafikOrnegi = new AtomicReference<>();

    public CanliDurumDto canliDurum() {
        long izleyici = viewerPresence.toplamSayisi("kanal");
        long dinleyici = viewerPresence.toplamSayisi("radyo");
        long aktifKayit = ActiveRecording.count();
        return new CanliDurumDto(izleyici, dinleyici, aktifKayit, anlikTrafikMbps());
    }

    private Long anlikTrafikMbps() {
        try {
            Map<String, MediaMtxPathList.Item> durumlar = mediaMtxService.pathStates();
            long toplamBayt = durumlar.values().stream().mapToLong(MediaMtxPathList.Item::bytesReceived).sum();
            TrafikOrnegi onceki = sonTrafikOrnegi.getAndSet(new TrafikOrnegi(Instant.now(), toplamBayt));
            if (onceki == null) {
                return null; // ilk olcum, henuz karsilastirilacak bir onceki yok
            }
            double saniyeFarki = Duration.between(onceki.zaman(), Instant.now()).toMillis() / 1000.0;
            long baytFarki = toplamBayt - onceki.toplamBayt();
            // Negatif fark: MediaMTX yeniden baslamis, sayaclar sifirlanmis olabilir.
            if (saniyeFarki <= 0 || baytFarki < 0) {
                return null;
            }
            return Math.round((baytFarki * 8.0 / 1_000_000.0) / saniyeFarki);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Anlık trafik hesaplanamadı");
            return null;
        }
    }

    public IcerikPerformansiDto icerikPerformansi() {
        return new IcerikPerformansiDto(
            topHedef(EtkinlikTuru.IZLEME_BASLADI, AnalitikService::kanalAdi),
            topHedef(EtkinlikTuru.DINLEME_BASLADI, AnalitikService::radyoAdi),
            topHedef(EtkinlikTuru.KAYIT_BASLADI, AnalitikService::kanalAdi));
    }

    public DepolamaDto depolama() {
        List<KullaniciKullanimDto> enYuksek = AppUser.<AppUser>listAll().stream()
            .map(u -> {
                QuotaService.Usage usage = quotaService.usageOf(u.keycloakId);
                return new KullaniciKullanimDto(u.username, usage.totalBytes(), usage.percentUsed());
            })
            .sorted(Comparator.comparingLong(KullaniciKullanimDto::toplamBayt).reversed())
            .limit(TOP_N)
            .toList();

        Instant simdi = Instant.now();
        long gelecek24Saat = ScheduledRecording.count(
            "durum = ?1 and baslangic between ?2 and ?3",
            ScheduledStatus.BEKLIYOR, simdi, simdi.plus(Duration.ofHours(24)));

        Long toplamDvrBayt = DvrSegment.getEntityManager()
            .createQuery("select coalesce(sum(d.boyutBayt), 0) from DvrSegment d", Long.class)
            .getSingleResult();

        return new DepolamaDto(enYuksek, gelecek24Saat, toplamDvrBayt == null ? 0 : toplamDvrBayt);
    }

    public TeknikDto teknik() {
        long basarisizPlanli = ScheduledRecording.count("durum = ?1", ScheduledStatus.BASARISIZ);
        long videoHatasi = Video.count("status = ?1", VideoStatus.HATA);
        return new TeknikDto(basarisizPlanli, videoHatasi, yayinKopmaOrani());
    }

    /** Toplam oynatma hatasi+takilma olayi / toplam izleme+dinleme baslangici, yuzde. */
    private Double yayinKopmaOrani() {
        long baslangicSayisi = EtkinlikKaydi.count("tur = ?1 or tur = ?2",
            EtkinlikTuru.IZLEME_BASLADI, EtkinlikTuru.DINLEME_BASLADI);
        if (baslangicSayisi == 0) {
            return null; // Henuz hic izleme/dinleme baslangici yoksa oran anlamsiz.
        }
        Number olayToplami = (Number) EtkinlikKaydi.getEntityManager()
            .createNativeQuery("select coalesce(sum((detay->>'sayi')::int), 0) from etkinlik_kayitlari "
                + "where tur in ('OYNATMA_HATASI', 'OYNATMA_TAKILMA')")
            .getSingleResult();
        return 100.0 * olayToplami.longValue() / baslangicSayisi;
    }

    public List<VideoAnalitikOzetDto> videoListesi() {
        @SuppressWarnings("unchecked")
        List<Object[]> satirlar = EtkinlikKaydi.getEntityManager()
            .createNativeQuery(
                "select v.id, v.title, count(e.id), "
                    + "  coalesce(avg(case when (e.detay->>'tamamlandi')::boolean then 1.0 else 0.0 end), 0) * 100 "
                    + "from videos v "
                    + "join etkinlik_kayitlari e on e.hedef_id = v.id and e.tur = 'VIDEO_IZLEME_BITTI' "
                    + "group by v.id, v.title "
                    + "order by count(e.id) desc "
                    + "limit 200")
            .getResultList();
        return satirlar.stream()
            .map(r -> new VideoAnalitikOzetDto(
                (UUID) r[0], (String) r[1], ((Number) r[2]).longValue(), ((Number) r[3]).doubleValue()))
            .toList();
    }

    public VideoIsiHaritasiDto videoIsiHaritasi(UUID videoId) {
        Object[] ozet = (Object[]) EtkinlikKaydi.getEntityManager()
            .createNativeQuery(
                "select count(*), coalesce(avg(case when (detay->>'tamamlandi')::boolean then 1.0 else 0.0 end), 0) * 100 "
                    + "from etkinlik_kayitlari where tur = 'VIDEO_IZLEME_BITTI' and hedef_id = :id")
            .setParameter("id", videoId)
            .getSingleResult();
        long oturumSayisi = ((Number) ozet[0]).longValue();
        double tamamlanmaOrani = ((Number) ozet[1]).doubleValue();

        @SuppressWarnings("unchecked")
        List<Object[]> dilimSatirlari = EtkinlikKaydi.getEntityManager()
            .createNativeQuery(
                "select (elem)::int as dilim, count(*) "
                    + "from etkinlik_kayitlari e "
                    + "cross join lateral jsonb_array_elements_text(e.detay -> 'ziyaretEdilenDilimler') as elem "
                    + "where e.tur = 'VIDEO_IZLEME_BITTI' and e.hedef_id = :id "
                    + "group by dilim")
            .setParameter("id", videoId)
            .getResultList();

        long[] dilimSayaclari = new long[10];
        for (Object[] satir : dilimSatirlari) {
            int i = ((Number) satir[0]).intValue();
            if (i >= 0 && i < 10) {
                dilimSayaclari[i] = ((Number) satir[1]).longValue();
            }
        }
        Video video = Video.findById(videoId);
        String baslik = video == null ? "Silinmiş video" : video.title;
        return new VideoIsiHaritasiDto(videoId, baslik, oturumSayisi, tamamlanmaOrani, dilimSayaclari);
    }

    public GenelAktiviteDto genel() {
        Instant simdi = Instant.now();
        long dau = distinctKullanici(EtkinlikTuru.GIRIS, simdi.minus(Duration.ofDays(1)));
        long mau = distinctKullanici(EtkinlikTuru.GIRIS, simdi.minus(Duration.ofDays(30)));

        Map<Integer, Long> saatBazli = saatDagilimi(EtkinlikTuru.GIRIS);

        Instant esik24s = simdi.minus(Duration.ofDays(1));
        long izlemeBaslangici24s = EtkinlikKaydi.count(
            "tur = ?1 and olusturmaZamani >= ?2", EtkinlikTuru.IZLEME_BASLADI, esik24s);
        long aktifKullanici24s = distinctKullanici(EtkinlikTuru.IZLEME_BASLADI, esik24s);
        double ortalama = aktifKullanici24s == 0 ? 0.0 : (double) izlemeBaslangici24s / aktifKullanici24s;

        return new GenelAktiviteDto(dau, mau, saatBazli, ortalama);
    }

    /**
     * Tek bir kullanıcının aktivite özeti — {@code AdminUsersPage}'de bir
     * kullanıcı adına tıklanınca açılan detay için.
     *
     * @param keycloakId {@code AdminUserResource}'un beklediğiyle aynı —
     *                   Keycloak kullanıcı id'si. Yerel {@code users}
     *                   satırı yoksa (hiç giriş yapmamış) sıfır/boş bir
     *                   özet döner, hata fırlatmaz.
     */
    public KullaniciAktiviteDto kullaniciAktivitesi(String keycloakId) {
        AppUser user = AppUser.byKeycloakId(keycloakId);
        if (user == null) {
            return new KullaniciAktiviteDto(null, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        }

        long videoYukleme = Video.count("uploadedBy.keycloakId = ?1", keycloakId);
        long klip = Clip.count("requestedBy.keycloakId = ?1", keycloakId);

        // NOT: sum(bigint) Postgres'te numeric doner (tasma korumasi icin),
        // bigint DEGIL -- JDBC bunu BigDecimal olarak veriyor, dogrudan
        // (Long) cast'i ClassCastException atardi. Number araliginda kalip
        // longValue() ile cikartiliyor.
        Number toplamSureMs = (Number) EtkinlikKaydi.getEntityManager()
            .createNativeQuery("select coalesce(sum((detay->>'sureMs')::bigint), 0) "
                + "from etkinlik_kayitlari where kullanici_id = :id and tur in ('IZLEME_BITTI', 'DINLEME_BITTI')")
            .setParameter("id", user.id)
            .getSingleResult();

        List<HedefIzlemeOzetiDto> kanallar = hedefIzlemeOzeti("IZLEME_BITTI", user.id, AnalitikService::kanalAdi);
        List<HedefIzlemeOzetiDto> radyolar = hedefIzlemeOzeti("DINLEME_BITTI", user.id, AnalitikService::radyoAdi);

        // KLIP_OLUSTURULDU'nun hedefi kanal DEĞİL klibin kendisi (hedef_id =
        // clip.id) -- kanal adı yalnızca detay->>'kanal' olarak ham metin
        // duruyor, bu yüzden hedef_id ile değil isimle gruplanıyor.
        List<AdSayiDto> klipKanallari = klipAlinanKanallar(user.id);

        List<TopEtiketDto> kayitKanallari =
            topHedefKullaniciSayi(EtkinlikTuru.KAYIT_BASLADI, user.id, AnalitikService::kanalAdi);
        List<TopEtiketDto> geriSarilanKanallar =
            topHedefKullaniciSayi(EtkinlikTuru.DVR_GERI_SARILDI, user.id, AnalitikService::kanalAdi);

        // Yukarıdaki kategorilerin dışında kalan HER ŞEY için yakalama ağı --
        // türe göre süzülmemiş, bu kullanıcının en yeni 20 olayı.
        List<EtkinlikDto> sonEtkinlikler = etkinlikService
            .ara(null, user.id, null, null, null, 0, 20)
            .items();

        EtkinlikKaydi sonGirisKaydi = EtkinlikKaydi.find(
            "kullaniciId = ?1 and tur = ?2 order by olusturmaZamani desc", user.id, EtkinlikTuru.GIRIS)
            .firstResult();
        Instant sonGiris = sonGirisKaydi == null ? null : sonGirisKaydi.olusturmaZamani;

        return new KullaniciAktiviteDto(user.username, videoYukleme, klip,
            toplamSureMs == null ? 0 : toplamSureMs.longValue(), kanallar, radyolar,
            klipKanallari, kayitKanallari, geriSarilanKanallar, sonEtkinlikler, sonGiris);
    }

    /**
     * Admin panelin giriş ekranı — bileşen sağlığı + son etkinlikler, tek
     * çağrıda. Her bileşen kontrolü kendi başına yakalanıyor: biri
     * çökerse (örn. Triton kapalıysa) diğerlerinin sonucu etkilenmemeli.
     */
    public SistemSagligiOzetDto genelBakis() {
        List<BilesenSaglikDurumu> bilesenler = new ArrayList<>();
        bilesenler.add(veritabaniSagligi());
        bilesenler.add(yayinSagligi());
        bilesenler.add(mediaMtxSagligi());
        bilesenler.add(minioSagligi());
        bilesenler.add(tritonSagligi());
        bilesenler.add(keycloakSagligi());
        bilesenler.add(redisSagligi());

        List<EtkinlikDto> sonEtkinlikler = etkinlikService
            .ara(null, null, null, null, null, 0, SON_ETKINLIK_SAYISI)
            .items();

        return new SistemSagligiOzetDto(bilesenler, sonEtkinlikler);
    }

    /**
     * "Genel Bakış"taki servis kartlarının detay sayıları — {@link #genelBakis()}
     * yalnızca erişilebilir mi diyor, bu metot gerçek değerleri Prometheus'tan
     * okuyor (bkz. {@link PrometheusClient}). Her sorgu bağımsız: biri veri
     * üretmiyorsa (örn. Keycloak henüz doğrulanmadı, MediaMTX/MinIO metrik
     * adı sürüme göre değişmiş olabilir) yalnızca o alan {@code null} kalır,
     * diğerleri etkilenmez.
     */
    public ServisMetrikleriDto servisMetrikleri() {
        return new ServisMetrikleriDto(
            toLong(prometheusClient.anlikDeger("sum(increase(nv_inference_count[5m]))")),
            prometheusClient.anlikDeger(
                "sum(rate(nv_inference_request_duration_us[5m])) "
                    + "/ clamp_min(sum(rate(nv_inference_request_success[5m])), 1) / 1000"),
            toLong(prometheusClient.anlikDeger("sum(nv_gpu_memory_used_bytes)")),

            prometheusClient.etiketliDegerler(
                "sum by (model) (rate(nv_inference_request_duration_us[5m])) "
                    + "/ clamp_min(sum by (model) (rate(nv_inference_request_success[5m])), 1) / 1000",
                "model"),

            toLong(prometheusClient.anlikDeger(
                "sum(pg_stat_activity_count{job=\"postgres\",datname=\"yayin_merkezi\"})")),
            toLong(prometheusClient.anlikDeger(
                "pg_database_size_bytes{job=\"postgres\",datname=\"yayin_merkezi\"}")),
            prometheusClient.anlikDeger(
                "sum(rate(pg_stat_database_xact_commit{job=\"postgres\",datname=\"yayin_merkezi\"}[5m]))"),

            toLong(prometheusClient.anlikDeger("redis_connected_clients")),
            toLong(prometheusClient.anlikDeger("redis_memory_used_bytes")),
            prometheusClient.anlikDeger("rate(redis_commands_processed_total[5m])"),

            toLong(prometheusClient.anlikDeger(
                "minio_cluster_capacity_usable_total_bytes - minio_cluster_capacity_usable_free_bytes")),
            toLong(prometheusClient.anlikDeger("minio_cluster_capacity_usable_total_bytes")),

            toLong(prometheusClient.anlikDeger("count(paths{job=\"mediamtx\"})")),
            toLong(prometheusClient.anlikDeger("count(hls_muxers{job=\"mediamtx\"})"))
        );
    }

    private static Long toLong(Double deger) {
        return deger == null ? null : Math.round(deger);
    }

    private BilesenSaglikDurumu veritabaniSagligi() {
        try {
            AppUser.count();
            return new BilesenSaglikDurumu("Veritabanı", true, "Bağlantı sağlıklı");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Veritabanı sağlık kontrolü başarısız");
            return new BilesenSaglikDurumu("Veritabanı", false, "Bağlanılamadı");
        }
    }

    /** MediaMTX'in kendisi değil, kaç kanalın gerçekten yayında olduğu — "Yayınlar" bileşeni. */
    private BilesenSaglikDurumu yayinSagligi() {
        try {
            Map<String, MediaMtxPathList.Item> durumlar = mediaMtxService.pathStates();
            long aktifKanal = Channel.countActive(null);
            long yayinda = Channel.<Channel>listAll().stream()
                .filter(c -> c.active)
                .filter(c -> {
                    MediaMtxPathList.Item item = durumlar.get(c.mediamtxPath);
                    return item != null && item.ready();
                })
                .count();
            boolean saglikli = aktifKanal == 0 || yayinda > 0;
            return new BilesenSaglikDurumu("Yayınlar", saglikli, yayinda + "/" + aktifKanal + " kanal yayında");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Yayın sağlık kontrolü başarısız");
            return new BilesenSaglikDurumu("Yayınlar", false, "MediaMTX'e ulaşılamadı");
        }
    }

    private BilesenSaglikDurumu mediaMtxSagligi() {
        try {
            mediaMtxService.pathStates();
            return new BilesenSaglikDurumu("MediaMTX", true, "Erişilebilir");
        } catch (RuntimeException e) {
            LOG.warnf(e, "MediaMTX sağlık kontrolü başarısız");
            return new BilesenSaglikDurumu("MediaMTX", false, "Erişilemedi");
        }
    }

    private BilesenSaglikDurumu minioSagligi() {
        try {
            minioClient.bucketExists(BucketExistsArgs.builder().bucket(videosBucket).build());
            return new BilesenSaglikDurumu("Depolama (MinIO)", true, "Erişilebilir");
        } catch (Exception e) {
            LOG.warnf(e, "MinIO sağlık kontrolü başarısız");
            return new BilesenSaglikDurumu("Depolama (MinIO)", false, "Erişilemedi");
        }
    }

    private BilesenSaglikDurumu tritonSagligi() {
        boolean saglikli = tritonClient.saglikliMi();
        return new BilesenSaglikDurumu("Yapay Zeka (Triton)", saglikli,
            saglikli ? "Hazır" : "Hazır değil / erişilemedi");
    }

    private BilesenSaglikDurumu keycloakSagligi() {
        try {
            keycloak.realm(keycloakRealm).toRepresentation();
            return new BilesenSaglikDurumu("Keycloak", true, "Erişilebilir");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Keycloak sağlık kontrolü başarısız");
            return new BilesenSaglikDurumu("Keycloak", false, "Erişilemedi");
        }
    }

    private BilesenSaglikDurumu redisSagligi() {
        try {
            // Anahtarin var olup olmamasi onemli degil -- cagrinin kendisi
            // baglanti/yaniti dogruluyor.
            redis.key(String.class).exists("saglik-kontrolu");
            return new BilesenSaglikDurumu("Redis", true, "Erişilebilir");
        } catch (RuntimeException e) {
            LOG.warnf(e, "Redis sağlık kontrolü başarısız");
            return new BilesenSaglikDurumu("Redis", false, "Erişilemedi");
        }
    }

    // ------------------------------------------------------------------

    private long distinctKullanici(EtkinlikTuru tur, Instant esik) {
        return EtkinlikKaydi.getEntityManager()
            .createQuery("select count(distinct k.kullaniciId) from EtkinlikKaydi k "
                + "where k.tur = :tur and k.olusturmaZamani >= :esik and k.kullaniciId is not null",
                Long.class)
            .setParameter("tur", tur)
            .setParameter("esik", esik)
            .getSingleResult();
    }

    /** Postgres'e özgü {@code extract(hour from ...)} — bu proje yalnızca Postgres'i destekliyor. */
    @SuppressWarnings("unchecked")
    private Map<Integer, Long> saatDagilimi(EtkinlikTuru tur) {
        List<Object[]> satirlar = EtkinlikKaydi.getEntityManager()
            .createNativeQuery("select extract(hour from olusturma_zamani)::int as saat, "
                + "count(*) as sayi from etkinlik_kayitlari where tur = :tur group by saat order by saat")
            .setParameter("tur", tur.name())
            .getResultList();
        Map<Integer, Long> sonuc = new HashMap<>();
        for (Object[] satir : satirlar) {
            sonuc.put(((Number) satir[0]).intValue(), ((Number) satir[1]).longValue());
        }
        return sonuc;
    }

    @SuppressWarnings("unchecked")
    private List<TopEtiketDto> topHedef(EtkinlikTuru tur, Function<UUID, String> adBul) {
        List<Object[]> satirlar = EtkinlikKaydi.getEntityManager()
            .createQuery("select k.hedefId, count(k) from EtkinlikKaydi k "
                + "where k.tur = :tur group by k.hedefId order by count(k) desc")
            .setParameter("tur", tur)
            .setMaxResults(TOP_N)
            .getResultList();
        return satirlar.stream()
            .map(satir -> {
                UUID id = (UUID) satir[0];
                long sayi = (Long) satir[1];
                return new TopEtiketDto(id, adBul.apply(id), sayi);
            })
            .toList();
    }

    /**
     * Kullanıcının bir hedef türündeki (kanal/radyo) izleme/dinleme özeti —
     * oturum sayısı VE toplam süre. {@code IZLEME_BASLADI} değil
     * {@code IZLEME_BITTI}/{@code DINLEME_BITTI} üzerinden sayılıyor: her
     * biten oturum tek bir satır ve {@code detay.sureMs} zaten orada.
     */
    @SuppressWarnings("unchecked")
    private List<HedefIzlemeOzetiDto> hedefIzlemeOzeti(String tur, UUID kullaniciId, Function<UUID, String> adBul) {
        List<Object[]> satirlar = EtkinlikKaydi.getEntityManager()
            .createNativeQuery(
                "select hedef_id, count(*), coalesce(sum((detay->>'sureMs')::bigint), 0) as toplam_sure "
                    + "from etkinlik_kayitlari "
                    + "where tur = :tur and kullanici_id = :kullaniciId "
                    + "group by hedef_id "
                    + "order by toplam_sure desc "
                    + "limit " + TOP_N)
            .setParameter("tur", tur)
            .setParameter("kullaniciId", kullaniciId)
            .getResultList();
        return satirlar.stream()
            .map(satir -> {
                UUID id = (UUID) satir[0];
                long oturumSayisi = ((Number) satir[1]).longValue();
                long toplamSureMs = ((Number) satir[2]).longValue();
                return new HedefIzlemeOzetiDto(id, adBul.apply(id), oturumSayisi, toplamSureMs);
            })
            .toList();
    }

    /**
     * Kullanıcının bir kanaldaki sayım-tabanlı özeti — manuel kayıt
     * ({@code KAYIT_BASLADI}) ve DVR geri sarma ({@code DVR_GERI_SARILDI})
     * için. Bu iki tür için hedef zaten kanalın kendisi (hedef_id = channel.id),
     * {@link #klipAlinanKanallar} ile karıştırılmamalı.
     */
    private List<TopEtiketDto> topHedefKullaniciSayi(EtkinlikTuru tur, UUID kullaniciId, Function<UUID, String> adBul) {
        List<Object[]> satirlar = EtkinlikKaydi.getEntityManager()
            .createQuery("select k.hedefId, count(k) from EtkinlikKaydi k "
                + "where k.tur = :tur and k.kullaniciId = :kullaniciId group by k.hedefId order by count(k) desc")
            .setParameter("tur", tur)
            .setParameter("kullaniciId", kullaniciId)
            .setMaxResults(TOP_N)
            .getResultList();
        return satirlar.stream()
            .map(satir -> {
                UUID id = (UUID) satir[0];
                long sayi = (Long) satir[1];
                return new TopEtiketDto(id, adBul.apply(id), sayi);
            })
            .toList();
    }

    /**
     * {@code KLIP_OLUSTURULDU}'nun hedefi kanal değil klibin kendisidir
     * (hedef_id = clip.id) — kanal adı yalnızca {@code detay->>'kanal'}'da
     * ham metin olarak duruyor, bu yüzden hedef_id ile değil isimle
     * gruplanıyor (bkz. {@link AdSayiDto}).
     */
    @SuppressWarnings("unchecked")
    private List<AdSayiDto> klipAlinanKanallar(UUID kullaniciId) {
        List<Object[]> satirlar = EtkinlikKaydi.getEntityManager()
            .createNativeQuery(
                "select detay->>'kanal' as kanal, count(*) as sayi "
                    + "from etkinlik_kayitlari "
                    + "where tur = 'KLIP_OLUSTURULDU' and kullanici_id = :id "
                    + "group by kanal "
                    + "order by sayi desc "
                    + "limit " + TOP_N)
            .setParameter("id", kullaniciId)
            .getResultList();
        return satirlar.stream()
            .map(satir -> new AdSayiDto((String) satir[0], ((Number) satir[1]).longValue()))
            .toList();
    }

    private static String kanalAdi(UUID id) {
        Channel channel = Channel.findById(id);
        return channel == null ? "Silinmiş kanal" : channel.name;
    }

    private static String radyoAdi(UUID id) {
        Radio radio = Radio.findById(id);
        return radio == null ? "Silinmiş radyo" : radio.name;
    }
}
