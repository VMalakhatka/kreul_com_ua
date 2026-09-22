package org.example.proect.lavka.dto.folio;

import java.util.List;

public record FolioProfitTaxSettings(
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=VersionDeserializer.class) Long version,
        List<String> retailFirmCodes, List<String> wholesaleFirmCodes) {
    public FolioProfitTaxSettings {
        // Preserve invalid/null input for service validation; never retain a caller's mutable list.
        retailFirmCodes = retailFirmCodes == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(retailFirmCodes));
        wholesaleFirmCodes = wholesaleFirmCodes == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(wholesaleFirmCodes));
    }
    public static final class VersionDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser p,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT) {
                long value = p.getLongValue();
                if (value >= 0 && value < Long.MAX_VALUE) return value;
            }
            throw com.fasterxml.jackson.databind.JsonMappingException.from(p, "Tax settings version must be a non-negative integer");
        }
    }
    public static FolioProfitTaxSettings defaults() {
        return new FolioProfitTaxSettings(0L, List.of("МИХНФОП","МАЛАФОП"), List.of("КУЗНФОП","КОНДФОП"));
    }
}
