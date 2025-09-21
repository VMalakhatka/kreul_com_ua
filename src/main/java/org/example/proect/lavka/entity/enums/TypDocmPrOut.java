package org.example.proect.lavka.entity.enums;

public enum TypDocmPrOut {
    R("Р"),// расходная наклданая
    P("П"),// приходная накладная
    S("С");// счет

    private final String description;

    TypDocmPrOut(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static TypDocmPrOut fromString(String text) {
        for (TypDocmPrOut type : TypDocmPrOut.values()) {
            if (type.description.equalsIgnoreCase(text)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No constant with description " + text + " found");
    }
}
