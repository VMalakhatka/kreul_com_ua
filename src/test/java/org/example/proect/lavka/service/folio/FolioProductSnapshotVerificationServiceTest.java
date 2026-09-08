package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioProductSnapshotVerificationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void serializesCompleteNegativeDiagnosticIncludingZeroAndUnknownValues() throws Exception {
        var sourceDao = mock(FolioProductSnapshotSourceDao.class);
        var snapshotDao = mock(FolioProductSnapshotDao.class);
        var service = new FolioProductSnapshotVerificationService(sourceDao, snapshotDao, CLOCK);
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("quantityBefore", java.math.BigDecimal.ZERO);
        details.put("quantityAfter", java.math.BigDecimal.ONE.negate());
        details.put("documentNumber", null);
        details.put("documentDate", LocalDateTime.of(2026, 9, 7, 0, 0));
        var issue = new org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse.Issue(
                "NEGATIVE_CHRONOLOGICAL_STOCK", "negative stock", details);
        service.recordSkuFailureDiagnostic("Paint_Ua", 5, "SKU", "job", true, issue);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(snapshotDao).recordSkuFailureDiagnostic(org.mockito.ArgumentMatchers.eq("Paint_Ua"),
                org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.eq("SKU"),
                org.mockito.ArgumentMatchers.eq("job"), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(issue.code()), org.mockito.ArgumentMatchers.eq(issue.message()),
                json.capture(), org.mockito.ArgumentMatchers.eq(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json.getValue());
        assertThat(tree.has("quantityBefore")).isTrue();
        assertThat(tree.get("quantityBefore").decimalValue()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(tree.has("documentNumber")).isTrue();
        assertThat(tree.get("documentNumber").isNull()).isTrue();
        assertThat(tree.get("documentDate").asText()).isEqualTo("2026-09-07T00:00:00");
    }

    @Test
    void capturesExactFingerprintAndPublishesItAfterCommit() {
        FolioProductSnapshotSourceDao sourceDao =
                mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshotDao = mock(FolioProductSnapshotDao.class);
        ProductFingerprint fingerprint = new ProductFingerprint(
                "Paint_Ua", 5, "KR-84127", "digest", "Product",
                48, 1L, 48L, null, null, 0);
        when(sourceDao.captureProductFingerprint(5, "KR-84127", 120))
                .thenReturn(fingerprint);
        when(snapshotDao.confirmApplied(
                "Paint_Ua", 5, "KR-84127", "digest",
                LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)))
                .thenReturn(1);
        var service = new FolioProductSnapshotVerificationService(
                sourceDao, snapshotDao, CLOCK);

        ProductFingerprint captured = service.capture(5, "KR-84127", 120)
                .orElseThrow();
        boolean confirmed = service.confirmApplied(captured);

        assertThat(confirmed).isTrue();
        verify(snapshotDao).confirmApplied(
                "Paint_Ua", 5, "KR-84127", "digest",
                LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
    }

    @Test
    void reportsMissingSnapshotRowWithoutChangingFolio() {
        FolioProductSnapshotSourceDao sourceDao =
                mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshotDao = mock(FolioProductSnapshotDao.class);
        ProductFingerprint fingerprint = new ProductFingerprint(
                "Paint_Ua", 5, "NEW", "digest", "New",
                0, null, null, null, null, 0);
        when(snapshotDao.confirmApplied(
                "Paint_Ua", 5, "NEW", "digest",
                LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)))
                .thenReturn(0);
        var service = new FolioProductSnapshotVerificationService(
                sourceDao, snapshotDao, CLOCK);

        assertThat(service.confirmApplied(fingerprint)).isFalse();
    }

    @Test
    void capturesAndPublishesFingerprintsAsOneBatch() {
        FolioProductSnapshotSourceDao sourceDao =
                mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshotDao = mock(FolioProductSnapshotDao.class);
        ProductFingerprint first = new ProductFingerprint(
                "Paint_Ua", 5, "A", "digest-a", "A",
                1, 1L, 1L, null, null, 0);
        ProductFingerprint second = new ProductFingerprint(
                "Paint_Ua", 5, "B", "digest-b", "B",
                1, 2L, 2L, null, null, 0);
        List<ProductFingerprint> fingerprints = List.of(first, second);
        LocalDateTime appliedAt = LocalDateTime.ofInstant(
                CLOCK.instant(), ZoneOffset.UTC);
        when(sourceDao.captureProductFingerprints(5, List.of("A", "B"), 120))
                .thenReturn(fingerprints);
        when(snapshotDao.confirmAppliedBatch(fingerprints, appliedAt))
                .thenReturn(new int[]{1, 1});
        var service = new FolioProductSnapshotVerificationService(
                sourceDao, snapshotDao, CLOCK);

        List<ProductFingerprint> captured = service.captureBatch(
                5, List.of("A", "B"), 120);
        Set<String> confirmed = service.confirmAppliedBatch(captured);

        assertThat(confirmed).containsExactlyInAnyOrder("A", "B");
        verify(snapshotDao).confirmAppliedBatch(fingerprints, appliedAt);
    }
}
