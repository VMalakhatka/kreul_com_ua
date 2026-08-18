package org.example.folioruslab.accounting;

public record SafeAccountingPriceProblem(
        String code,
        String message,
        String sku,
        String nextSku,
        Integer recno,
        String movementDate,
        String formula,
        Double numerator,
        Double denominator,
        Double quantityBefore,
        Double movementQuantity,
        String folioNegativeDate
) {
}
