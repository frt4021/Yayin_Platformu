package org.example.exception;

/**
 * Uygulamadaki TÜM runtime (iş kuralı) hataları için kullanılan tek sınıf.
 * Framework'e hiç bağımlı değil — Spring'den Quarkus'a geçişte bu dosya
 * karakter karakter aynı kalabilir, sadece ErrorCode'un içindeki
 * Response.Status/HttpStatus farkı framework'e özel katmanda kalıyor.
 *
 *   throw AppException.notFound("Kanal bulunamadı: " + id);
 */
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    private AppException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    private AppException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public static AppException notFound(String message) {
        return new AppException(ErrorCode.NOT_FOUND, message);
    }

    public static AppException conflict(String message) {
        return new AppException(ErrorCode.CONFLICT, message);
    }

    public static AppException badRequest(String message) {
        return new AppException(ErrorCode.BAD_REQUEST, message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(ErrorCode.UNAUTHORIZED, message);
    }

    public static AppException forbidden(String message) {
        return new AppException(ErrorCode.FORBIDDEN, message);
    }

    public static AppException upstreamError(String message) {
        return new AppException(ErrorCode.UPSTREAM_ERROR, message);
    }

    public static AppException upstreamError(String message, Throwable cause) {
        return new AppException(ErrorCode.UPSTREAM_ERROR, message, cause);
    }

    public static AppException internalError(String message, Throwable cause) {
        return new AppException(ErrorCode.INTERNAL_ERROR, message, cause);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
