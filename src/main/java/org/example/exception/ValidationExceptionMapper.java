package org.example.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Spring'deki @ExceptionHandler(MethodArgumentNotValidException.class) karşılığı.
 * @Valid ile işaretli bir istek gövdesi doğrulamayı geçemediğinde Hibernate
 * Validator bu exception'ı fırlatır, biz onu aynı ErrorResponse formatına çeviririz.
 */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(ConstraintViolationException ex) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
            .map(this::toFieldError)
            .toList();

        ErrorResponse body = ErrorResponse.ofFieldErrors(400, "VALIDATION_ERROR", uriInfo.getPath(), fieldErrors);
        return Response.status(Response.Status.BAD_REQUEST).entity(body).build();
    }

    private ErrorResponse.FieldError toFieldError(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath().toString();
        return new ErrorResponse.FieldError(field, violation.getMessage());
    }
}
