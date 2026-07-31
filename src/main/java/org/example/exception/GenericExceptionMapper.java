package org.example.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Spring'deki @ExceptionHandler(Exception.class) karşılığı.
 * AppException ya da ConstraintViolationException dışında kalan HER ŞEY
 * buraya düşer — client'a asla ham stack trace gitmez, sadece loglanır.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GenericExceptionMapper.class);

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(Throwable ex) {
        LOG.error("Beklenmeyen hata: " + uriInfo.getPath(), ex);

        ErrorResponse body = ErrorResponse.of(500, "INTERNAL_ERROR", "Beklenmeyen bir hata oluştu", uriInfo.getPath());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
    }
}
