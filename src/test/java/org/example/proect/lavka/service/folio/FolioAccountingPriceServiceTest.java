package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.ArticleRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementTotals;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeChronologyProblem;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeFullChunkOutput;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeInvariantDigest;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeProtectedSnapshot;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeSkuProtectedState;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseScope;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceNativeFullRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioAccountingPriceServiceTest {

    private static final int WAREHOUSE_ID = 12;
    private static final String CLEAN_SKU = "CLEAN";
    private static final String NEGATIVE_SKU = "NEGATIVE";
    private static final TaskExecutor DIRECT_EXECUTOR = Runnable::run;
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void previewInspectsProductWithoutCallingFolioRebuild() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubWarehouse(dao);
        stubProduct(dao, CLEAN_SKU, article(CLEAN_SKU, "800"), List.of());

        var response = service(dao, false, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(CLEAN_SKU, WAREHOUSE_ID, true));

        assertThat(response.ok()).isTrue();
        assertThat(response.previewOnly()).isTrue();
        assertThat(response.status()).isEqualTo("PREVIEW_READY");
        assertThat(response.eligibleToApply()).isTrue();
        assertThat(response.procedureExecuted()).isFalse();
        verify(dao, never()).acquireRecalculationMutex(anyInt());
        verify(dao, never()).rebuildOne(anyString(), anyInt(), anyInt());
    }

    @Test
    void legacyScratchRowsDoNotBlockVerifiedAveragePricePreview() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubWarehouse(dao);
        stubProduct(dao, CLEAN_SKU, article(CLEAN_SKU, "800"), List.of());
        var response = service(dao, false, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(CLEAN_SKU, WAREHOUSE_ID, true));

        assertThat(response.status()).isEqualTo("PREVIEW_READY");
        assertThat(response.eligibleToApply()).isTrue();
        assertThat(response.errors()).isEmpty();
        verify(dao, never()).rebuildOne(anyString(), anyInt(), anyInt());
    }

    @Test
    void applyRebuildsCleanProductAndReturnsRecalculatedState() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubWarehouse(dao);
        ArticleRow before = article(CLEAN_SKU, "900");
        ArticleRow after = article(CLEAN_SKU, "800");
        when(dao.findArticles(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(true)))
                .thenReturn(List.of(before));
        when(dao.findArticles(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(false)))
                .thenReturn(List.of(after));
        when(dao.findChronologicalMovements(
                eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(true)))
                .thenReturn(List.of());
        when(dao.findMovementTotals(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID))))
                .thenReturn(Map.of(WAREHOUSE_ID, totals()));
        var response = service(dao, true, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(CLEAN_SKU, WAREHOUSE_ID, false));

        assertThat(response.ok()).isTrue();
        assertThat(response.previewOnly()).isFalse();
        assertThat(response.status()).isEqualTo("RECALCULATED");
        assertThat(response.procedureExecuted()).isTrue();
        assertThat(response.priceChanged()).isTrue();
        assertThat(response.before()).singleElement()
                .extracting(state -> state.accountingPrice())
                .isEqualTo(new BigDecimal("900"));
        assertThat(response.after()).singleElement()
                .extracting(state -> state.accountingPrice())
                .isEqualTo(new BigDecimal("800"));
        verify(dao).acquireRecalculationMutex(5_000);
        verify(dao).rebuildOne(CLEAN_SKU, WAREHOUSE_ID, 120);
    }

    @Test
    void negativeChronologicalStockBlocksRebuild() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubWarehouse(dao);
        stubProduct(dao, NEGATIVE_SKU, article(NEGATIVE_SKU, "800"), List.of(
                expense(1001L, "11")
        ));

        var response = service(dao, true, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(
                        NEGATIVE_SKU, WAREHOUSE_ID, false));

        assertThat(response.ok()).isFalse();
        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.eligibleToApply()).isFalse();
        assertThat(response.procedureExecuted()).isFalse();
        assertThat(response.warnings())
                .filteredOn(warning -> "NEGATIVE_CHRONOLOGICAL_STOCK".equals(warning.code()))
                .singleElement()
                .satisfies(warning -> {
                    assertThat(warning.details())
                            .containsEntry("warehouseId", WAREHOUSE_ID)
                            .containsEntry("initialQuantity", BigDecimal.TEN)
                            .containsEntry("quantityBefore", BigDecimal.TEN)
                            .containsEntry("quantityAfter", new BigDecimal("-1"))
                            .containsEntry("shortageQuantity", BigDecimal.ONE)
                            .containsEntry("movementPosition", 1)
                            .containsEntry("movementCount", 1);
                    assertThat(warning.details().get("operation"))
                            .isInstanceOfSatisfying(Map.class, operation -> assertThat(operation)
                                    .containsEntry("kind", "EXPENSE")
                                    .containsEntry("documentType", FolioAccountingPriceDao.TYPE_EXPENSE)
                                    .containsEntry("quantity", new BigDecimal("11"))
                                    .containsEntry("recno", 1001L)
                                    .containsEntry("documentId", new BigDecimal("700001"))
                                    .containsEntry("documentNumber", new BigDecimal("1001"))
                                    .containsEntry("documentDate", "2026-08-15T09:00:00")
                                    .containsEntry("warehouseId", WAREHOUSE_ID));
                    assertThat(warning.details().get("currentState"))
                            .isInstanceOfSatisfying(Map.class, current -> assertThat(current)
                                    .containsEntry("physicalQuantity", BigDecimal.TEN)
                                    .containsEntry("availableQuantity", BigDecimal.TEN)
                                    .containsEntry("accountingQuantity", BigDecimal.TEN)
                                    .containsEntry("accountingPrice", new BigDecimal("800")));
                });
        verify(dao, never()).rebuildOne(anyString(), anyInt(), anyInt());
    }

    @Test
    void fullApplyContinuesAfterNegativeProductAndRebuildsFollowingProduct() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubWarehouse(dao);
        when(dao.findSkus(WAREHOUSE_ID)).thenReturn(List.of(NEGATIVE_SKU, CLEAN_SKU));
        when(dao.findArticles(eq(NEGATIVE_SKU), anyList(), anyBoolean()))
                .thenReturn(List.of(article(NEGATIVE_SKU, "800")));
        when(dao.findChronologicalMovements(eq(NEGATIVE_SKU), anyList(), anyBoolean()))
                .thenReturn(List.of(expense(1001L, "11")));
        when(dao.findArticles(eq(CLEAN_SKU), anyList(), eq(true)))
                .thenReturn(List.of(article(CLEAN_SKU, "900")));
        when(dao.findArticles(eq(CLEAN_SKU), anyList(), eq(false)))
                .thenReturn(List.of(article(CLEAN_SKU, "800")));
        when(dao.findChronologicalMovements(eq(CLEAN_SKU), anyList(), anyBoolean()))
                .thenReturn(List.of());
        when(dao.findMovementTotals(anyString(), anyList()))
                .thenReturn(Map.of(WAREHOUSE_ID, totals()));

        FolioAccountingPriceService service = service(dao, true, true);
        var accepted = service.requestFull(new FolioAccountingPriceFullRecalculationRequest(
                WAREHOUSE_ID, false, true));
        var completed = service.status(false);

        assertThat(accepted.accepted()).isTrue();
        assertThat(completed.running()).isFalse();
        assertThat(completed.status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(completed.totalProducts()).isEqualTo(2);
        assertThat(completed.processedProducts()).isEqualTo(2);
        assertThat(completed.recalculatedProducts()).isEqualTo(1);
        assertThat(completed.skippedProducts()).isEqualTo(1);
        assertThat(completed.warnings())
                .anySatisfy(warning -> {
                    assertThat(warning.code()).isEqualTo("NEGATIVE_CHRONOLOGICAL_STOCK");
                    assertThat(warning.details()).containsEntry("sku", NEGATIVE_SKU);
                });
        verify(dao, never()).rebuildOne(eq(NEGATIVE_SKU), eq(WAREHOUSE_ID), anyInt());
        verify(dao).rebuildOne(CLEAN_SKU, WAREHOUSE_ID, 120);
    }

    @Test
    void corruptedAccountingAmountAfterRebuildRollsBackTransaction() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubWarehouse(dao);
        ArticleRow before = article(CLEAN_SKU, "900");
        ArticleRow corruptAfter = articleWithAccountingAmount(CLEAN_SKU, "800", "9000");
        when(dao.findArticles(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(true)))
                .thenReturn(List.of(before));
        when(dao.findArticles(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(false)))
                .thenReturn(List.of(corruptAfter));
        when(dao.findChronologicalMovements(
                eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(true)))
                .thenReturn(List.of());
        when(dao.findMovementTotals(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID))))
                .thenReturn(Map.of(WAREHOUSE_ID, totals()));
        TrackingTransactionManager transactions = new TrackingTransactionManager();

        assertThatThrownBy(() -> service(dao, transactions, true, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(
                        CLEAN_SKU, WAREHOUSE_ID, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accounting amount");

        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(dao).rebuildOne(CLEAN_SKU, WAREHOUSE_ID, 120);
    }

    @Test
    void nonNullZeroAccountingGroupIsBlockedBeforeProcedureCall() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        WarehouseRow requested = new WarehouseRow(
                WAREHOUSE_ID, "Requested", 1000, 0);
        when(dao.findWarehouseScope(WAREHOUSE_ID))
                .thenReturn(new WarehouseScope(requested, List.of(requested)));
        when(dao.findArticles(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(true)))
                .thenReturn(List.of(article(CLEAN_SKU, "800")));
        when(dao.findChronologicalMovements(
                eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID)), eq(true)))
                .thenReturn(List.of());
        when(dao.findMovementTotals(eq(CLEAN_SKU), eq(List.of(WAREHOUSE_ID))))
                .thenReturn(Map.of(WAREHOUSE_ID, totals()));

        var response = service(dao, true, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(
                        CLEAN_SKU, WAREHOUSE_ID, false));

        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.warnings())
                .extracting(warning -> warning.code())
                .contains("ACCOUNTING_GROUP_UNSUPPORTED");
        verify(dao, never()).rebuildOne(anyString(), anyInt(), anyInt());
    }

    @Test
    void databaseMutexTimeoutIsReportedAsAccountingPriceBusy() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        org.mockito.Mockito.doThrow(new CannotAcquireLockException("busy"))
                .when(dao).acquireRecalculationMutex(5_000);

        assertThatThrownBy(() -> service(dao, true, false).recalculate(
                new FolioAccountingPriceRecalculationRequest(
                        CLEAN_SKU, WAREHOUSE_ID, false)))
                .isInstanceOf(FolioAccountingPriceBusyException.class)
                .hasMessageContaining("already running");

        verify(dao, never()).rebuildOne(anyString(), anyInt(), anyInt());
    }

    @Test
    void pointApplyRequiresApplyFeatureFlag() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);

        assertThatThrownBy(() -> service(dao, false, true).recalculate(
                new FolioAccountingPriceRecalculationRequest(
                        CLEAN_SKU, WAREHOUSE_ID, false)))
                .isInstanceOf(FolioAccountingPriceDisabledException.class)
                .hasMessageContaining("apply is disabled");

        verify(dao, never()).findWarehouseScope(anyInt());
        verify(dao, never()).rebuildOne(anyString(), anyInt(), anyInt());
    }

    @Test
    void fullApplyRequiresDedicatedFullFeatureFlag() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);

        assertThatThrownBy(() -> service(dao, true, false).requestFull(
                new FolioAccountingPriceFullRecalculationRequest(
                        WAREHOUSE_ID, false, true)))
                .isInstanceOf(FolioAccountingPriceDisabledException.class)
                .hasMessageContaining("Full Folio accounting-price apply is disabled");

        verify(dao, never()).findWarehouseScope(anyInt());
        verify(dao, never()).findSkus(anyInt());
    }

    @Test
    void nativePreviewRunsExactProcedureAndRollsBackEveryChunk() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(CLEAN_SKU, 100, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        var accepted = service.requestNativeFull(
                new FolioAccountingPriceNativeFullRequest(
                        WAREHOUSE_ID, true, false));
        var completed = service.nativeFullStatus(false);

        assertThat(accepted.accepted()).isTrue();
        assertThat(completed.status()).isEqualTo("PREVIEW_READY");
        assertThat(completed.running()).isFalse();
        assertThat(completed.procedureCalls()).isEqualTo(1);
        assertThat(completed.preflightChunks()).isEqualTo(1);
        assertThat(completed.committedChunks()).isZero();
        assertThat(completed.progressUnits()).isEqualTo(100);
        assertThat(completed.progressPercent()).isEqualTo(100);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void nativeFullUsesDedicatedLongTimeoutForDiagnosticsAndProcedureChunks() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.findNativeChronologyProblems(WAREHOUSE_ID, 900)).thenReturn(List.of());
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(900)))
                .thenReturn(nativeChunk(CLEAN_SKU, 100, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = new FolioAccountingPriceService(
                dao, DIRECT_EXECUTOR, CLOCK, transactions,
                true, true, true, true, false,
                Set.of("Paint_Rus"), 100, 5_000, 120, 900, 20);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));

        assertThat(service.nativeFullStatus(false).status()).isEqualTo("PREVIEW_READY");
        verify(dao).findNativeChronologyProblems(WAREHOUSE_ID, 900);
        verify(dao).callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(900));
        assertThat(transactions.timeouts).contains(900);
    }

    @Test
    void nativePreviewQuarantinesKnownProblemAndCompletesWithWarnings() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.findNativeChronologyProblems(WAREHOUSE_ID, 120))
                .thenReturn(List.of(nativeProblem(
                        "ZERO_ACCOUNTING_QUANTITY_DENOMINATOR", NEGATIVE_SKU,
                        FolioAccountingPriceDao.TYPE_RECEIPT, "-10", "10", "0")));
        when(dao.findUnusedProductTypeMarker()).thenReturn("9");
        when(dao.quarantineNativeSkus(
                WAREHOUSE_ID, Set.of(NEGATIVE_SKU), "9"))
                .thenReturn(Map.of(NEGATIVE_SKU, "1"));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(CLEAN_SKU, 100, 100, null, null));

        FolioAccountingPriceService service = nativeService(
                dao, new TrackingTransactionManager(), false);
        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var completed = service.nativeFullStatus(false);

        assertThat(completed.status()).isEqualTo("PREVIEW_READY_WITH_WARNINGS");
        assertThat(completed.committedChunks()).isZero();
        assertThat(completed.warnings())
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.code())
                            .isEqualTo("ZERO_ACCOUNTING_QUANTITY_DENOMINATOR");
                    assertThat(issue.details())
                            .containsEntry("sku", NEGATIVE_SKU)
                            .containsEntry("skipped", true)
                            .containsEntry("denominator", BigDecimal.ZERO);
                });
        verify(dao).quarantineNativeSkus(
                WAREHOUSE_ID, Set.of(NEGATIVE_SKU), "9");
        verify(dao).createNativeQuarantineType("9");
        verify(dao).restoreNativeSkus(
                WAREHOUSE_ID, Map.of(NEGATIVE_SKU, "1"));
        verify(dao).deleteNativeQuarantineType("9");
    }

    @Test
    void nativeApplySkipsKnownNegativeSkuAndCommitsOtherProducts() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.findNativeChronologyProblems(WAREHOUSE_ID, 120))
                .thenReturn(List.of(nativeProblem(
                        "NEGATIVE_CHRONOLOGICAL_STOCK", NEGATIVE_SKU,
                        FolioAccountingPriceDao.TYPE_EXPENSE, "11", "10", "-1")));
        when(dao.findUnusedProductTypeMarker()).thenReturn("9");
        when(dao.quarantineNativeSkus(
                WAREHOUSE_ID, Set.of(NEGATIVE_SKU), "9"))
                .thenReturn(Map.of(NEGATIVE_SKU, "1"));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(CLEAN_SKU, 100, 100, null, null));

        FolioAccountingPriceService service = nativeService(
                dao, new TrackingTransactionManager(), true);
        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, false, true));
        var completed = service.nativeFullStatus(false);

        assertThat(completed.status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(completed.committedChunks()).isEqualTo(1);
        assertThat(completed.warningCount()).isEqualTo(1);
        verify(dao, times(2)).quarantineNativeSkus(
                WAREHOUSE_ID, Set.of(NEGATIVE_SKU), "9");
        verify(dao, times(2)).createNativeQuarantineType("9");
        verify(dao, times(2)).restoreNativeSkus(
                WAREHOUSE_ID, Map.of(NEGATIVE_SKU, "1"));
        verify(dao, times(2)).deleteNativeQuarantineType("9");
    }

    @Test
    void nativeProcedureFailureRestoresTemporaryQuarantineBeforeRollback() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.findNativeChronologyProblems(WAREHOUSE_ID, 120))
                .thenReturn(List.of(nativeProblem(
                        "NEGATIVE_CHRONOLOGICAL_STOCK", NEGATIVE_SKU,
                        FolioAccountingPriceDao.TYPE_EXPENSE, "11", "10", "-1")));
        when(dao.findUnusedProductTypeMarker()).thenReturn("9");
        when(dao.quarantineNativeSkus(
                WAREHOUSE_ID, Set.of(NEGATIVE_SKU), "9"))
                .thenReturn(Map.of(NEGATIVE_SKU, "1"));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenThrow(new IllegalStateException("legacy procedure failed"));

        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(
                dao, transactions, false);
        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));

        assertThat(service.nativeFullStatus(false).status()).isEqualTo("FAILED");
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(dao).restoreNativeSkus(
                WAREHOUSE_ID, Map.of(NEGATIVE_SKU, "1"));
        verify(dao).deleteNativeQuarantineType("9");
    }

    @Test
    void nativeApplyPerformsRollbackPreflightBeforeCommittingCleanPass() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(CLEAN_SKU, 100, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, true);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, false, true));
        var completed = service.nativeFullStatus(false);

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.procedureCalls()).isEqualTo(2);
        assertThat(completed.preflightChunks()).isEqualTo(1);
        assertThat(completed.committedChunks()).isEqualTo(1);
        assertThat(completed.lastCommittedArt()).isEqualTo(CLEAN_SKU);
        assertThat(transactions.rollbacks).isEqualTo(3);
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    void nativeApplyAllowsDifferentTimeBasedChunkBoundaries() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.findProcessedRangeEnd(WAREHOUSE_ID, null)).thenReturn("Z");
        NativeProtectedSnapshot completeScope = protectedSnapshot(
                List.of("A", "B", "Z"), "article-sha256");
        when(dao.captureNativeProtectedSnapshot(
                WAREHOUSE_ID, null, null)).thenReturn(completeScope);
        when(dao.captureNativeProtectedSnapshot(
                WAREHOUSE_ID, null, "Z")).thenReturn(completeScope);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(
                        nativeChunk("A", 40, 100, "B", null),
                        nativeChunk("Z", 100, 100, null, null));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq("B"), eq(0), eq(100), eq(120)))
                .thenReturn(nativeChunk("Z", 60, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, true);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, false, true));
        var completed = service.nativeFullStatus(false);

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.preflightChunks()).isEqualTo(2);
        assertThat(completed.committedChunks()).isEqualTo(1);
        assertThat(completed.progressUnits()).isEqualTo(100);
        assertThat(transactions.rollbacks).isEqualTo(4);
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    void nativeApplyIsBlockedWhenRollbackPreflightFindsNegativeStock() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        stubProduct(dao, NEGATIVE_SKU, article(NEGATIVE_SKU, "800"), List.of(
                expense(1001L, "11")
        ));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(
                        NEGATIVE_SKU, 40, 100, CLEAN_SKU, "15.08.2026"));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(CLEAN_SKU), eq(0), eq(100), eq(120)))
                .thenReturn(nativeChunk(CLEAN_SKU, 60, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, true);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, false, true));
        var blocked = service.nativeFullStatus(false);

        assertThat(blocked.status()).isEqualTo("BLOCKED_NEGATIVE_STOCK");
        assertThat(blocked.committedChunks()).isZero();
        assertThat(blocked.preflightChunks()).isEqualTo(2);
        assertThat(blocked.warnings())
                .filteredOn(warning -> "NEGATIVE_CHRONOLOGICAL_STOCK".equals(warning.code()))
                .singleElement()
                .satisfies(warning -> assertThat(warning.details())
                        .containsEntry("folioProblemDate", "15.08.2026")
                        .containsEntry("procedureArt", NEGATIVE_SKU)
                        .containsEntry("nextArt", CLEAN_SKU));
        assertThat(transactions.rollbacks).isEqualTo(2);
        // The additional commit is the read-only diagnostic transaction that
        // builds operation-before/after details after the native rollback.
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    void nativeApplyRequiresExplicitConfirmation() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        FolioAccountingPriceService service = nativeService(
                dao, new TrackingTransactionManager(), true);

        assertThatThrownBy(() -> service.requestNativeFull(
                new FolioAccountingPriceNativeFullRequest(
                        WAREHOUSE_ID, false, false)))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("confirmApply=true");

        verify(dao, never()).currentDatabaseName();
    }

    @Test
    void nativeTransactionBoundaryChangeProducesUnknownOutcome() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(new NativeFullChunkOutput(
                        0, CLEAN_SKU, 100, 100, null, null, 1, 0, 0));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(failed.error()).contains("transaction boundary");
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void nativeApplyAlsoRequiresGlobalApplyKillSwitch() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        FolioAccountingPriceService service = nativeService(
                dao, new TrackingTransactionManager(), false, true);

        assertThatThrownBy(() -> service.requestNativeFull(
                new FolioAccountingPriceNativeFullRequest(
                        WAREHOUSE_ID, false, true)))
                .isInstanceOf(FolioAccountingPriceDisabledException.class)
                .hasMessageContaining("apply is disabled");

        verify(dao, never()).currentDatabaseName();
    }

    @Test
    void invalidNativeReturnCodeRollsBackApplyChunkBeforeCommit() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        NativeFullChunkOutput clean = nativeChunk(
                CLEAN_SKU, 100, 100, null, null);
        NativeFullChunkOutput invalid = new NativeFullChunkOutput(
                10, CLEAN_SKU, 100, 100, null, null, 1, 1, 0);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(clean, invalid);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, true);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, false, true));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.committedChunks()).isZero();
        assertThat(failed.error()).contains("returned code 10");
        assertThat(transactions.rollbacks).isEqualTo(3);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void backwardNativeCursorIsRejectedInsideRollbackTransaction() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.isArtAfter(WAREHOUSE_ID, "C", "B")).thenReturn(false);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk("B", 40, 100, "C", null));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq("C"), eq(0), eq(100), eq(120)))
                .thenReturn(nativeChunk("Z", 20, 100, "B", null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("continuation cursor");
        assertThat(failed.failedChunk()).isNotNull();
        assertThat(failed.failedChunk().inputArt()).isEqualTo("C");
        assertThat(failed.failedChunk().outputArt()).isEqualTo("Z");
        assertThat(failed.failedChunk().nextArt()).isEqualTo("B");
        assertThat(failed.failedChunk().returnCode()).isZero();
        assertThat(failed.failedChunk().currentUnits()).isEqualTo(20);
        assertThat(failed.failedChunk().totalUnits()).isEqualTo(100);
        assertThat(failed.failedChunk().validationError())
                .contains("invalid continuation cursor");
        assertThat(failed.procedureCalls()).isEqualTo(2);
        assertThat(failed.preflightChunks()).isEqualTo(2);
        assertThat(transactions.rollbacks).isEqualTo(2);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void staleOutputArtDoesNotRejectACompletedRollbackPreview() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk("B", 40, 100, "C", null));
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq("C"), eq(0), eq(100), eq(120)))
                .thenReturn(nativeChunk("B", 60, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var completed = service.nativeFullStatus(false);

        assertThat(completed.status()).isEqualTo("PREVIEW_READY");
        assertThat(completed.failedChunk()).isNull();
        assertThat(completed.procedureCalls()).isEqualTo(2);
        assertThat(completed.preflightChunks()).isEqualTo(2);
        assertThat(transactions.rollbacks).isEqualTo(2);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void warehouseScopeDriftStopsBeforeNativeProcedure() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.findWarehouseForUpdate(WAREHOUSE_ID)).thenReturn(
                new WarehouseRow(WAREHOUSE_ID, "Test warehouse", 1000, 0));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("scope or method changed");
        verify(dao, never()).callNativeFullChunk(
                org.mockito.ArgumentMatchers.nullable(Integer.class), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.nullable(String.class),
                anyInt(), anyInt(), anyInt());
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void transactionDatabaseMismatchStopsBeforeNativeProcedure() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.currentDatabaseName()).thenReturn("Paint_Rus", "Paint_Ua");
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(failed.error()).contains("database changed");
        verify(dao, never()).callNativeFullChunk(
                org.mockito.ArgumentMatchers.nullable(Integer.class), anyInt(), anyInt(), anyInt(),
                anyBoolean(), org.mockito.ArgumentMatchers.nullable(String.class),
                anyInt(), anyInt(), anyInt());
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void nativeConnectionLossIsReportedAsUnknownOutcome() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(failed.error()).contains("connection lost");
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void incompleteNativeProgressRollsBackAndCannotReportCompleted() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(CLEAN_SKU, 90, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("ended before all progress units");
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void nativeWorkWithoutOutputArtCannotBeCommitted() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(nativeChunk(null, 100, 100, null, null));
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, false);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, true, false));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("without the last processed art");
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
    }

    @Test
    void protectedNativeRangeChangeRollsBackApplyChunk() {
        FolioAccountingPriceDao dao = mock(FolioAccountingPriceDao.class);
        stubNativeWarehouse(dao);
        NativeFullChunkOutput clean = nativeChunk(
                CLEAN_SKU, 100, 100, null, null);
        when(dao.callNativeFullChunk(
                eq(null), eq(WAREHOUSE_ID), eq(0), eq(0), eq(false),
                eq(null), eq(0), eq(0), eq(120)))
                .thenReturn(clean);
        NativeProtectedSnapshot before = protectedSnapshot("before-article");
        NativeProtectedSnapshot after = protectedSnapshot("after-article");
        when(dao.captureNativeProtectedSnapshot(
                WAREHOUSE_ID, null, null)).thenReturn(before);
        when(dao.captureNativeProtectedSnapshot(
                WAREHOUSE_ID, null, CLEAN_SKU)).thenReturn(after);
        TrackingTransactionManager transactions = new TrackingTransactionManager();
        FolioAccountingPriceService service = nativeService(dao, transactions, true);

        service.requestNativeFull(new FolioAccountingPriceNativeFullRequest(
                WAREHOUSE_ID, false, true));
        var failed = service.nativeFullStatus(false);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.committedChunks()).isZero();
        assertThat(failed.error()).contains("protected stock or movement invariant");
        assertThat(transactions.rollbacks).isEqualTo(3);
        assertThat(transactions.commits).isZero();
    }

    private static FolioAccountingPriceService service(FolioAccountingPriceDao dao,
                                                        boolean applyEnabled,
                                                        boolean fullApplyEnabled) {
        return service(dao, transactionManager(), applyEnabled, fullApplyEnabled);
    }

    private static FolioAccountingPriceService service(
            FolioAccountingPriceDao dao,
            PlatformTransactionManager transactionManager,
            boolean applyEnabled,
            boolean fullApplyEnabled) {
        return new FolioAccountingPriceService(
                dao,
                DIRECT_EXECUTOR,
                CLOCK,
                transactionManager,
                true,
                applyEnabled,
                fullApplyEnabled,
                5_000,
                120,
                20
        );
    }

    private static FolioAccountingPriceService nativeService(
            FolioAccountingPriceDao dao,
            PlatformTransactionManager transactionManager,
            boolean nativeApplyEnabled) {
        return nativeService(dao, transactionManager, true, nativeApplyEnabled);
    }

    private static FolioAccountingPriceService nativeService(
            FolioAccountingPriceDao dao,
            PlatformTransactionManager transactionManager,
            boolean globalApplyEnabled,
            boolean nativeApplyEnabled) {
        return new FolioAccountingPriceService(
                dao,
                DIRECT_EXECUTOR,
                CLOCK,
                transactionManager,
                true,
                globalApplyEnabled,
                true,
                true,
                nativeApplyEnabled,
                Set.of("Paint_Rus"),
                100,
                5_000,
                120,
                20
        );
    }

    private static void stubWarehouse(FolioAccountingPriceDao dao) {
        WarehouseRow warehouse = new WarehouseRow(
                WAREHOUSE_ID, "Test warehouse", 1000, null);
        when(dao.findWarehouseScope(WAREHOUSE_ID))
                .thenReturn(new WarehouseScope(warehouse, List.of(warehouse)));
    }

    private static void stubNativeWarehouse(FolioAccountingPriceDao dao) {
        stubWarehouse(dao);
        WarehouseRow warehouse = new WarehouseRow(
                WAREHOUSE_ID, "Test warehouse", 1000, null);
        when(dao.currentDatabaseName()).thenReturn("Paint_Rus");
        when(dao.findWarehouseForUpdate(WAREHOUSE_ID)).thenReturn(warehouse);
        when(dao.isArtAfter(eq(WAREHOUSE_ID), anyString(), anyString()))
                .thenReturn(true);
        when(dao.isArtAtOrAfter(eq(WAREHOUSE_ID), anyString(), anyString()))
                .thenReturn(true);
        when(dao.findProcessedRangeEnd(eq(WAREHOUSE_ID),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(CLEAN_SKU);
        NativeProtectedSnapshot protectedState = protectedSnapshot("article-sha256");
        when(dao.captureNativeProtectedSnapshot(
                anyInt(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(protectedState);
    }

    private static NativeFullChunkOutput nativeChunk(String art,
                                                     int current,
                                                     int total,
                                                     String next,
                                                     String problemDate) {
        return new NativeFullChunkOutput(
                0, art, current, total, next, problemDate, 1, 1, 0);
    }

    private static NativeChronologyProblem nativeProblem(String code,
                                                         String sku,
                                                         String documentType,
                                                         String operationQuantity,
                                                         String quantityBefore,
                                                         String quantityAfter) {
        return new NativeChronologyProblem(
                code, sku, WAREHOUSE_ID, 1001L,
                new BigDecimal("700001"), new BigDecimal("47"),
                LocalDateTime.of(2026, 8, 15, 9, 0), documentType, false,
                new BigDecimal(operationQuantity), new BigDecimal(quantityBefore),
                new BigDecimal(quantityAfter), 7, 12, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("800"));
    }

    private static NativeProtectedSnapshot protectedSnapshot(String articleHash) {
        return protectedSnapshot(List.of(CLEAN_SKU), articleHash);
    }

    private static NativeProtectedSnapshot protectedSnapshot(List<String> skus,
                                                              String articleHash) {
        NativeSkuProtectedState state = new NativeSkuProtectedState(
                new NativeInvariantDigest(1, articleHash),
                new NativeInvariantDigest(1, "movement-sha256"));
        Map<String, NativeSkuProtectedState> states = skus.stream()
                .collect(java.util.stream.Collectors.toMap(
                        sku -> sku,
                        sku -> state,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        return new NativeProtectedSnapshot(
                List.copyOf(skus), Map.copyOf(states));
    }

    private static void stubProduct(FolioAccountingPriceDao dao,
                                    String sku,
                                    ArticleRow article,
                                    List<MovementRow> movements) {
        when(dao.findArticles(eq(sku), eq(List.of(WAREHOUSE_ID)), anyBoolean()))
                .thenReturn(List.of(article));
        when(dao.findChronologicalMovements(
                eq(sku), eq(List.of(WAREHOUSE_ID)), anyBoolean()))
                .thenReturn(movements);
        when(dao.findMovementTotals(eq(sku), eq(List.of(WAREHOUSE_ID))))
                .thenReturn(Map.of(WAREHOUSE_ID, totals()));
    }

    private static ArticleRow article(String sku, String accountingPrice) {
        return articleWithAccountingAmount(sku, accountingPrice, "8000");
    }

    private static ArticleRow articleWithAccountingAmount(String sku,
                                                          String accountingPrice,
                                                          String accountingAmount) {
        return new ArticleRow(
                sku,
                WAREHOUSE_ID,
                "Test warehouse",
                "Test product",
                "1",
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.TEN,
                new BigDecimal(accountingAmount),
                BigDecimal.ZERO,
                new BigDecimal(accountingPrice),
                BigDecimal.ZERO,
                new BigDecimal("800"),
                BigDecimal.ZERO,
                false
        );
    }

    private static MovementRow expense(long recno, String quantity) {
        return new MovementRow(
                recno,
                new BigDecimal("700001"),
                new BigDecimal("1001"),
                WAREHOUSE_ID,
                LocalDateTime.of(2026, 8, 15, 9, 0),
                FolioAccountingPriceDao.TYPE_EXPENSE,
                false,
                new BigDecimal(quantity)
        );
    }

    private static MovementTotals totals() {
        return new MovementTotals(
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static PlatformTransactionManager transactionManager() {
        return new TrackingTransactionManager();
    }

    private static final class TrackingTransactionManager implements PlatformTransactionManager {
        private int commits;
        private int rollbacks;
        private final List<Integer> timeouts = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            timeouts.add(definition.getTimeout());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) {
                rollbacks++;
            } else {
                commits++;
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
