package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.ArticleRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementTotals;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseScope;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(dao.countScratchRows()).thenReturn(0);

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
                .extracting(warning -> warning.code())
                .contains("NEGATIVE_CHRONOLOGICAL_STOCK");
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
        when(dao.countScratchRows()).thenReturn(0);

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
        when(dao.countScratchRows()).thenReturn(0);
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
        when(dao.countScratchRows()).thenReturn(0);

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

    private static void stubWarehouse(FolioAccountingPriceDao dao) {
        WarehouseRow warehouse = new WarehouseRow(
                WAREHOUSE_ID, "Test warehouse", 1000, null);
        when(dao.findWarehouseScope(WAREHOUSE_ID))
                .thenReturn(new WarehouseScope(warehouse, List.of(warehouse)));
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
        when(dao.countScratchRows()).thenReturn(0);
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

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
