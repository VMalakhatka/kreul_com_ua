package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
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
import static org.mockito.Mockito.when;

class FolioCustomerBalanceServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 13);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T10:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void calculatesWorkbookTotalsAndClassifications() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.load(eq("КЛИЕНТ"), any(), any(), anyList(), anyBoolean()))
                .thenReturn(new ProcedureResult(
                        "КЛИЕНТ",
                        "Тестовый клиент",
                        bd("100"),
                        BigDecimal.ZERO,
                        null,
                        List.of(
                                row(0, "Р", "1", "РЕЛ Отсрочка", "1000", "0", "0", "2026-08-01", "2026-09-01"),
                                row(1, "ПК", "2", "ПРД Предоплата", "-300", "300", "0", "2026-08-02", null),
                                row(2, "Р", "3", "РЕЛ Реализация", "400", "0", "0", "2026-08-03", "2026-08-10"),
                                row(3, "Р", "4", "", "-50", "0", "0", "2026-08-04", null),
                                row(4, "ПБ", "5", "ПРД Банковская предоплата", "-200", "0", "200", "2026-08-05", null)
                        )
                ));

        var response = new FolioCustomerBalanceService(dao, CLOCK).get(
                "КЛИЕНТ",
                LocalDate.of(2026, 1, 1),
                null,
                true
        );

        assertThat(response.summary().openingBalance()).isEqualByComparingTo("100");
        assertThat(response.summary().expenseTotal()).isEqualByComparingTo("1400");
        assertThat(response.summary().receiptTotal()).isEqualByComparingTo("50");
        assertThat(response.summary().bankPaymentTotal()).isEqualByComparingTo("200");
        assertThat(response.summary().cashPaymentTotal()).isEqualByComparingTo("300");
        assertThat(response.summary().commonDebt()).isEqualByComparingTo("1450");
        assertThat(response.summary().deferredAmount()).isEqualByComparingTo("1000");
        assertThat(response.summary().overdueDeferredAmount()).isEqualByComparingTo("400");
        assertThat(response.summary().prepaymentAmount()).isEqualByComparingTo("500");
        assertThat(response.summary().payableNow()).isEqualByComparingTo("450");

        assertThat(response.rows()).hasSize(6);
        assertThat(response.rows().get(0).openingBalanceRow()).isTrue();
        assertThat(response.rows().get(1).deferred()).isTrue();
        assertThat(response.rows().get(2).prepayment()).isTrue();
        assertThat(response.rows().get(3).overdueDeferred()).isTrue();
        assertThat(response.rows().get(5).bankPayment()).isEqualByComparingTo("200");
        assertThat(response.rows().get(5).prepayment()).isTrue();
        assertThat(response.rows().get(5).prepaymentAmount()).isEqualByComparingTo("200");
        assertThat(response.rows().get(5).balanceAfter()).isEqualByComparingTo("950");
    }

    @Test
    void doesNotTreatFutureControlDateAsDeferredWithoutRelBasis() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.load(eq("A"), any(), any(), anyList(), anyBoolean()))
                .thenReturn(new ProcedureResult(
                        "A",
                        "Client",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        List.of(expenseRowWithBasis(
                                0,
                                "DOC",
                                "555053Woo o 11.08.2026",
                                "3354.12",
                                "2026-08-11",
                                "2026-08-14"
                        ))
                ));

        var response = new FolioCustomerBalanceService(dao, CLOCK)
                .get("A", LocalDate.of(2026, 8, 1), null, true);

        assertThat(response.rows().get(1).deferred()).isFalse();
        assertThat(response.rows().get(1).overdueDeferred()).isFalse();
        assertThat(response.rows().get(1).basis()).startsWith("555053");
        assertThat(response.rows().get(1).deferredAmount()).isEqualByComparingTo("0");
        assertThat(response.summary().deferredAmount()).isEqualByComparingTo("0");
        assertThat(response.summary().payableNow()).isEqualByComparingTo("3354.12");
    }

    @Test
    void usesFullHistoryWhenStartDateIsMissingAndAlwaysEndsToday() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.load(eq("A"), eq(FolioCustomerBalanceService.FOLIO_MIN_DATE), eq(AS_OF),
                eq(List.of(7, 1)), eq(true)))
                .thenReturn(new ProcedureResult("A", "Client", BigDecimal.ZERO,
                        BigDecimal.ZERO, null, List.of()));

        var response = new FolioCustomerBalanceService(dao, CLOCK)
                .get(" A ", null, List.of(7, 1, 7), null);

        assertThat(response.partner().shortName()).isEqualTo("A");
        assertThat(response.filters().dateFrom()).isEqualTo(FolioCustomerBalanceService.FOLIO_MIN_DATE);
        assertThat(response.filters().dateTo()).isEqualTo(AS_OF);
        assertThat(response.filters().asOfDate()).isEqualTo(AS_OF);
        assertThat(response.filters().warehouseIds()).containsExactly(7, 1);
        assertThat(response.filters().includeServicePayments()).isTrue();
    }

    @Test
    void rejectsFutureStartDateBeforeCallingDatabase() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        FolioCustomerBalanceService service = new FolioCustomerBalanceService(dao, CLOCK);

        assertThatThrownBy(() -> service.get(
                "A",
                LocalDate.of(2026, 8, 14),
                List.of(),
                true
        )).isInstanceOf(FolioAccountValidationException.class)
                .hasMessageContaining("dateFrom");
    }

    @Test
    void placesPaymentsBeforeDocumentsCreatedOnTheSameDate() {
        FolioCustomerBalanceDao dao = mock(FolioCustomerBalanceDao.class);
        when(dao.load(eq("A"), any(), any(), anyList(), anyBoolean()))
                .thenReturn(new ProcedureResult(
                        "A",
                        "Client",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null,
                        List.of(
                                row(0, "Р", "DOC", "", "100", "0", "0", "2026-08-01", null),
                                row(1, "ПБ", "PAY", "", "-100", "0", "100", "2026-08-01", null)
                        )
                ));

        var response = new FolioCustomerBalanceService(dao, CLOCK)
                .get("A", LocalDate.of(2026, 8, 1), null, true);

        assertThat(response.rows().get(1).documentNumber()).isEqualTo("PAY");
        assertThat(response.rows().get(2).documentNumber()).isEqualTo("DOC");
        assertThat(response.summary().commonDebt()).isEqualByComparingTo("0");
    }

    private static RawRow row(int order,
                              String type,
                              String number,
                              String noteOrBasis,
                              String amount,
                              String rawCash,
                              String rawBank,
                              String documentDate,
                              String controlDate) {
        boolean basisMarker = noteOrBasis.startsWith("РЕЛ");
        return new RawRow(
                order,
                LocalDateTime.parse(documentDate + "T00:00:00"),
                type,
                number,
                basisMarker ? noteOrBasis : null,
                bd(amount),
                (long) order + 1,
                7,
                "Киев ОПТ",
                "*РОЗНИЦА",
                bd(rawCash),
                bd(rawBank),
                "CLASSIC",
                null,
                null,
                controlDate == null ? null : LocalDateTime.parse(controlDate + "T00:00:00"),
                "Тестовый клиент",
                basisMarker ? null : noteOrBasis
        );
    }

    private static RawRow expenseRowWithBasis(int order,
                                              String number,
                                              String basis,
                                              String amount,
                                              String documentDate,
                                              String controlDate) {
        return new RawRow(
                order,
                LocalDateTime.parse(documentDate + "T00:00:00"),
                "Р",
                number,
                basis,
                bd(amount),
                (long) order + 1,
                7,
                "Киев ОПТ",
                "*РОЗНИЦА",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "CLASSIC",
                null,
                LocalDateTime.parse(documentDate + "T00:00:00"),
                LocalDateTime.parse(controlDate + "T00:00:00"),
                "Тестовый клиент",
                null
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
