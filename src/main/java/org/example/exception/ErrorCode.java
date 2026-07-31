package org.example.exception;

import jakarta.ws.rs.core.Response;

/**
 * Uygulamadaki tüm runtime hata kodları burada toplanır.
 * Spring versiyonundaki mantığın birebir aynısı — sadece HttpStatus yerine
 * JAX-RS'in Response.Status'u kullanılıyor.
 */
public enum ErrorCode {
    NOT_FOUND(Response.Status.NOT_FOUND),
    CONFLICT(Response.Status.CONFLICT),
    UNAUTHORIZED(Response.Status.UNAUTHORIZED),
    FORBIDDEN(Response.Status.FORBIDDEN),
    UPSTREAM_ERROR(Response.Status.BAD_GATEWAY),
    BAD_REQUEST(Response.Status.BAD_REQUEST),
    INTERNAL_ERROR(Response.Status.INTERNAL_SERVER_ERROR);

    private final Response.Status status;

    ErrorCode(Response.Status status) {
        this.status = status;
    }

    public Response.Status getStatus() {
        return status;
    }
}
