package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Capture;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.CaptureConsumer;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MonthlyActivity;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MovementFact;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Warehouse;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Generation;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Publish;
import org.example.proect.lavka.dto.folio.FolioProductSnapshotRefreshRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioProductSnapshotServiceTest {

    @Test
    void persistedBuildingGenerationIsReportedAsInterruptedAfterRestart() {
        FolioProductSnapshotSourceDao source = mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshot = mock(FolioProductSnapshotDao.class);
        FolioAccountingPriceDao accounting = mock(FolioAccountingPriceDao.class);
        when(snapshot.latest()).thenReturn(Optional.of(new Generation(
                35L, "Paint_Ua", 9, 36, 2, "BUILDING", "MANUAL",
                LocalDateTime.of(2026, 8, 27, 9, 15), null,
                0, 0, 0, 0, 0, 0, 0, 0, null, null)));
        FolioProductSnapshotService service = new FolioProductSnapshotService(
                source, snapshot, accounting, new FolioProductEconomicsCalculator(),
                directExecutor(), Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"),
                ZoneOffset.UTC), transactionManager(), true, 24, 600, 5_000, 3_600);

        var response = service.status();

        assertThat(response.running()).isFalse();
        assertThat(response.status()).isEqualTo("INTERRUPTED");
        assertThat(response.phase()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(response.errorCode())
                .isEqualTo("PRODUCT_SNAPSHOT_INTERRUPTED_BY_RESTART");
        assertThat(response.recommendation()).contains("Start snapshot refresh again");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stagesBoundedSourceOutputBeforePublishingGeneration(boolean existingFailure) {
        FolioProductSnapshotSourceDao source = mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshot = mock(FolioProductSnapshotDao.class);
        FolioAccountingPriceDao accounting = mock(FolioAccountingPriceDao.class);
        ProductCard product = product();
        MovementFact movement = movement();
        MonthlyActivity activity = activity();
        when(source.currentDatabaseName()).thenReturn("Paint_Ua");
        when(snapshot.tryAcquireLease(anyString(), anyString(), anyInt())).thenReturn(true);
        when(snapshot.renewLease(anyString(), anyString(), anyInt())).thenReturn(true);
        when(snapshot.createGeneration(anyString(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(17L);
        when(snapshot.findExisting("Paint_Ua", 5)).thenReturn(existingFailure
                ? Map.of(product.sku(), new FolioProductSnapshotDao.ExistingItem(product.sku(),
                product.productName(), null, "UNKNOWN", product.sourceDigest(), null, "FAILED", true,
                1, 1L, 1L, null, null, 0, LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 1, 0, 0), "NEGATIVE_CHRONOLOGICAL_STOCK; jobId=previous")) : Map.of());
        doAnswer(invocation -> {
            CaptureConsumer consumer = invocation.getArgument(4);
            consumer.acceptMovementBatch(List.of(movement));
            consumer.acceptProductActivity(product, List.of(activity));
            consumer.acceptProductDailyStock(product, Map.of(movement.documentDate(), movement.signedQuantity()));
            return new Capture(new Warehouse("Paint_Ua", 5, "Odessa",
                    new BigDecimal("1000"), null), "digest", List.of(product), 1, 1);
        }).when(source).capture(anyInt(), any(), any(), anyInt(), any());

        FolioProductSnapshotService service = new FolioProductSnapshotService(
                source, snapshot, accounting, new FolioProductEconomicsCalculator(),
                directExecutor(), Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"),
                ZoneOffset.UTC), transactionManager(), true, 24, 600, 5_000, 3_600);

        var response = service.request(new FolioProductSnapshotRefreshRequest(5, 24));

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.movementFactRows()).isEqualTo(1);
        verify(snapshot).stageMovements(anyLong(), anyString(), anyInt(), any(), anyList());
        verify(snapshot).stageMonthly(anyLong(), anyString(), anyInt(), any(), anyList());
        verify(snapshot).stageCurrent(anyLong(), anyString(), anyInt(), any(), anyList());
        verify(snapshot).stageAvailability(anyLong(), anyString(), anyInt(), anyList());
        ArgumentCaptor<Publish> publish = ArgumentCaptor.forClass(Publish.class);
        verify(snapshot).publish(publish.capture());
        assertThat(publish.getValue().movementFactRows()).isEqualTo(1);
        assertThat(publish.getValue().monthlyMetricRows()).isEqualTo(1);
        if (existingFailure) {
            assertThat(publish.getValue().items()).singleElement().satisfies(item -> {
                assertThat(item.state()).isEqualTo("FAILED");
                assertThat(item.appliedDigest()).isNull();
                assertThat(item.lastError()).contains("NEGATIVE_CHRONOLOGICAL_STOCK", "jobId=previous");
            });
        }
    }

    @Test
    void missingAvailabilityCaptureCannotPublishAnApparentlyCompleteV5Snapshot() {
        FolioProductSnapshotSourceDao source = mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshot = mock(FolioProductSnapshotDao.class);
        FolioAccountingPriceDao accounting = mock(FolioAccountingPriceDao.class);
        when(source.currentDatabaseName()).thenReturn("Paint_Ua");
        when(snapshot.tryAcquireLease(anyString(), anyString(), anyInt())).thenReturn(true);
        when(snapshot.renewLease(anyString(), anyString(), anyInt())).thenReturn(true);
        when(snapshot.createGeneration(anyString(), anyInt(), anyInt(), anyString(), any())).thenReturn(19L);
        when(source.capture(anyInt(), any(), any(), anyInt(), any())).thenReturn(
                new Capture(new Warehouse("Paint_Ua",5,"Test",new BigDecimal("1000"),null),
                        "digest",List.of(product()),0,0));
        var service = new FolioProductSnapshotService(source,snapshot,accounting,
                new FolioProductEconomicsCalculator(),directExecutor(),
                Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"),ZoneOffset.UTC),
                transactionManager(),true,24,600,5_000,3_600);
        var response = service.request(new FolioProductSnapshotRefreshRequest(5,24));
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.error()).contains("availability staging product count mismatch");
        verify(snapshot, org.mockito.Mockito.never()).publish(any());
        verify(snapshot).discardStaging(19L);
    }

    @Test
    void failedSourceCaptureDiscardsStagingWithoutPublishing() {
        FolioProductSnapshotSourceDao source = mock(FolioProductSnapshotSourceDao.class);
        FolioProductSnapshotDao snapshot = mock(FolioProductSnapshotDao.class);
        FolioAccountingPriceDao accounting = mock(FolioAccountingPriceDao.class);
        when(source.currentDatabaseName()).thenReturn("Paint_Ua");
        when(snapshot.tryAcquireLease(anyString(), anyString(), anyInt())).thenReturn(true);
        when(snapshot.renewLease(anyString(), anyString(), anyInt())).thenReturn(true);
        when(snapshot.createGeneration(anyString(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(18L);
        doAnswer(invocation -> {
            CaptureConsumer consumer = invocation.getArgument(4);
            consumer.acceptMovementBatch(List.of(movement()));
            throw new IllegalStateException("source failed");
        }).when(source).capture(anyInt(), any(), any(), anyInt(), any());
        FolioProductSnapshotService service = new FolioProductSnapshotService(
                source, snapshot, accounting, new FolioProductEconomicsCalculator(),
                directExecutor(), Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"),
                ZoneOffset.UTC), transactionManager(), true, 24, 600, 5_000, 3_600);

        var response = service.request(new FolioProductSnapshotRefreshRequest(5, 24));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.error()).isEqualTo("source failed");
        verify(snapshot, never()).publish(any());
        verify(snapshot).failGeneration(anyLong(), anyString(), any());
        verify(snapshot).discardStaging(18L);
    }

    private static TaskExecutor directExecutor() {
        return Runnable::run;
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private static ProductCard product() {
        return new ProductCard("SKU-1", "Product", "digest", "Supplier", "CURRENT",
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, new BigDecimal("10"), new BigDecimal("10"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                1, 1L, 1L, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1), 0, false);
    }

    private static MovementFact movement() {
        return new MovementFact(1, 10L, BigDecimal.ONE,
                LocalDate.of(2026, 8, 1), "SKU-1", BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("10"), "П", "П", "", true, false,
                "RECEIPT", "IN", "NOT_APPLICABLE", "NOT_SPECIFIED",
                "NOT_APPLICABLE", "SUP", "Supplier", "П", "Supplier",
                "CURRENT", true, false, false);
    }

    private static MonthlyActivity activity() {
        return new MonthlyActivity("SKU-1", LocalDate.of(2026, 8, 1),
                BigDecimal.ONE, new BigDecimal("10"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDate.of(2026, 8, 1), null, null,
                BigDecimal.ONE, new BigDecimal("10"));
    }
}
