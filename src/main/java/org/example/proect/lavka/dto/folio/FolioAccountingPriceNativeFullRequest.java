package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FolioAccountingPriceNativeFullRequest(
        @NotNull @Positive Integer warehouseId,
        @NotNull Boolean previewOnly,
        Boolean confirmApply
) {
    public boolean isApplyConfirmed() {
        return Boolean.TRUE.equals(confirmApply);
    }
}
