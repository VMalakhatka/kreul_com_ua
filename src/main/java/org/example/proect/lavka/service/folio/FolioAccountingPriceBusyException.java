package org.example.proect.lavka.service.folio;

public class FolioAccountingPriceBusyException extends RuntimeException {
    public FolioAccountingPriceBusyException() {
        super("Another Folio accounting-price recalculation is already running");
    }

    public FolioAccountingPriceBusyException(Throwable cause) {
        super("Another Folio accounting-price recalculation is already running: "
                + cause.getMessage(), cause);
    }
}
