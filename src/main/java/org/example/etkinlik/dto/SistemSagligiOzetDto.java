package org.example.etkinlik.dto;

import java.util.List;

public record SistemSagligiOzetDto(
    List<BilesenSaglikDurumu> bilesenler,
    List<EtkinlikDto> sonEtkinlikler
) {
}
