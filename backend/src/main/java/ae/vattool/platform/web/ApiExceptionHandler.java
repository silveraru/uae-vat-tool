package ae.vattool.platform.web;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setProperty("code", e.code());
        e.details().forEach(p::setProperty);
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        p.setProperty("code", "VALIDATION_FAILED");
        return p;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail denied(AccessDeniedException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not permitted");
        p.setProperty("code", "FORBIDDEN");
        return p;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail unreadable(Exception e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request could not be read");
        p.setProperty("code", "BAD_REQUEST");
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badArgument(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setProperty("code", "BAD_REQUEST");
        return p;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse) {
            throw e; // let Spring render its own 4xx responses
        }
        log.error("Unhandled error", e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        p.setProperty("code", "INTERNAL_ERROR");
        return p;
    }
}
