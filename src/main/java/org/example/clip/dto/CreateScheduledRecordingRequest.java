package org.example.clip.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Kayıt emri isteği.
 *
 * <p>Aralık <b>geçmişte, şu anda ya da gelecekte</b> olabilir. Tamamen geçmişse
 * beklemeye gerek yok, klip hemen açılır; aksi halde emir kuyruğa girer.
 */
public record CreateScheduledRecordingRequest(
    @NotNull Instant baslangic,
    @NotNull Instant bitis
) {
}
