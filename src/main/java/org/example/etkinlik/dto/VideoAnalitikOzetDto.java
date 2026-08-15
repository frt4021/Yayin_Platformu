package org.example.etkinlik.dto;

import java.util.UUID;

public record VideoAnalitikOzetDto(UUID videoId, String baslik, long oturumSayisi,
                                    double tamamlanmaOrani) {
}
