package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FolioAccountingPriceRecalculationRequest(
        @NotBlank @Size(max = 20) String sku,
        @NotNull @Positive Integer warehouseId,
        @NotNull Boolean previewOnly
) {
}
