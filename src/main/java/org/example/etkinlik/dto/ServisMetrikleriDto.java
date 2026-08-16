package org.example.etkinlik.dto;

import java.util.Map;

/**
 * Admin panelin "Genel Bakış" ekranındaki servis detay metrikleri —
 * {@code BilesenSaglikDurumu}'nun (yalnızca erişilebilir mi) aksine gerçek
 * sayısal değerler. {@code PrometheusClient} üzerinden okunuyor; Prometheus'a
 * ulaşılamıyorsa ya da metrik henüz veri üretmiyorsa ilgili alan {@code null}
 * — sessizce 0 göstermek yerine (bkz. CLAUDE.md "ölçmeden sayı verme").
 */
public record ServisMetrikleriDto(
    // Triton toplamı — tek bakışlık özet.
    Long tritonIstekSayisi5dk,
    Double tritonOrtalamaGecikmeMs,
    Long tritonGpuBellekBayt,

    // Model/dil bazlı kırılım (whisper, marian_en_tr, marian_en_de,
    // marian_en_ru) — toplam ortalamanın içinde bir dilin çok daha yavaş
    // olduğu (ör. GPU VRAM sınırına yakınken Marian'ların 10+ saniyeye
    // çıkması) gizlenmesin diye ayrı ayrı. Veri yoksa boş harita.
    Map<String, Double> tritonModelGecikmeMs,

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
