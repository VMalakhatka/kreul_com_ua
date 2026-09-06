package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FolioProductAvailabilityHistoryTest {
    @Test void reconstructsDayEndsAndExcludesUnfinishedCaptureDay() {
        var start = LocalDate.of(2026, 6, 1);
        var rows = FolioProductAvailabilityHistory.build(card("0", "1"), Map.of(
                start.plusDays(20), BigDecimal.ONE), start, start.plusMonths(1));
        assertThat(rows.get(0).quality()).isEqualTo("RECONCILED");
        assertThat(Long.bitCount(rows.get(0).availableMask())).isEqualTo(10);
        assertThat(Long.bitCount(rows.get(0).knownMask())).isEqualTo(30);
        assertThat(rows.get(1).knownMask()).isZero();
    }

    @Test void mismatchingCurrentBalanceDoesNotProduceAnEstimate() {
        var start = LocalDate.of(2026, 6, 1);
        var rows = FolioProductAvailabilityHistory.build(card("0", "2"),
                Map.of(start, BigDecimal.ONE), start, start.plusMonths(1));
        assertThat(rows).allSatisfy(m -> {
            assertThat(m.quality()).isEqualTo("DATA_INCOMPLETE");
            assertThat(m.knownMask()).isZero();
            assertThat(m.availableMask()).isZero();
            assertThat(m.reconciliationDifference()).isEqualByComparingTo("-1");
        });
    }

    @Test void negativeBalanceIsStockoutAndLeapDayAndSameDayNetAreHandled() {
        var start = LocalDate.of(2024, 2, 1);
        var rows = FolioProductAvailabilityHistory.build(card("-1", "1"),
                Map.of(start.plusDays(28), new BigDecimal("2")), start, LocalDate.of(2024, 3, 1));
        assertThat(Long.bitCount(rows.get(0).knownMask())).isEqualTo(29);
        assertThat(Long.bitCount(rows.get(0).negativeMask())).isEqualTo(28);
        assertThat(Long.bitCount(rows.get(0).availableMask())).isEqualTo(1);
    }

    public static ProductCard card(String opening, String physical) {
        BigDecimal z = BigDecimal.ZERO;
        return new ProductCard("SKU", "Product", "digest", null, "MISSING", z,
                new BigDecimal(physical), z, z, z, z, z, z, new BigDecimal(opening), z,
                0, null, null, null, null, 0, false);
    }
}
