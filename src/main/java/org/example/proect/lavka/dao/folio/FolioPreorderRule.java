package org.example.proect.lavka.dao.folio;

/** Shared selection for the reserve and the customer's document filter. Active ledger only. */
public final class FolioPreorderRule {
    private FolioPreorderRule() {}
    public static final String PREDICATE = "n.TYPE_DOC = 'С' AND n.STND_UCHET = 0"
            + " AND LTRIM(RTRIM(n.VID_DOC)) = '*РАЗОВАЯ' AND LTRIM(n.L_CP2_PLAT) LIKE 'ПРД%'";
}
