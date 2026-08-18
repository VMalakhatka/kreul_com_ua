package org.example.folioruslab.accounting;

import jakarta.validation.constraints.Min;

public record SafeAccountingPricePreviewRequest(
        @Min(1) int warehouseId
) {
}
