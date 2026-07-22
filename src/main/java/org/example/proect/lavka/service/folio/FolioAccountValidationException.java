package org.example.proect.lavka.service.folio;

public class FolioAccountValidationException extends RuntimeException {
    private final String code;

    public FolioAccountValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
