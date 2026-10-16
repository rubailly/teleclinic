package rw.teleclinic.core.shared;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error leaves the core in the same shape as a FHIR OperationOutcome. */
@RestControllerAdvice
class OperationOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(OperationOutcomeHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        log.info("Request refused: {} {} ({})", e.status().value(), e.code(), e.getMessage());
        return outcome(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception e) {
        String detail = e instanceof MethodArgumentNotValidException m
                ? m.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().toList().toString()
                : "Request body is not valid JSON for this endpoint";
        return outcome(HttpStatus.UNPROCESSABLE_ENTITY, "invalid", detail);
    }

    static ResponseEntity<Map<String, Object>> outcome(HttpStatus status, String code, String diagnostics) {
        Map<String, Object> issue = Map.of("severity", "error", "code", code, "diagnostics", diagnostics);
        return ResponseEntity.status(status)
                .body(Map.of("resourceType", "OperationOutcome", "issue", List.of(issue)));
    }
}
