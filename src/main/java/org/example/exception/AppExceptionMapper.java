package org.example.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Spring'deki @ExceptionHandler(AppException.class) karşılığı.
 * Tüm modüllerde fırlatılan AppException'lar buraya düşer —
 * yeni bir hata durumu için bu dosyaya dokunmanız gerekmez,
 * sadece ErrorCode + AppException'a factory metod eklemeniz yeterli.
 */
@Provider
public class AppExceptionMapper implements ExceptionMapper<AppException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(AppException ex) {
        ErrorCode code = ex.getErrorCode();
        ErrorResponse body = ErrorResponse.of(
            code.getStatus().getStatusCode(),
            code.name(),
            ex.getMessage(),
            uriInfo.getPath()
        );
        return Response.status(code.getStatus()).entity(body).build();
    }
}
