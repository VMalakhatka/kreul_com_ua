package org.example.proect.lavka.service.folio;

public final class FolioAccountingModeUnsupportedException
        extends FolioAccountValidationException {

    private final Integer rawCode;
    private final String modeName;
    private final String recommendation;

    public FolioAccountingModeUnsupportedException(
            String code,
            Integer rawCode,
            String modeName,
            String recommendation,
            String message
    ) {
        super(code, message);
        this.rawCode = rawCode;
        this.modeName = modeName;
        this.recommendation = recommendation;
    }

    public Integer rawCode() {
        return rawCode;
    }

    public String modeName() {
        return modeName;
    }

    public String recommendation() {
        return recommendation;
    }
}
