package org.example.folioruslab.snapshot;

import org.example.folioruslab.sql.LabBusyException;
import org.example.folioruslab.sql.LabOperationGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountingPriceSourceSnapshotServiceTest {

    private AccountingPriceSourceSnapshotService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void createsBaselineThenDetectsDirtyNewAndRemovedSkus() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.results.add(snapshot(
                "whole-1", Map.of("A", "a1", "B", "b1", "D", "d1"), 10, 2
        ));
        gateway.results.add(snapshot(
                "whole-2", Map.of("A", "a1", "B", "b2", "C", "c1"), 12, 3
        ));
        service = service(gateway, new LabOperationGate());

        service.start(new AccountingPriceSourceSnapshotRequest(12));
        AccountingPriceSourceSnapshotStatus baseline = awaitFinished();
        assertEquals("BASELINE_CREATED", baseline.status());
        assertFalse(baseline.comparedToPrevious());
        assertEquals(3, baseline.skuCount());

        service.start(new AccountingPriceSourceSnapshotRequest(12));
        AccountingPriceSourceSnapshotStatus compared = awaitFinished();
        assertEquals("CHANGES_DETECTED", compared.status());
        assertTrue(compared.comparedToPrevious());
        assertEquals(1, compared.unchangedSkuCount());
        assertEquals(1, compared.dirtySkuCount());
        assertEquals(1, compared.newSkuCount());
        assertEquals(1, compared.removedSkuCount());
        assertEquals(java.util.List.of("B", "C", "D"), compared.changedSkus());
        assertEquals("whole-1", compared.previousWarehouseDigest());
        assertEquals("whole-2", compared.warehouseDigest());

        gateway.results.add(snapshot(
                "whole-2", Map.of("A", "a1", "B", "b2", "C", "c1"), 12, 3
        ));
        service.start(new AccountingPriceSourceSnapshotRequest(12));
        AccountingPriceSourceSnapshotStatus repeated = awaitFinished();
        assertEquals("CHANGES_DETECTED", repeated.status());
        assertEquals("whole-1", repeated.previousWarehouseDigest());
        assertEquals(1, repeated.dirtySkuCount());
        assertEquals(1, repeated.newSkuCount());
        assertEquals(1, repeated.removedSkuCount());
    }

    @Test
    void reportsUnchangedWhenExactSnapshotIsStable() throws Exception {
        FakeGateway gateway = new FakeGateway();
        AccountingPriceSourceSnapshot same = snapshot(
                "whole", Map.of("A", "a", "B", "b"), 4, 1
        );
        gateway.results.add(same);
        gateway.results.add(same);
        service = service(gateway, new LabOperationGate());

        service.start(new AccountingPriceSourceSnapshotRequest(23));
        awaitFinished();
        service.start(new AccountingPriceSourceSnapshotRequest(23));
        AccountingPriceSourceSnapshotStatus compared = awaitFinished();

        assertEquals("UNCHANGED", compared.status());
        assertEquals(2, compared.unchangedSkuCount());
        assertEquals(0, compared.dirtySkuCount());
        assertTrue(compared.changedSkus().isEmpty());
    }

    @Test
    void doesNotReplaceSuccessfulBaselineWhenCaptureFails() throws Exception {
        FakeGateway gateway = new FakeGateway();
        gateway.results.add(snapshot("whole", Map.of("A", "a"), 1, 0));
        service = service(gateway, new LabOperationGate());

        service.start(new AccountingPriceSourceSnapshotRequest(12));
        awaitFinished();
        gateway.failure = new IllegalStateException("synthetic snapshot failure");
        service.start(new AccountingPriceSourceSnapshotRequest(12));
        AccountingPriceSourceSnapshotStatus failed = awaitFinished();

        assertEquals("FAILED", failed.status());
        assertEquals("synthetic snapshot failure", failed.error());

        gateway.failure = null;
        gateway.results.add(snapshot("whole", Map.of("A", "a"), 1, 0));
        service.start(new AccountingPriceSourceSnapshotRequest(12));
        AccountingPriceSourceSnapshotStatus recovered = awaitFinished();
        assertEquals("UNCHANGED", recovered.status());
    }

    @Test
    void rejectsConcurrentLaboratoryOperation() {
        LabOperationGate gate = new LabOperationGate();
        assertTrue(gate.tryAcquire());
        service = service(new FakeGateway(), gate);

        assertThrows(
                LabBusyException.class,
                () -> service.start(new AccountingPriceSourceSnapshotRequest(12))
        );
        gate.release();
    }

    private AccountingPriceSourceSnapshotStatus awaitFinished() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            AccountingPriceSourceSnapshotStatus current = service.status();
            if (!current.running()) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Snapshot did not finish in time");
    }

    private static AccountingPriceSourceSnapshotService service(
            AccountingPriceSourceSnapshotGateway gateway,
            LabOperationGate gate
    ) {
        return new AccountingPriceSourceSnapshotService(
                gateway,
                gate,
                Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static AccountingPriceSourceSnapshot snapshot(
            String digest,
            Map<String, String> skus,
            long movements,
            long priceRules
    ) {
        return new AccountingPriceSourceSnapshot(
                12, digest, skus, movements, priceRules, 0, 0
        );
    }

    private static final class FakeGateway implements AccountingPriceSourceSnapshotGateway {
        private final ArrayDeque<AccountingPriceSourceSnapshot> results = new ArrayDeque<>();
        private RuntimeException failure;

        @Override
        public AccountingPriceSourceSnapshot capture(int warehouseId) {
            if (failure != null) {
                throw failure;
            }
            return results.removeFirst();
        }
    }
}
