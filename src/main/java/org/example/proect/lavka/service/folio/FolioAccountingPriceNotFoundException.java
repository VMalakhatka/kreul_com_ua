package org.example.proect.lavka.service.folio;

public class FolioAccountingPriceNotFoundException extends RuntimeException {
    private final String code;

    public FolioAccountingPriceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
