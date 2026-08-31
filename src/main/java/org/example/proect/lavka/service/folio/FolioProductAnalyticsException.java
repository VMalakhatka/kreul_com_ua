package org.example.proect.lavka.service.folio;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class FolioProductAnalyticsException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public FolioProductAnalyticsException(String code, HttpStatus status, String message) {
        this(code, status, message, Map.of());
    }

    public FolioProductAnalyticsException(String code, HttpStatus status,
                                          String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }
}
