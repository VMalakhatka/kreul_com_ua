package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolioProfitReportPartialTest {
    private FolioProfitReportDao dao;
    private FolioProfitReportService service;
    private final FolioProfitReportService.Request request = new FolioProfitReportService.Request(
            "2026-07", null, null, null, null, null, null, null);

    @BeforeEach void setup() {
        dao = mock(FolioProfitReportDao.class);
        service = new FolioProfitReportService(dao, new FolioProfitClassifier(), new FolioProfitReportProperties());
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        when(dao.findWarehouseNames(any())).thenReturn(Map.of(1,"Warehouse A",7,"Warehouse B",12,"Warehouse C",5,"Warehouse D"));
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of(
                new FolioProfitReportDao.GrossMarginRow(1,"",false,true,1,new BigDecimal("100")),
                new FolioProfitReportDao.GrossMarginRow(5,"",false,true,1,new BigDecimal("200"))));
    }

    @Test void expenseFailurePreservesGrossInventoryAndMasterClassWithoutInventingZeros() throws Exception {
        when(dao.findPaymentCandidates(any(),any(),anyString()))
                .thenThrow(new DataAccessResourceFailureException("private SQL and connection details"));
        var report = service.calculate(request, true);
        assertThat(report.ok()).isTrue();
        assertThat(report.complete()).isFalse();
        assertThat(report.sections().get("EXPENSES").errorCode()).isEqualTo("PROFIT_REPORT_SOURCE_UNAVAILABLE");
        assertThat(report.sections().get("MASTER_CLASS").status()).isEqualTo("AVAILABLE");
        assertThat(report.controls()).isNull();
        assertThat(report.expenseLines()).isEmpty();
        assertThat(report.documents()).isEmpty();
        assertThat(report.inventory()).hasSize(2);
        assertThat(report.cities()).allSatisfy(city -> {
            assertThat(city.baseGrossProfit()).isNotNull();
            assertThat(city.operatingExpenses()).isNull();
            assertThat(city.profit()).isNull();
        });
        assertThat(report.warnings()).noneMatch(w -> w.code().equals("IMPORT_TRANSPORT_CAPITALIZED"));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(report);
        assertThat(json).contains("\"profit\":null", "\"controls\":null")
                .doesNotContain("private SQL", "connection details");
        String fixture = System.getProperty("folio.profit.partial.fixture.output");
        if (fixture != null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture), json);
    }

    @Test void masterClassFailureOnlyInvalidatesDependentOdesaProfit() {
        when(dao.masterClassArticleExists(anyString())).thenThrow(new QueryTimeoutException("query failed"));
        var report = service.calculate(request, true);
        assertThat(report.sections().get("MASTER_CLASS").errorCode()).isEqualTo("PROFIT_REPORT_READ_TIMEOUT");
        assertThat(report.masterClass().income()).isNull();
        assertThat(city(report,"KYIV").profit()).isEqualByComparingTo("100");
        assertThat(city(report,"ODESA").profit()).isNull();
        assertThat(city(report,"ODESA").baseGrossProfit()).isEqualByComparingTo("200");
        assertThat(report.controls()).isNotNull();
        assertThat(report.inventory()).hasSize(2);
    }

    @Test void missingMasterClassArticleIsNotZeroIncome() {
        when(dao.masterClassArticleExists(anyString())).thenReturn(false);
        var report = service.calculate(request, false);
        assertThat(report.sections().get("MASTER_CLASS").status()).isEqualTo("UNAVAILABLE");
        assertThat(report.masterClass().netContribution()).isNull();
        assertThat(city(report,"ODESA").profit()).isNull();
        assertThat(city(report,"KYIV").profit()).isNotNull();
    }

    @Test void failedKyivInventoryDoesNotHideOdesaInventoryOrProfit() {
        when(dao.findInventoryOpenings(eq(List.of(1,7,12))))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        var report = service.calculate(request, false);
        assertThat(report.sections().get("INVENTORY_KYIV").status()).isEqualTo("UNAVAILABLE");
        assertThat(report.sections().get("INVENTORY_ODESA").status()).isEqualTo("AVAILABLE");
        assertThat(report.inventory()).extracting(FolioProfitReportResponse.InventoryResult::city).containsExactly("ODESA");
        assertThat(report.cities()).allSatisfy(city -> assertThat(city.profit()).isNotNull());
    }

    @Test void grossFailureKeepsExpensesAndDoesNotProduceNegativeExpenseAsProfit() {
        when(dao.findGrossMargins(any(),any())).thenThrow(new DataAccessResourceFailureException("unavailable"));
        var report = service.calculate(request,false);
        assertThat(report.sections().get("EXPENSES").status()).isEqualTo("AVAILABLE");
        assertThat(report.expenseLines()).hasSize(32);
        assertThat(report.cities()).allSatisfy(city -> {
            assertThat(city.profit()).isNull();
            assertThat(city.baseGrossProfit()).isNull();
            assertThat(city.operatingExpenses()).isNotNull();
        });
    }

    @Test void invalidMonthRemainsFatalAndDoesNotReadDatabase() {
        clearInvocations(dao);
        assertThatThrownBy(() -> service.calculate(new FolioProfitReportService.Request(
                "bad",null,null,null,null,null,null,null), false))
                .isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao);
    }

    private FolioProfitReportResponse.CityResult city(FolioProfitReportResponse report, String name) {
        return report.cities().stream().filter(city -> city.city().equals(name)).findFirst().orElseThrow();
    }
}
