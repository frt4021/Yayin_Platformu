package org.example.etkinlik.dto;

import java.util.UUID;

/** @param dilimSayaclari her zaman uzunluk 10 — sıfır-doldurulmuş, sparse değil. */
public record VideoIsiHaritasiDto(UUID videoId, String baslik, long oturumSayisi,
                                   double tamamlanmaOrani, long[] dilimSayaclari) {
}
