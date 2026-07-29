package org.example.proect.lavka.dao.folio;

public enum FolioDocumentCounterCode {
    ACCOUNT_ACCOUNTED("СU"),
    ACCOUNT_UNACCOUNTED("СN"),
    EXPENSE_ACCOUNTED("РU"),
    RECEIPT_ACCOUNTED("ПU"),
    RECEIPT_UNACCOUNTED("ПN");

    private final String databaseSuffix;

    FolioDocumentCounterCode(String databaseSuffix) {
        this.databaseSuffix = databaseSuffix;
    }

    public String databaseSuffix() {
        return databaseSuffix;
    }
}
