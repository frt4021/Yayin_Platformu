package org.example.exception;

import java.time.Instant;
import java.util.List;

/**
 * Tüm API hatalarının döndüğü tek, tutarlı format.
 * Frontend için Spring ya da Quarkus fark etmez, aynı şekli görür.
 */
public record ErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, List.of());
    }

    public static ErrorResponse ofFieldErrors(int status, String error, String path, List<FieldError> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, error, "Doğrulama hatası", path, fieldErrors);
    }
}
