package org.example.proect.lavka.service.folio;

public class FolioAccountConflictException extends RuntimeException {
    private final String code;

    public FolioAccountConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
