package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Set-based capture used by SAFE_APPLY_ONLY after successful SKU commits. */
    default List<ProductFingerprint> captureBatch(int warehouseId,
                                                  List<String> skus,
                                                  int queryTimeoutSeconds) {
        List<ProductFingerprint> result = new ArrayList<>();
        for (String sku : skus) {
            capture(warehouseId, sku, queryTimeoutSeconds).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /** Returns SKU values whose active MariaDB snapshot row was updated. */
    default Set<String> confirmAppliedBatch(List<ProductFingerprint> fingerprints) {
        Set<String> confirmed = new LinkedHashSet<>();
        for (ProductFingerprint fingerprint : fingerprints) {
            if (confirmApplied(fingerprint)) confirmed.add(fingerprint.sku());
        }
        return Set.copyOf(confirmed);
    }

    void markFailed(String sourceDatabase, int warehouseId, String sku, String error);

    default void recordSkuFailureDiagnostic(String sourceDatabase, int warehouseId, String sku,
            String jobId, boolean previewOnly,
            org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse.Issue issue) {
        if (!previewOnly) markFailed(sourceDatabase, warehouseId, sku,
                issue.code() + ": " + issue.message() + "; jobId=" + jobId);
    }
}
