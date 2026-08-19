package org.example.folioruslab.snapshot;

import jakarta.validation.constraints.Min;

public record AccountingPriceSourceSnapshotRequest(
        @Min(1) int warehouseId
) {
}
