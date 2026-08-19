package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Capture;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MonthlyActivity;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Warehouse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FolioProductEconomicsCalculatorTest {

    private final FolioProductEconomicsCalculator calculator =
            new FolioProductEconomicsCalculator();

    @Test
    void calculatesCapitalGrossProfitTurnsAndCoverage() {
        LocalDate asOf = LocalDate.of(2026, 8, 19);
        ProductCard card = card("SKU-1", bd("25"), bd("5"), bd("10"), 4);
        MonthlyActivity activity = new MonthlyActivity(
                "SKU-1", LocalDate.of(2026, 8, 1),
                bd("20"), bd("200"), bd("5"), bd("100"), bd("50"),
                bd("0"), bd("0"), LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 8, 18), bd("15"), bd("150"));

        var result = calculator.calculate(capture(card, activity),
                LocalDate.of(2026, 7, 1), asOf);

        assertThat(result.monthly()).hasSize(1);
        var august = result.monthly().get(0);
        assertThat(august.openingQuantity()).isEqualByComparingTo("0");
        assertThat(august.closingQuantity()).isEqualByComparingTo("15");
        assertThat(august.grossProfit()).isEqualByComparingTo("50");
        var current = result.current().get(0);
        assertThat(current.availableQuantity()).isEqualByComparingTo("20");
        assertThat(current.inventoryValue()).isEqualByComparingTo("250");
        assertThat(current.grossProfit90d()).isEqualByComparingTo("50");
        assertThat(current.coverageDays()).isEqualByComparingTo("360.00");
        assertThat(current.healthStatus()).isEqualTo("OVERSTOCK");
        assertThat(result.alerts()).extracting(FolioProductEconomicsCalculator.Alert::code)
                .containsExactly("OVERSTOCK");
    }

    @Test
    void emptyNewCardIsNormalAndDoesNotCreateAnAlert() {
        ProductCard card = card("NEW-1", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0);
        var result = calculator.calculate(capture(card),
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 8, 19));

        assertThat(result.monthly()).isEmpty();
        assertThat(result.current()).singleElement()
                .extracting(FolioProductEconomicsCalculator.CurrentMetric::healthStatus)
                .isEqualTo("NEW");
        assertThat(result.alerts()).isEmpty();
    }

    @Test
    void nonZeroStockWithZeroAccountingPriceAfterMovementsIsDataIssue() {
        ProductCard card = card("BROKEN-1", bd("3"), BigDecimal.ZERO,
                BigDecimal.ZERO, 2);
        var result = calculator.calculate(capture(card),
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 8, 19));

        assertThat(result.current().get(0).healthStatus()).isEqualTo("DATA_ISSUE");
        assertThat(result.alerts()).extracting(FolioProductEconomicsCalculator.Alert::code)
                .containsExactly("DATA_ISSUE");
    }

    private static Capture capture(ProductCard card, MonthlyActivity... activity) {
        return new Capture(new Warehouse("Paint_Rus", 12, "Lab", bd("1000"), null),
                "digest", List.of(card), List.of(activity), card.movementCount());
    }

    private static ProductCard card(String sku, BigDecimal physical,
                                    BigDecimal reserved, BigDecimal price,
                                    long movementCount) {
        return new ProductCard(sku, sku, "digest", BigDecimal.ZERO, physical,
                reserved, physical, physical.multiply(price), price,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                movementCount, null, null, null, null, 0, false);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
