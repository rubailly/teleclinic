package rw.teleclinic.core.shared;

import org.springframework.http.HttpStatus;

/** A failure the caller can act on. Rendered as an OperationOutcome (Section 6.1). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String diagnostics) {
        super(diagnostics);
        this.status = status;
        this.code = code;
    }

    public static ApiException forbidden(String code, String diagnostics) {
        return new ApiException(HttpStatus.FORBIDDEN, code, diagnostics);
    }

    public static ApiException notFound(String diagnostics) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", diagnostics);
    }

    public static ApiException conflict(String code, String diagnostics) {
        return new ApiException(HttpStatus.CONFLICT, code, diagnostics);
    }

    public static ApiException unprocessable(String code, String diagnostics) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, diagnostics);
    }

    public static ApiException unavailable(String code, String diagnostics) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, diagnostics);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
