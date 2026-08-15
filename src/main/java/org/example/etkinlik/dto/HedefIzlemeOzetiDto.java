package org.example.etkinlik.dto;

import java.util.UUID;

/** Bir kullanıcının tek bir kanal/radyodaki izleme/dinleme özeti. */
public record HedefIzlemeOzetiDto(UUID id, String ad, long oturumSayisi, long toplamSureMs) {
}
