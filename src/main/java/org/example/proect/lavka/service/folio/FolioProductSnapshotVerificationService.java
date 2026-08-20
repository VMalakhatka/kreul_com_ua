package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class FolioProductSnapshotVerificationService
        implements FolioProductVerificationRecorder {

    private final FolioProductSnapshotSourceDao sourceDao;
    private final FolioProductSnapshotDao snapshotDao;
    private final Clock clock;

    public FolioProductSnapshotVerificationService(
            FolioProductSnapshotSourceDao sourceDao,
            FolioProductSnapshotDao snapshotDao,
            @Qualifier("folioBalanceClock") Clock clock) {
        this.sourceDao = sourceDao;
        this.snapshotDao = snapshotDao;
        this.clock = clock;
    }

    @Override
    public Optional<ProductFingerprint> capture(int warehouseId, String sku,
                                                int queryTimeoutSeconds) {
        return Optional.of(sourceDao.captureProductFingerprint(
                warehouseId, sku, queryTimeoutSeconds));
    }

    @Override
    public boolean confirmApplied(ProductFingerprint fingerprint) {
        int updated = snapshotDao.confirmApplied(
                fingerprint.sourceDatabase(), fingerprint.warehouseId(),
                fingerprint.sku(), fingerprint.sourceDigest(),
                LocalDateTime.now(clock));
        if (updated != 1) {
            log.warn("[folio.product.snapshot] applied_digest_not_recorded db={} warehouse={} sku={} updated={}",
                    fingerprint.sourceDatabase(), fingerprint.warehouseId(),
                    fingerprint.sku(), updated);
            return false;
        }
        log.info("[folio.product.snapshot] applied_digest_recorded db={} warehouse={} sku={}",
                fingerprint.sourceDatabase(), fingerprint.warehouseId(), fingerprint.sku());
        return true;
    }

    @Override
    public void markFailed(String sourceDatabase, int warehouseId,
                           String sku, String error) {
        int updated = snapshotDao.markRecalculationFailed(
                sourceDatabase, warehouseId, sku, error);
        log.warn("[folio.product.snapshot] recalculation_failed db={} warehouse={} sku={} recorded={}",
                sourceDatabase, warehouseId, sku, updated == 1);
    }
}
