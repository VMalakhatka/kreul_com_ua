package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record FolioProductSnapshotRefreshRequest(
        @NotNull @Min(1) Integer warehouseId,
        @Min(12) @Max(36) Integer horizonMonths
) {
    public int effectiveHorizonMonths(int defaultValue) {
        return horizonMonths == null ? defaultValue : horizonMonths;
    }
}
