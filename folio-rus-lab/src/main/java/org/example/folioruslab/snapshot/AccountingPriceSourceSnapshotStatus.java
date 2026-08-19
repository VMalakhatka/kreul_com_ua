package org.example.folioruslab.snapshot;

import java.time.LocalDateTime;
import java.util.List;

public record AccountingPriceSourceSnapshotStatus(
        boolean ok,
        boolean accepted,
        boolean running,
        String jobId,
        String status,
        Integer warehouseId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String snapshotVersion,
        String warehouseDigest,
        String previousWarehouseDigest,
        int skuCount,
        long movementCount,
        long priceRuleCount,
        long ignoredOrphanMovementCount,
        long ignoredOrphanPriceRuleCount,
        boolean comparedToPrevious,
        int unchangedSkuCount,
        int dirtySkuCount,
        int newSkuCount,
        int removedSkuCount,
        boolean changedSkusTruncated,
        List<String> changedSkus,
        String error
) {
    public AccountingPriceSourceSnapshotStatus {
        changedSkus = changedSkus == null ? List.of() : List.copyOf(changedSkus);
    }

    public static AccountingPriceSourceSnapshotStatus idle() {
        return new AccountingPriceSourceSnapshotStatus(
                true, false, false, null, "IDLE", null,
                null, null, AccountingPriceSourceSnapshot.SNAPSHOT_VERSION,
                null, null, 0, 0, 0, 0, 0, false,
                0, 0, 0, 0, false, List.of(), null
        );
    }
}
