package org.example.etkinlik.dto;

/**
 * Admin panelin "Genel Bakış" ekranındaki servis detay metrikleri —
 * {@code BilesenSaglikDurumu}'nun (yalnızca erişilebilir mi) aksine gerçek
 * sayısal değerler. {@code PrometheusClient} üzerinden okunuyor; Prometheus'a
 * ulaşılamıyorsa ya da metrik henüz veri üretmiyorsa ilgili alan {@code null}
 * — sessizce 0 göstermek yerine (bkz. CLAUDE.md "ölçmeden sayı verme").
 */
public record ServisMetrikleriDto(
    // Triton — model başına değil toplam, admin panelde tek bakışlık özet
    // için; model kırılımı Grafana'daki "Triton Metrikleri" dashboard'unda.
    Long tritonIstekSayisi5dk,
    Double tritonOrtalamaGecikmeMs,
    Long tritonGpuBellekBayt,

    // Postgres — yalnızca uygulama veritabanı (yayin_merkezi); Keycloak'ın
    // ayrı veritabanı burada değil, o zaten Keycloak sağlık kontrolüne dahil.
    Long postgresAktifBaglanti,
    Long postgresBoyutBayt,
    Double postgresCommitOrani5dk,

    Long redisBagliIstemci,
    Long redisBellekBayt,
    Double redisKomutOrani5dk,

    Long minioKullanilanBayt,
    Long minioToplamBayt,

    Long mediaMtxAktifPath,
    Long mediaMtxAktifHlsMuxer
) {
}
