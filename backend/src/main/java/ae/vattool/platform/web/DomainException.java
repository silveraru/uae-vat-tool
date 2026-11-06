package ae.vattool.platform.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A rule the caller broke, reported with a stable code the UI can translate. */
public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public DomainException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public DomainException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static DomainException notFound(String what) {
        return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static DomainException forbidden(String message) {
        return new DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static DomainException invalid(String code, String message) {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details == null ? Map.of() : details;
    }
}
