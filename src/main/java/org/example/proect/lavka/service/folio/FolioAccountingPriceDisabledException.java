package org.example.proect.lavka.service.folio;

public class FolioAccountingPriceDisabledException extends RuntimeException {
    private final String code;

    public FolioAccountingPriceDisabledException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
