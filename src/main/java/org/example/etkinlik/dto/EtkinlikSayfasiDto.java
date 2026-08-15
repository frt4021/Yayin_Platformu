package org.example.etkinlik.dto;

import java.util.List;

public record EtkinlikSayfasiDto(List<EtkinlikDto> items, long total, int first, int max) {
}
