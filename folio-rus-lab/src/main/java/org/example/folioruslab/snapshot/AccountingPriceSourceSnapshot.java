package org.example.folioruslab.snapshot;

import java.util.Map;

record AccountingPriceSourceSnapshot(
        int warehouseId,
        String warehouseDigest,
        Map<String, String> skuDigests,
        long movementCount,
        long priceRuleCount,
        long ignoredOrphanMovementCount,
        long ignoredOrphanPriceRuleCount
) {
    static final String SNAPSHOT_VERSION = "folio-accounting-price-source/v1";

    AccountingPriceSourceSnapshot {
        skuDigests = Map.copyOf(skuDigests);
    }
}
