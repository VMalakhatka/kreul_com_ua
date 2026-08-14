package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.PartnerBalanceResult;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.PartnerCandidate;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.ProcedureResult;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.RawRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioCustomerDebtorsServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 14);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T10:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void appliesStrictThreshold() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.loadForPartners(any(), anyList(), any(), any(), anyBoolean()))
                .thenReturn(List.of(
                        result("EXACT", "Exact", "Д", expense("100.00", null, null)),
                        result("ABOVE", "Above", "Д", expense("100.01", null, null))
                ));

        var response = service(dao).get(new BigDecimal("100.00"), null, null, null, null, null);

        assertThat(response.summary().matchedClients()).isEqualTo(1);
        assertThat(response.debtors()).extracting(item -> item.partner().shortName())
                .containsExactly("ABOVE");
        assertThat(response.debtors().get(0).payableNow()).isEqualByComparingTo("100.01");
    }

    @Test
    void reusesCorrectedDeferredAndPrepaymentRules() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.loadForPartners(any(), anyList(), any(), any(), anyBoolean()))
                .thenReturn(List.of(
                        result("NO_REL", "No marker", "Д",
                                expense("500", "ordinary", "2026-09-01")),
                        result("FUTURE", "Future deferred", "Д",
                                expense("1000", "РЕЛ Отсрочка", "2026-09-01")),
                        result("OVERDUE", "Overdue", "Д",
                                expense("400", "РЕЛ Отсрочка", "2026-08-10")),
                        result("PREPAY", "Prepaid", "Д",
                                expense("300", null, null),
                                bankPayment("200", "ПРД Банковская предоплата"))
                ));

        var response = service(dao).get(BigDecimal.ZERO, null, null, 50, 0, null);

        assertThat(response.debtors()).extracting(item -> item.partner().shortName())
                .containsExactly("NO_REL", "OVERDUE", "PREPAY");
        assertThat(item(response, "NO_REL").deferredAmount()).isEqualByComparingTo("0");
        assertThat(item(response, "NO_REL").payableNow()).isEqualByComparingTo("500");
        assertThat(item(response, "OVERDUE").overdueDeferredAmount()).isEqualByComparingTo("400");
        assertThat(item(response, "OVERDUE").payableNow()).isEqualByComparingTo("400");
        assertThat(item(response, "PREPAY").commonDebt()).isEqualByComparingTo("300");
        assertThat(item(response, "PREPAY").prepaymentAmount()).isEqualByComparingTo("200");
        assertThat(item(response, "PREPAY").payableNow()).isEqualByComparingTo("300");
    }

    @Test
    void keepsPrepaymentSeparateFromExistingDebt() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.loadForPartners(any(), anyList(), any(), any(), anyBoolean()))
                .thenReturn(List.of(result(
                        "CLIENT",
                        "Client",
                        "Д",
                        expense("1000", null, null),
                        bankPayment("800", null),
                        bankPayment("700", "ПРД Предоплата за будущий товар")
                )));

        var response = service(dao).get(BigDecimal.ZERO, null, null, 50, 0, null);
        var client = item(response, "CLIENT");

        assertThat(client.commonDebt()).isEqualByComparingTo("200");
        assertThat(client.prepaymentAmount()).isEqualByComparingTo("700");
        assertThat(client.payableNow()).isEqualByComparingTo("200");
    }

    @Test
    void filtersSortsAndPaginatesStablyWhileSummaryCoversAllMatches() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.loadForPartners(any(), anyList(), any(), any(), anyBoolean()))
                .thenReturn(List.of(
                        result("B", "Beta", "П", expense("300", null, null)),
                        result("A", "Alpha", "П", expense("300", null, null)),
                        result("C", "Gamma", "П", expense("200", null, null))
                ));

        var response = service(dao).get(BigDecimal.ZERO, " name ", "П,Д,П", 1, 1,
                "payableNow_desc");

        verify(dao).loadForPartners(
                eq("name"),
                eq(List.of("П", "Д")),
                eq(FolioCustomerBalanceService.FOLIO_MIN_DATE),
                eq(AS_OF),
                eq(true)
        );
        assertThat(response.summary().matchedClients()).isEqualTo(3);
        assertThat(response.summary().returnedClients()).isEqualTo(1);
        assertThat(response.summary().payableNowTotal()).isEqualByComparingTo("800");
        assertThat(response.debtors()).extracting(item -> item.partner().shortName())
                .containsExactly("B");
    }

    @Test
    void supportsAllTypesAndEmptyResult() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.loadForPartners(any(), anyList(), any(), any(), anyBoolean()))
                .thenReturn(List.of());

        var response = service(dao).get(null, null, "all", null, null, null);

        verify(dao).loadForPartners(any(), eq(List.of()), any(), eq(AS_OF), eq(true));
        assertThat(response.ok()).isTrue();
        assertThat(response.filters().types()).containsExactly("all");
        assertThat(response.filters().minPayable()).isEqualByComparingTo("100.00");
        assertThat(response.summary().matchedClients()).isZero();
        assertThat(response.debtors()).isEmpty();
        assertThat(response.errors()).isEmpty();
    }

    @Test
    void rejectsInvalidParametersBeforeCallingDatabase() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
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
    }

    private static FolioCustomerDebtorsService service(FolioCustomerBalanceDao dao) {
        return new FolioCustomerDebtorsService(dao, CLOCK);
    }

    private static org.example.proect.lavka.dto.folio.FolioCustomerDebtorsResponse.DebtorItem item(
            org.example.proect.lavka.dto.folio.FolioCustomerDebtorsResponse response,
            String shortName) {
        return response.debtors().stream()
                .filter(value -> shortName.equals(value.partner().shortName()))
                .findFirst()
                .orElseThrow();
    }

    private static PartnerBalanceResult result(String shortName,
                                               String name,
                                               String type,
                                               RawRow... rows) {
        PartnerCandidate partner = new PartnerCandidate(shortName, name, type, "", "");
        ProcedureResult balance = new ProcedureResult(
                shortName, name, BigDecimal.ZERO, BigDecimal.ZERO, null, List.of(rows)
        );
        return new PartnerBalanceResult(partner, balance);
    }

    private static RawRow expense(String amount, String basis, String controlDate) {
        return row("Р", amount, basis, null, BigDecimal.ZERO, BigDecimal.ZERO, controlDate);
    }

    private static RawRow bankPayment(String amount, String note) {
        return row("ПБ", "0", null, note, BigDecimal.ZERO, bd(amount), null);
    }

    private static RawRow row(String type,
                              String amount,
                              String basis,
                              String note,
                              BigDecimal cash,
                              BigDecimal bank,
                              String controlDate) {
        return new RawRow(
                0,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                type,
                "1",
                basis,
                bd(amount),
                1L,
                7,
                "Киев ОПТ",
                "*РОЗНИЦА",
                cash,
                bank,
                "CLASSIC",
                null,
                null,
                controlDate == null ? null : LocalDateTime.parse(controlDate + "T00:00:00"),
                "Client",
                note
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
