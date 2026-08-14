package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.ActiveSnapshot;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.SnapshotClient;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.SnapshotPage;
import org.example.proect.lavka.dao.wp.FolioCustomerBalanceSnapshotDao.SnapshotSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FolioCustomerDebtorsServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 14);
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 8, 14, 0, 20);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T10:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void readsFilteredPageAndTotalsFromActiveSnapshot() {
        FolioCustomerBalanceSnapshotDao dao = readyDao();
        when(dao.findDebtors(eq(42L), eq(bd("100.00")), eq("name"),
                eq(List.of("П", "Д")), eq(1), eq(1)))
                .thenReturn(new SnapshotPage(
                        new SnapshotSummary(3, bd("900"), bd("100"), bd("50"), bd("700"), bd("800")),
                        List.of(client("B", "Beta", "П", "300", "0", "0", "0", "300"))
                ));

        var response = service(dao).get(bd("100"), " name ", "П,Д,П", 1, 1,
                "payableNow_desc");

        assertThat(response.asOfDate()).isEqualTo(AS_OF);
        assertThat(response.summary().matchedClients()).isEqualTo(3);
        assertThat(response.summary().returnedClients()).isEqualTo(1);
        assertThat(response.summary().payableNowTotal()).isEqualByComparingTo("800");
        assertThat(response.debtors()).extracting(item -> item.partner().shortName())
                .containsExactly("B");
        assertThat(response.warnings()).extracting(issue -> issue.code())
                .contains("BALANCE_SNAPSHOT");
    }

    @Test
    void keepsStoredDebtAndPrepaymentSeparate() {
        FolioCustomerBalanceSnapshotDao dao = readyDao();
        when(dao.findDebtors(any(Long.class), eq(BigDecimal.ZERO.setScale(2)), any(), anyList(), anyInt(), anyInt()))
                .thenReturn(new SnapshotPage(
                        new SnapshotSummary(1, bd("200"), bd("0"), bd("0"), bd("700"), bd("200")),
                        List.of(client("CLIENT", "Client", "Д", "200", "0", "0", "700", "200"))
                ));

        var response = service(dao).get(BigDecimal.ZERO, null, null, 50, 0, null);
        var client = response.debtors().get(0);

        assertThat(client.commonDebt()).isEqualByComparingTo("200");
        assertThat(client.prepaymentAmount()).isEqualByComparingTo("700");
        assertThat(client.payableNow()).isEqualByComparingTo("200");
    }

    @Test
    void supportsAllTypesAndEmptyResult() {
        FolioCustomerBalanceSnapshotDao dao = readyDao();
        when(dao.findDebtors(eq(42L), eq(bd("100.00")), eq(""), eq(List.of()), eq(50), eq(0)))
                .thenReturn(new SnapshotPage(
                        new SnapshotSummary(0, bd("0"), bd("0"), bd("0"), bd("0"), bd("0")),
                        List.of()
                ));

        var response = service(dao).get(null, null, "all", null, null, null);

        verify(dao).findDebtors(42L, bd("100.00"), "", List.of(), 50, 0);
        assertThat(response.filters().types()).containsExactly("all");
        assertThat(response.summary().matchedClients()).isZero();
        assertThat(response.debtors()).isEmpty();
    }

    @Test
    void reportsStaleSnapshotWithoutRecalculatingFolio() {
        FolioCustomerBalanceSnapshotDao dao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(dao.findActiveSnapshot()).thenReturn(Optional.of(new ActiveSnapshot(
                41L, "ACTIVE", AS_OF.minusDays(1), COMPLETED_AT.minusDays(1),
                COMPLETED_AT.minusDays(1), 10
        )));
        when(dao.findDebtors(any(Long.class), any(), any(), anyList(), anyInt(), anyInt()))
                .thenReturn(new SnapshotPage(
                        new SnapshotSummary(0, bd("0"), bd("0"), bd("0"), bd("0"), bd("0")),
                        List.of()
                ));

        var response = service(dao).get(null, null, null, null, null, null);

        assertThat(response.warnings()).extracting(issue -> issue.code())
                .contains("BALANCE_SNAPSHOT_STALE");
    }

    @Test
    void failsFastWhenNoActiveSnapshotExists() {
        FolioCustomerBalanceSnapshotDao dao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(dao.findActiveSnapshot()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(dao).get(null, null, null, null, null, null))
                .isInstanceOf(FolioBalanceSnapshotUnavailableException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void rejectsInvalidParametersBeforeReadingSnapshot() {
        FolioCustomerBalanceSnapshotDao dao = mock(FolioCustomerBalanceSnapshotDao.class);
        FolioCustomerDebtorsService service = service(dao);

        assertThatThrownBy(() -> service.get(new BigDecimal("-1"), null, null, null, null, null))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("minPayable");
        assertThatThrownBy(() -> service.get(new BigDecimal("1.001"), null, null, null, null, null))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("decimal");
        assertThatThrownBy(() -> service.get(null, null, "BAD", null, null, null))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("types");
        assertThatThrownBy(() -> service.get(null, null, null, 201, null, null))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.get(null, null, null, null, -1, null))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> service.get(null, null, null, null, null, "name_asc"))
                .isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("sort");
        verifyNoInteractions(dao);
    }

    private static FolioCustomerBalanceSnapshotDao readyDao() {
        FolioCustomerBalanceSnapshotDao dao = mock(FolioCustomerBalanceSnapshotDao.class);
        when(dao.findActiveSnapshot()).thenReturn(Optional.of(new ActiveSnapshot(
                42L, "ACTIVE", AS_OF, COMPLETED_AT.minusMinutes(10), COMPLETED_AT, 20
        )));
        return dao;
    }

    private static SnapshotClient client(String shortName,
                                         String name,
                                         String type,
                                         String commonDebt,
                                         String deferred,
                                         String overdue,
                                         String prepayment,
                                         String payableNow) {
        return new SnapshotClient(
                shortName, name, type, "", "",
                bd(commonDebt), bd(deferred), bd(overdue), bd(prepayment), bd(payableNow),
                COMPLETED_AT
        );
    }

    private static FolioCustomerDebtorsService service(FolioCustomerBalanceSnapshotDao dao) {
        return new FolioCustomerDebtorsService(dao, CLOCK);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
