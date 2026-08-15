package org.example.etkinlik;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.example.etkinlik.dto.CanliDurumDto;
import org.example.etkinlik.dto.DepolamaDto;
import org.example.etkinlik.dto.GenelAktiviteDto;
import org.example.etkinlik.dto.IcerikPerformansiDto;
import org.example.etkinlik.dto.KullaniciAktiviteDto;
import org.example.etkinlik.dto.ServisMetrikleriDto;
import org.example.etkinlik.dto.SistemSagligiOzetDto;
import org.example.etkinlik.dto.TeknikDto;
import org.example.etkinlik.dto.VideoAnalitikOzetDto;
import org.example.etkinlik.dto.VideoIsiHaritasiDto;
import org.example.user.Roles;

import java.util.List;
import java.util.UUID;

/**
 * Faz 1 analitik dashboard'u — beş uç, kullanıcının önerdiği beş dashboard
 * modülüyle birebir eşleşiyor (bkz. {@code docs/analitik-dashboard-plani-faz1.md}).
 * {@code AdminUserResource}/{@code AdminEtkinlikResource} ile aynı desen:
 * sınıf düzeyi {@code @RolesAllowed}.
 */
@Path("/api/admin/analitik")
@RolesAllowed(Roles.YONETICI)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Analitik", description = "Yöneticiye özel dashboard verisi")
public class AdminAnalitikResource {

    @Inject
    AnalitikService analitikService;

    @GET
    @Path("/genel-bakis")
    @Operation(summary = "Genel Bakış",
        description = "Bileşen sağlığı (veritabanı, yayınlar, MediaMTX, MinIO, Triton) ve "
            + "son 10 etkinlik — admin panelin giriş ekranı.")
    public SistemSagligiOzetDto genelBakis() {
        return analitikService.genelBakis();
    }

    @GET
    @Path("/servis-metrikleri")
    @Operation(summary = "Servis detay metrikleri",
        description = "Triton/Postgres/Redis/MinIO/MediaMTX için Prometheus'tan okunan sayısal "
            + "detay (bağlantı sayısı, bellek kullanımı, gecikme vb.) — genel-bakis'teki "
            + "erişilebilir mi/değil bilgisinin ötesinde. Prometheus'a ulaşılamıyorsa ya da "
            + "metrik henüz veri üretmiyorsa ilgili alan null döner.")
    public ServisMetrikleriDto servisMetrikleri() {
        return analitikService.servisMetrikleri();
    }

    @GET
    @Path("/canli-durum")
    @Operation(summary = "Canlı Sistem Durumu",
        description = "Eşzamanlı izleyici/dinleyici, aktif DVR kaydı, anlık trafik (Mbps). "
            + "Trafik alanı yalnızca ilk çağrıda null döner (henüz karşılaştırılacak örnek yok).")
    public CanliDurumDto canliDurum() {
        return analitikService.canliDurum();
    }

    @GET
    @Path("/icerik-performansi")
    @Operation(summary = "İçerik & Kanal Performansı",
        description = "En çok izlenen kanallar, dinlenen radyolar, kaydedilen yayınlar (top 10).")
    public IcerikPerformansiDto icerikPerformansi() {
        return analitikService.icerikPerformansi();
    }

    @GET
    @Path("/depolama")
    @Operation(summary = "Depolama ve DVR Analitiği",
        description = "En yüksek kotalı 10 kullanıcı, gelecek 24 saatteki planlı kayıt yükü, "
            + "toplam DVR boyutu.")
    public DepolamaDto depolama() {
        return analitikService.depolama();
    }

    @GET
    @Path("/teknik")
    @Operation(summary = "Teknik & Hata Takibi",
        description = "Başarısız planlı kayıt, video işleme hatası sayıları ve yayın kopma oranı "
            + "(oynatma hatası+takılma / toplam izleme+dinleme başlangıcı).")
    public TeknikDto teknik() {
        return analitikService.teknik();
    }

    @GET
    @Path("/genel")
    @Operation(summary = "Genel Kullanıcı Aktivitesi",
        description = "DAU/MAU, saat bazlı giriş dağılımı (peak hours), zapping vekili.")
    public GenelAktiviteDto genel() {
        return analitikService.genel();
    }

    @GET
    @Path("/videolar")
    @Operation(summary = "İzlenen video listesi (özet)",
        description = "Oturum sayısı ve tamamlanma oranına göre azalan sırada; en fazla 200.")
    public List<VideoAnalitikOzetDto> videoListesi() {
        return analitikService.videoListesi();
    }

    @GET
    @Path("/videolar/{id}")
    @Operation(summary = "Video izleme ısı haritası",
        description = "10 dilimlik kaba tekrar-izleme dağılımı ve tamamlanma oranı.")
    public VideoIsiHaritasiDto videoIsiHaritasi(@PathParam("id") UUID id) {
        return analitikService.videoIsiHaritasi(id);
    }

    @GET
    @Path("/kullanicilar/{keycloakId}")
    @Operation(summary = "Kullanıcı aktivite özeti",
        description = "Video yükleme/klip sayısı, izlenen kanallar/dinlenen radyolar, "
            + "toplam izleme süresi, son giriş. Yol parametresi Keycloak kullanıcı id'si "
            + "(AdminUserResource ile aynı).")
    public KullaniciAktiviteDto kullaniciAktivitesi(@PathParam("keycloakId") String keycloakId) {
        return analitikService.kullaniciAktivitesi(keycloakId);
    }
}
