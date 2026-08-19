package org.example.user.dto;

import java.util.List;

/**
 * {@code org.example.etkinlik.dto.EtkinlikSayfasiDto} ile aynı desen — sayfa
 * içeriği ile gerçek toplam sayı birlikte döner ki arayüz numaralı
 * sayfalama çizebilsin.
 */
public record KullaniciSayfasiDto(List<UserDto> items, long total, int first, int max) {
}
