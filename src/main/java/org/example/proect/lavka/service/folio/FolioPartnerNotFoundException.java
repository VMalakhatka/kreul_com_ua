package org.example.proect.lavka.service.folio;

public class FolioPartnerNotFoundException extends RuntimeException {
    public FolioPartnerNotFoundException(String partnerId) {
        super("Folio partner not found: " + partnerId);
    }
}
