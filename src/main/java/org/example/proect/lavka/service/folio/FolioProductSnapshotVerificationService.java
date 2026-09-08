package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    public List<ProductFingerprint> captureBatch(int warehouseId,
                                                 List<String> skus,
                                                 int queryTimeoutSeconds) {
        return sourceDao.captureProductFingerprints(
                warehouseId, skus, queryTimeoutSeconds);
    }

    @Override
    public Set<String> confirmAppliedBatch(List<ProductFingerprint> fingerprints) {
        if (fingerprints.isEmpty()) return Set.of();
        int[] updated = snapshotDao.confirmAppliedBatch(
                fingerprints, LocalDateTime.now(clock));
        Set<String> confirmed = new LinkedHashSet<>();
        for (int index = 0; index < fingerprints.size(); index++) {
            ProductFingerprint fingerprint = fingerprints.get(index);
            int count = index < updated.length ? updated[index] : 0;
            if (count > 0) {
                confirmed.add(fingerprint.sku());
            } else {
                log.warn("[folio.product.snapshot] applied_digest_not_recorded db={} warehouse={} sku={} updated={}",
                        fingerprint.sourceDatabase(), fingerprint.warehouseId(),
                        fingerprint.sku(), count);
            }
        }
        log.info("[folio.product.snapshot] applied_digest_batch_recorded db={} warehouse={} requested={} confirmed={}",
                fingerprints.get(0).sourceDatabase(),
                fingerprints.get(0).warehouseId(), fingerprints.size(), confirmed.size());
        return Set.copyOf(confirmed);
    }

    @Override
    public void markFailed(String sourceDatabase, int warehouseId,
                           String sku, String error) {
        int updated = snapshotDao.markRecalculationFailed(
                sourceDatabase, warehouseId, sku, error);
        log.warn("[folio.product.snapshot] recalculation_failed db={} warehouse={} sku={} recorded={}",
                sourceDatabase, warehouseId, sku, updated == 1);
    }

    @Override
    public void recordSkuFailureDiagnostic(String sourceDatabase, int warehouseId, String sku,
            String jobId, boolean previewOnly,
            org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse.Issue issue) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(issue.details());
            snapshotDao.recordSkuFailureDiagnostic(sourceDatabase, warehouseId, sku, jobId, previewOnly,
                    issue.code(), issue.message(), json, LocalDateTime.now(clock));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize SKU failure diagnostic", error);
        }
    }
}
