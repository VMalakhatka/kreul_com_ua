package org.example.proect.lavka.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@lombok.extern.slf4j.Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // --- ВНЕШНИЕ HTTP фейлы (Woo/WP и т.д.) ---
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleRemote(RestClientResponseException e, HttpServletRequest r) {
        String body = truncate(e.getResponseBodyAsString(), 4000);
        log.error("[sync.errors] remote status={} uri={} body={}", e.getRawStatusCode(), r.getRequestURI(), body, e);

        return problem(r, HttpStatus.valueOf(e.getRawStatusCode()), "Remote call failed", Map.of(
                "remoteStatus", e.getRawStatusCode(),
                "remoteBody", truncate(e.getResponseBodyAsString(), 1000)
        ));
    }

    // --- Ошибки доступа к БД ---
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDatabase(DataAccessException e, HttpServletRequest r) {
        Throwable root = e.getMostSpecificCause() != null ? e.getMostSpecificCause() : e;
        log.error("[sync.errors] db uri={} cause={} msg={}", r.getRequestURI(),
                root.getClass().getSimpleName(), root.getMessage(), e);

        return problem(r, HttpStatus.SERVICE_UNAVAILABLE, "Database error", Map.of(
                "reason", root.getClass().getSimpleName(),
                "message", truncate(String.valueOf(root.getMessage()), 500)
        ));
    }

    // --- Некорректное тело запроса/параметры ---
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException e, HttpServletRequest r) {
        log.warn("[sync.errors] bad-json uri={} msg={}", r.getRequestURI(), e.getMessage());
        return problem(r, HttpStatus.BAD_REQUEST, "Malformed JSON", Map.of(
                "message", truncate(String.valueOf(e.getMessage()), 500)
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest r) {
        log.warn("[sync.errors] missing-param uri={} name={} type={}", r.getRequestURI(), e.getParameterName(), e.getParameterType());
        return problem(r, HttpStatus.BAD_REQUEST, "Missing request parameter", Map.of(
                "param", e.getParameterName(),
                "type", e.getParameterType()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest r) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[sync.errors] bean-validation uri={} details={}", r.getRequestURI(), details);
        return problem(r, HttpStatus.BAD_REQUEST, "Validation failed", Map.of(
                "details", truncate(details, 1000)
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e, HttpServletRequest r) {
        String details = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("[sync.errors] constraint-violation uri={} details={}", r.getRequestURI(), details);
        return problem(r, HttpStatus.BAD_REQUEST, "Constraint violation", Map.of(
                "details", truncate(details, 1000)
        ));
    }

    // --- Всё остальное ---
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e, HttpServletRequest r) {
        log.error("[sync.errors] unhandled uri={} msg={}", r.getRequestURI(), e.getMessage(), e);
        return problem(r, HttpStatus.INTERNAL_SERVER_ERROR, "Unhandled error", Map.of(
                "message", truncate(String.valueOf(e.getMessage()), 1000)
        ));
    }

    // ==== helpers ====

    private ResponseEntity<Map<String, Object>> problem(HttpServletRequest r, HttpStatus status, String title, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("title", title);
        body.put("path", r.getRequestURI());
        body.put("reqId", MDC.get("reqId"));

        if (extra != null) body.putAll(extra);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}