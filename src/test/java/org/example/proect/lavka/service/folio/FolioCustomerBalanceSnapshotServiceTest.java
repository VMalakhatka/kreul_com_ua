package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.PartnerBalanceResult;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.PartnerCandidate;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.ProcedureResult;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.SnapshotClient;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.ActiveSnapshot;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.GenerationStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioCustomerBalanceSnapshotServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 14);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T10:00:00Z"),
            ZoneOffset.UTC
    );
    private static final TaskExecutor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void statusShowsBuildingGenerationAndPreviousActiveSnapshotTogether() {
        FolioCustomerBalanceDao balanceDao = mock(FolioCustomerBalanceDao.class);
        FolioCustomerBalanceSnapshotDao snapshotDao = mock(FolioCustomerBalanceSnapshotDao.class);
        LocalDateTime buildingStarted = LocalDateTime.of(2026, 8, 15, 0, 10);
        LocalDateTime activeCompleted = LocalDateTime.of(2026, 8, 14, 21, 41, 25, 646_000_000);
        when(snapshotDao.findLatestGeneration()).thenReturn(Optional.of(new GenerationStatus(
                2L, "BUILDING", "SCHEDULED", LocalDate.of(2026, 8, 15),
                buildingStarted, null, 0, null, false
        )));
        when(snapshotDao.findActiveSnapshot()).thenReturn(Optional.of(new ActiveSnapshot(
                1L, "ACTIVE", LocalDate.of(2026, 8, 14),
                LocalDateTime.of(2026, 8, 14, 20, 0), activeCompleted, 1698
        )));

        var response = service(balanceDao, snapshotDao).status(false);

        assertThat(response.running()).isTrue();
        assertThat(response.building()).isNotNull();
        assertThat(response.building().generationId()).isEqualTo(2L);
        assertThat(response.activeSnapshot()).isNotNull();
        assertThat(response.activeSnapshot().generationId()).isEqualTo(1L);
        assertThat(response.activeSnapshot().totalClients()).isEqualTo(1698);
    }

    @Test
    void buildsAndAtomicallyPublishesCanonicalGeneration() {
        FolioCustomerBalanceDao balanceDao = mock(FolioCustomerBalanceDao.class);
        FolioCustomerBalanceSnapshotDao snapshotDao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(snapshotDao.tryAcquireLease(any(), anyInt())).thenReturn(true);
        when(snapshotDao.renewLease(any(), anyInt())).thenReturn(true);
        when(snapshotDao.createGeneration(eq(AS_OF), eq("MANUAL"), any(LocalDateTime.class)))
                .thenReturn(77L);
        PartnerBalanceResult result = new PartnerBalanceResult(
                new PartnerCandidate("CLIENT", "Client name", "Д", "Kyiv", "+380"),
                new ProcedureResult(
                        "CLIENT", "Client name", bd("200"), BigDecimal.ZERO,
                        null, List.of()
                )
        );
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<PartnerBalanceResult> consumer = invocation.getArgument(5);
            consumer.accept(result);
            return 1;
        }).when(balanceDao).forEachPartnerBalance(
                eq(null), eq(List.of()), eq(FolioCustomerBalanceService.FOLIO_MIN_DATE),
                eq(AS_OF), eq(true), any());

        FolioCustomerBalanceSnapshotService service = service(balanceDao, snapshotDao);

        assertThat(service.requestRefresh("manual")).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SnapshotClient>> clientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(snapshotDao).saveClients(eq(77L), clientsCaptor.capture());
        SnapshotClient stored = clientsCaptor.getValue().get(0);
        assertThat(stored.partnerShortName()).isEqualTo("CLIENT");
        assertThat(stored.partnerType()).isEqualTo("Д");
        assertThat(stored.commonDebt()).isEqualByComparingTo("200.00");
        assertThat(stored.payableNow()).isEqualByComparingTo("200.00");
        verify(snapshotDao).publishGeneration(eq(77L), eq(1), any(LocalDateTime.class));
        verify(snapshotDao).releaseLease(any());
    }

    @Test
    void keepsPreviousActiveGenerationWhenBuildFails() {
        FolioCustomerBalanceDao balanceDao = mock(FolioCustomerBalanceDao.class);
        FolioCustomerBalanceSnapshotDao snapshotDao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(snapshotDao.tryAcquireLease(any(), anyInt())).thenReturn(true);
        when(snapshotDao.renewLease(any(), anyInt())).thenReturn(true);
        when(snapshotDao.createGeneration(eq(AS_OF), eq("SCHEDULED"), any(LocalDateTime.class)))
                .thenReturn(78L);
        when(balanceDao.forEachPartnerBalance(any(), anyList(), any(), any(), eq(true), any()))
                .thenThrow(new IllegalStateException("folio unavailable"));

        FolioCustomerBalanceSnapshotService service = service(balanceDao, snapshotDao);

        assertThat(service.requestRefresh("scheduled")).isTrue();

        verify(snapshotDao).failGeneration(eq(78L), eq("folio unavailable"), any(LocalDateTime.class));
        verify(snapshotDao, never()).publishGeneration(any(Long.class), anyInt(), any());
        verify(snapshotDao).releaseLease(any());
    }

    @Test
    void skipsRefreshWhenAnotherInstanceOwnsLease() {
        FolioCustomerBalanceDao balanceDao = mock(FolioCustomerBalanceDao.class);
        FolioCustomerBalanceSnapshotDao snapshotDao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(snapshotDao.tryAcquireLease(any(), anyInt())).thenReturn(false);

        FolioCustomerBalanceSnapshotService service = service(balanceDao, snapshotDao);

        assertThat(service.requestRefresh("manual")).isTrue();

        verify(balanceDao, never()).forEachPartnerBalance(any(), anyList(), any(), any(), eq(true), any());
        verify(snapshotDao, never()).createGeneration(any(), any(), any());
        verify(snapshotDao).releaseLease(any());
    }

    @Test
    void doesNotPublishGenerationAfterLeaseIsLost() {
        FolioCustomerBalanceDao balanceDao = mock(FolioCustomerBalanceDao.class);
        FolioCustomerBalanceSnapshotDao snapshotDao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(snapshotDao.tryAcquireLease(any(), anyInt())).thenReturn(true);
        when(snapshotDao.createGeneration(eq(AS_OF), eq("MANUAL"), any(LocalDateTime.class)))
                .thenReturn(79L);
        when(balanceDao.forEachPartnerBalance(any(), anyList(), any(), any(), eq(true), any()))
                .thenReturn(0);
        when(snapshotDao.renewLease(any(), anyInt())).thenReturn(false);

        FolioCustomerBalanceSnapshotService service = service(balanceDao, snapshotDao);

        assertThat(service.requestRefresh("manual")).isTrue();

        verify(snapshotDao).failGeneration(
                eq(79L), eq("Balance snapshot lease was lost during generation"), any(LocalDateTime.class)
        );
        verify(snapshotDao, never()).publishGeneration(any(Long.class), anyInt(), any());
    }

    private static FolioCustomerBalanceSnapshotService service(
            FolioCustomerBalanceDao balanceDao,
            FolioCustomerBalanceSnapshotDao snapshotDao) {
        return new FolioCustomerBalanceSnapshotService(
                balanceDao, snapshotDao, DIRECT_EXECUTOR, CLOCK, true, 7200
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
