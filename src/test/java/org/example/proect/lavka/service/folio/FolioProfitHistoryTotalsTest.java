package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.Revision;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FolioProfitHistoryTotalsTest {
    @Test void sumsMoneyExactlyButUsesOnlyFirstAndLastInventoryBoundaries() {
        var july=revision(FolioProfitHistoryServiceTest.report("2025-07",false));
        var august=revision(FolioProfitHistoryServiceTest.report("2025-08",false));
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(july,august));
        assertThat(totals.grossProfit()).isEqualByComparingTo("200.02");
        assertThat(totals.operatingExpenses()).isEqualByComparingTo("40.02");
        assertThat(totals.profit()).isEqualByComparingTo("160.00");
        assertThat(totals.openingAccountingValue()).isEqualByComparingTo("300");
        assertThat(totals.closingAccountingValue()).isEqualByComparingTo("400");
        assertThat(totals.accountingValueChange()).isEqualByComparingTo("100");
    }
    @Test void missingMonthNeverBecomesZeroOrPartialSum() {
        var missing=new Revision(false,"MISSING","Paint_Ua","2025-08",null,null,null,null,null,false,false,null,null,null,null,null);
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(revision(FolioProfitHistoryServiceTest.report("2025-07",false)),missing));
        assertThat(totals.profit()).isNull();
        assertThat(totals.operatingExpenses()).isNull();
        assertThat(totals.closingAccountingValue()).isNull();
        assertThat(totals.complete()).isFalse();
    }
    private Revision revision(FolioProfitReportResponse report) { return new Revision(true,"COMPLETED","Paint_Ua",report.month(),1L,"id",1L,1L,
            "COMPLETED",true,true,null,null,null,report,null); }
}
