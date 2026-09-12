package org.example.proect.lavka.service.folio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Availability;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.StockoutDemand;

/** An estimate, never a reconstructed sale. The scenario applies its own uplift cap. */
public final class FolioStockoutDemand {
    private static final String METHOD = "SALES_ON_AVAILABLE_END_OF_DAY_DAYS_V1";
    private FolioStockoutDemand() { }

    public static StockoutDemand estimate(Availability availability, BigDecimal sales) {
        if (availability == null || !"MEASURED".equals(availability.status())
                || availability.availableDays() == null || availability.stockoutDays() == null
                || sales == null || sales.signum() < 0)
            return new StockoutDemand("HISTORY_NOT_READY", METHOD, null, null);
        if (availability.availableDays() <= 0)
            return new StockoutDemand("NO_AVAILABLE_DAYS", METHOD, sales, null);
        BigDecimal lost = sales.multiply(BigDecimal.valueOf(availability.stockoutDays()))
                .divide(BigDecimal.valueOf(availability.availableDays()), 6, RoundingMode.HALF_UP);
        return new StockoutDemand("ESTIMATED", METHOD, sales, lost);
    }
}
