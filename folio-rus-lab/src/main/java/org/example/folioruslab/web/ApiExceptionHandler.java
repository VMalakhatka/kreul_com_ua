package org.example.folioruslab.web;

import org.example.folioruslab.db.DatabaseGuardException;
import org.example.folioruslab.sql.LabBusyException;
import org.example.folioruslab.sql.SqlPolicyViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public final class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(SqlPolicyViolationException.class)
    ResponseEntity<Map<String, ApiError>> policy(SqlPolicyViolationException exception) {
        return response(HttpStatus.BAD_REQUEST, new ApiError(
                "SQL_POLICY_REJECTED",
                "The SQL batch crosses the Paint_Rus laboratory boundary",
                exception.getViolations()
        ));
    }

    @ExceptionHandler(LabBusyException.class)
    ResponseEntity<Map<String, ApiError>> busy(LabBusyException exception) {
        return response(HttpStatus.CONFLICT, new ApiError(
                "LAB_BUSY", exception.getMessage(), List.of()
        ));
    }

    @ExceptionHandler(DatabaseGuardException.class)
    ResponseEntity<Map<String, ApiError>> guard(DatabaseGuardException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, new ApiError(
                exception.getCode(), exception.getMessage(), List.of()
        ));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Map<String, ApiError>> invalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, new ApiError(
                "INVALID_REQUEST",
                "The request body or execution limits are invalid",
                List.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, ApiError>> unexpected(Exception exception) {
        log.error("Unhandled local laboratory API error: type={}", exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(
                "INTERNAL_ERROR",
                "The local laboratory could not complete the request",
                List.of()
        ));
    }

    private static ResponseEntity<Map<String, ApiError>> response(
            HttpStatus status,
            ApiError error
    ) {
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    public record ApiError(String code, String message, List<String> details) {
    }
}
