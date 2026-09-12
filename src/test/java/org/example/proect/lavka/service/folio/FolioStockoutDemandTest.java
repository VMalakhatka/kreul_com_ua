package org.example.proect.lavka.service.folio;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Availability;
import static org.assertj.core.api.Assertions.assertThat;

class FolioStockoutDemandTest {
    private Availability history(String status, long available, long absent) {
        return new Availability(status, "PHYSICAL_END_OF_DAY", true, null, available + absent,
                available + absent, available, absent, null, null, List.of());
    }

    @Test void extrapolatesOnlyTheSalesAlignedWithAvailableDays() {
        var value = FolioStockoutDemand.estimate(history("MEASURED", 10, 12), new BigDecimal("100"));
        assertThat(value.status()).isEqualTo("ESTIMATED");
        assertThat(value.estimatedLostSales()).isEqualByComparingTo("120");
        assertThat(FolioStockoutDemand.estimate(history("MEASURED", 20, 1), new BigDecimal("100"))
                .estimatedLostSales()).isEqualByComparingTo("5");
    }

    @Test void unknownOrZeroObservationDaysAreNotZeroLostSales() {
        assertThat(FolioStockoutDemand.estimate(history("DATA_INCOMPLETE", 10, 12), BigDecimal.TEN)
                .estimatedLostSales()).isNull();
        assertThat(FolioStockoutDemand.estimate(history("MEASURED", 0, 30), BigDecimal.ZERO)
                .status()).isEqualTo("NO_AVAILABLE_DAYS");
        assertThat(FolioStockoutDemand.estimate(history("MEASURED", 30, 0), BigDecimal.TEN)
                .estimatedLostSales()).isZero();
    }
}
