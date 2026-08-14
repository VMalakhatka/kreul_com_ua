package org.example.proect.lavka.dto.folio;

import java.util.Locale;

public enum FolioCustomerDocumentType {
    ACCOUNT("С", 1, true),
    EXPENSE("Р", 2, true),
    PAYMENT(null, 3, false);

    private final String folioType;
    private final int sortRank;
    private final boolean repeatable;

    FolioCustomerDocumentType(String folioType, int sortRank, boolean repeatable) {
        this.folioType = folioType;
        this.sortRank = sortRank;
        this.repeatable = repeatable;
    }

    public String folioType() {
        return folioType;
    }

    public int sortRank() {
        return sortRank;
    }

    public boolean repeatable() {
        return repeatable;
    }

    public static FolioCustomerDocumentType parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Document type is required");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
