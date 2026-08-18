package org.example.folioruslab.accounting;

import org.example.folioruslab.sql.LabBusyException;
import org.example.folioruslab.sql.LabOperationGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeAccountingPricePreviewServiceTest {

    private SafeAccountingPricePreviewService service;

    @AfterEach
    void closeService() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void continuesAfterSafeProcedureProblemAndCollectsDiagnostics() throws Exception {
        SafeAccountingPriceProblem zero = new SafeAccountingPriceProblem(
                "ZERO_ACCOUNTING_DENOMINATOR", "zero", "B", "C", 42,
                "2026-08-17T00:00", "AVERAGE_RECEIPT", 100.0, 0.0,
                0.0, 0.0, null
        );
        SafeAccountingPriceProblem negative = new SafeAccountingPriceProblem(
                "NEGATIVE_CHRONOLOGICAL_STOCK", "negative", "C", null, null,
                null, null, null, null, null, null, "17.08.2026"
        );
        FakeGateway gateway = new FakeGateway(List.of("A", "B", "C"));
        gateway.results = List.of(
                new SafeAccountingPriceGateway.SkuPreview("A", "B", 0, null, null),
                new SafeAccountingPriceGateway.SkuPreview("B", "C", 20, null, zero),
                new SafeAccountingPriceGateway.SkuPreview(
                        "C", null, 0, "17.08.2026", negative
                )
        );
        service = service(gateway, new LabOperationGate());

        SafeAccountingPricePreviewStatus accepted = service.start(
                new SafeAccountingPricePreviewRequest(12)
        );
        SafeAccountingPricePreviewStatus finished = awaitFinished();

        assertTrue(accepted.accepted());
        assertEquals("COMPLETED_WITH_WARNINGS", finished.status());
        assertEquals(3, finished.processedProducts());
        assertEquals(1, finished.cleanProducts());
        assertEquals(2, finished.problemProducts());
        assertEquals(1, finished.negativeStockProducts());
        assertEquals(List.of("A", "B", "C"), gateway.previewedSkus);
        assertEquals(List.of(zero, negative), finished.problems());
        assertFalse(finished.problemsTruncated());
    }

    @Test
    void failureStopsTheRunAndReleasesTheSharedGate() throws Exception {
        FakeGateway gateway = new FakeGateway(List.of("A"));
        gateway.failure = new SafeAccountingPriceException("synthetic failure");
        LabOperationGate gate = new LabOperationGate();
        service = service(gateway, gate);

        service.start(new SafeAccountingPricePreviewRequest(12));
        SafeAccountingPricePreviewStatus failed = awaitFinished();

        assertEquals("FAILED", failed.status());
        assertEquals("synthetic failure", failed.error());
        assertTrue(gate.tryAcquire());
        gate.release();
    }

    @Test
    void rejectsStartWhenAnotherLaboratoryOperationOwnsTheGate() {
        LabOperationGate gate = new LabOperationGate();
        assertTrue(gate.tryAcquire());
        service = service(new FakeGateway(List.of()), gate);

        assertThrows(
                LabBusyException.class,
                () -> service.start(new SafeAccountingPricePreviewRequest(12))
        );
        gate.release();
    }

    private SafeAccountingPricePreviewStatus awaitFinished() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            SafeAccountingPricePreviewStatus current = service.status();
            if (!current.running()) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Preview did not finish in time");
    }

    private static SafeAccountingPricePreviewService service(
            SafeAccountingPriceGateway gateway,
            LabOperationGate gate
    ) {
        return new SafeAccountingPricePreviewService(
                gateway,
                gate,
                Clock.fixed(Instant.parse("2026-08-17T21:30:00Z"), ZoneOffset.UTC)
        );
    }

    private static final class FakeGateway implements SafeAccountingPriceGateway {
        private final List<String> skus;
        private final java.util.ArrayList<String> previewedSkus = new java.util.ArrayList<>();
        private List<SkuPreview> results = List.of();
        private RuntimeException failure;

        private FakeGateway(List<String> skus) {
            this.skus = skus;
        }

        @Override
        public PreviewSession open(int warehouseId) {
            PreviewScope scope = new PreviewScope(warehouseId, skus);
            return new PreviewSession() {
                @Override
                public PreviewScope scope() {
                    return scope;
                }

                @Override
                public SkuPreview previewOne(String sku) {
                    previewedSkus.add(sku);
                    if (failure != null) {
                        throw failure;
                    }
                    return results.get(previewedSkus.size() - 1);
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
