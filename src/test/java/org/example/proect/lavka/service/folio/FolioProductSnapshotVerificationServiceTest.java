package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioProductSnapshotVerificationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);

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
}
