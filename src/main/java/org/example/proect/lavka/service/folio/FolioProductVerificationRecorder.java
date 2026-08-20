package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;

import java.util.Optional;

/**
 * Bridges a confirmed Folio recalculation commit to the MariaDB product
 * snapshot. Capture happens before MSSQL commit; publication happens only
 * after the transaction manager has returned successfully.
 */
public interface FolioProductVerificationRecorder {

    FolioProductVerificationRecorder NOOP = new FolioProductVerificationRecorder() {
        @Override
        public Optional<ProductFingerprint> capture(int warehouseId, String sku,
                                                    int queryTimeoutSeconds) {
            return Optional.empty();
        }

        @Override
        public boolean confirmApplied(ProductFingerprint fingerprint) {
            return true;
        }

        @Override
        public void markFailed(String sourceDatabase, int warehouseId,
                               String sku, String error) {
        }
    };

    Optional<ProductFingerprint> capture(int warehouseId, String sku,
                                         int queryTimeoutSeconds);

    boolean confirmApplied(ProductFingerprint fingerprint);

    void markFailed(String sourceDatabase, int warehouseId, String sku, String error);
}
