package org.example.etkinlik.dto;

import java.util.List;

public record IcerikPerformansiDto(
    List<TopEtiketDto> enCokIzlenenKanallar,
    List<TopEtiketDto> enCokDinlenenRadyolar,
    List<TopEtiketDto> enCokKaydedilenYayinlar
) {
}
