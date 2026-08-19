package org.example.folioruslab.snapshot;

public interface AccountingPriceSourceSnapshotGateway {

    AccountingPriceSourceSnapshot capture(int warehouseId);
}
