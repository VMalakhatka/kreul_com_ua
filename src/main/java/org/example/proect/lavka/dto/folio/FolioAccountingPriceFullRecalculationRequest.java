package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FolioAccountingPriceFullRecalculationRequest(
        @NotNull @Positive Integer warehouseId,
        @NotNull Boolean previewOnly,
        Boolean continueOnNegativeStock
) {
    public boolean shouldContinueOnNegativeStock() {
        return continueOnNegativeStock == null || continueOnNegativeStock;
    }
}
