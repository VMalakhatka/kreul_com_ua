package org.example.proect.lavka.dao.folio;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitReadBudgetTest {
    @Test void limitsEachQueryToRemainingBudgetAndCleansThreadLocal() {
        AtomicLong clock = new AtomicLong();
        try (var budget = new FolioProfitReadBudget(90, 30, clock::get)) {
            assertThat(FolioProfitReadBudget.remainingQuerySeconds()).isEqualTo(30);
            clock.set(89_100_000_000L);
            assertThat(FolioProfitReadBudget.remainingQuerySeconds()).isEqualTo(1);
            clock.set(90_000_000_000L);
            assertThatThrownBy(FolioProfitReadBudget::remainingQuerySeconds)
                    .isInstanceOf(QueryTimeoutException.class);
        }
        assertThat(FolioProfitReadBudget.remainingQuerySeconds()).isEqualTo(30);
    }

    @Test void exhaustedBudgetDoesNotAcquireAnotherConnection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicLong clock = new AtomicLong();
        try (var budget = new FolioProfitReadBudget(1, 30, clock::get)) {
            clock.set(1_000_000_000L);
            assertThatThrownBy(() -> new FolioProfitReportDao(jdbc).findGrossMargins(
                    LocalDate.of(2026,7,1), LocalDate.of(2026,8,1)))
                    .isInstanceOf(QueryTimeoutException.class);
            verifyNoInteractions(jdbc);
        }
    }

    @Test void nestedScopeRestoresPreviousBudget() {
        try (var outer = new FolioProfitReadBudget(10, 7)) {
            try (var inner = new FolioProfitReadBudget(10, 2)) {
                assertThat(FolioProfitReadBudget.remainingQuerySeconds()).isEqualTo(2);
            }
            assertThat(FolioProfitReadBudget.remainingQuerySeconds()).isEqualTo(7);
        }
    }
}
