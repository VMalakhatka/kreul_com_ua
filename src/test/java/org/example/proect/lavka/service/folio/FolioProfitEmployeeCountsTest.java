package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.CalculateRequest;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FolioProfitEmployeeCountsTest {
    @ParameterizedTest @CsvSource({"4,3,0.4285714286,3.00", "0,7,1.0000000000,7.00", "7,0,0.0000000000,0.00"})
    void countsAndShares(Integer kyiv,Integer odesa,String share,String amount) {
        var a=FolioProfitTaxAllocation.resolve(kyiv,odesa,null);
        assertThat(a.mode()).isEqualTo("EMPLOYEE_COUNTS");
        assertThat(a.totalCount()).isEqualTo(7);
        assertThat(a.odesaShare()).isEqualByComparingTo(share);
        assertThat(a.odesaAmount(new BigDecimal("7"))).isEqualByComparingTo(amount);
        assertThat(a.odesaShare().add(a.kyivShare())).isEqualByComparingTo("1");
    }
    @Test void defaultsAndExactRationalRoundingInsteadOfTruncatedShare() {
        var a=FolioProfitTaxAllocation.resolve(null,null,null);
        assertThat(a.kyivCount()).isEqualTo(4); assertThat(a.odesaCount()).isEqualTo(3);
        assertThat(a.odesaAmount(new BigDecimal("1000000000"))).isEqualByComparingTo("428571428.57");
        var half=FolioProfitTaxAllocation.resolve(1,1,null);
        assertThat(half.odesaAmount(new BigDecimal("0.01"))).isEqualByComparingTo("0.01");
        assertThat(half.odesaAmount(new BigDecimal("-0.01"))).isEqualByComparingTo("-0.01");
        assertThat(FolioProfitTaxAllocation.resolve(Integer.MAX_VALUE,Integer.MAX_VALUE,null).totalCount()).isEqualTo(4294967294L);
    }
    @ParameterizedTest @CsvSource({"0,0", "-1,7", "4,-1", ",3", "4,"})
    void invalidCounts(Integer kyiv,Integer odesa) {
        assertThatThrownBy(()->FolioProfitTaxAllocation.resolve(kyiv,odesa,null))
                .isInstanceOfSatisfying(FolioAccountValidationException.class,e->assertThat(e.getCode()).isEqualTo("EMPLOYEE_COUNTS_INVALID"));
    }
    @Test void legacyShareStillExplicitAndConflictNeverSilent() {
        var a=FolioProfitTaxAllocation.resolve(null,null,new BigDecimal("0.41"));
        assertThat(a.mode()).isEqualTo("LEGACY_SHARE");
        assertThat(a.kyivCount()).isNull(); assertThat(a.totalCount()).isNull();
        assertThat(a.odesaAmount(new BigDecimal("7"))).isEqualByComparingTo("2.87");
        assertThatThrownBy(()->FolioProfitTaxAllocation.resolve(4,3,new BigDecimal("0.41")))
                .isInstanceOfSatisfying(FolioAccountValidationException.class,e->assertThat(e.getCode()).isEqualTo("TAX_ALLOCATION_INPUT_CONFLICT"));
    }
    @ParameterizedTest @ValueSource(strings={"1.5","true","2147483648","\"1.5\""})
    void jsonDoesNotSilentlyCoerceEmployeeCount(String token) {
        assertThatThrownBy(()->new ObjectMapper().readValue("{\"kyivEmployeeCount\":"+token+"}",CalculateRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void realServiceAuditExpenseLinesAndControlsAgree() throws Exception {
        var dao=mock(FolioProfitReportDao.class);
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        // Pool remains recognized from name, not only purposeCode: the new filter is informational.
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(List.of(
                payment(1,"MALAFOP","0.01"),payment(2,"MALAFOP","0.01"),payment(3,"KONDFOP","2.00")));
        var service=new FolioProfitReportService(dao,new FolioProfitClassifier(),new FolioProfitReportProperties());
        var report=service.calculate(new FolioProfitReportService.Request("2026-07",null,null,null,null,
                BigDecimal.ZERO,null,null,BigDecimal.ZERO,1,1),true);
        assertThat(report.inputs().allocationMode()).isEqualTo("EMPLOYEE_COUNTS");
        assertThat(report.inputs().totalEmployeeCount()).isEqualTo(2);
        var retail=report.expenseLines().stream().filter(l->l.lineId().equals("ODESA_TAX_MALAFOP")).findFirst().orElseThrow();
        assertThat(retail.label()).isEqualTo("Налоги Розн");
        assertThat(retail.filters().purposeCodes()).containsExactly("МАЛАФОП");
        assertThat(retail.amount()).isEqualByComparingTo("0.02"); // not rounded pool 0.01
        var wholesale=report.expenseLines().stream().filter(l->l.lineId().equals("KYIV_TAX_KONDFOP")).findFirst().orElseThrow();
        assertThat(wholesale.label()).isEqualTo("Налоги ОПТ");
        assertThat(wholesale.filters().purposeCodes()).containsExactly("КОНДФОП");
        assertThat(wholesale.amount()).isEqualByComparingTo("2.00");
        assertThat(report.documents().stream().map(FolioProfitReportResponse.DocumentLine::odesaAllocation).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo("0.02");
        assertThat(report.controls().operatingExpenseTotal()).isEqualByComparingTo("2.02");
        String fixture=System.getProperty("folio.profit.headcount.fixture.output");
        if(fixture!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture),
                new ObjectMapper().findAndRegisterModules().writeValueAsString(report));
        var invalid=service.calculate(new FolioProfitReportService.Request("2026-07",null,null,null,null,
                BigDecimal.ZERO,null,null,BigDecimal.ZERO,0,0),true);
        assertThat(invalid.sections().get("EXPENSE_INPUTS").errorCode()).isEqualTo("EMPLOYEE_COUNTS_INVALID");
        assertThat(invalid.complete()).isFalse();
        assertThat(invalid.inputs().allocationMode()).isEqualTo("UNAVAILABLE");
    }
    private static PaymentRow payment(long id,String name,String amount) {
        return new PaymentRow(id,"TEST-"+id,LocalDate.of(2026,7,1),new BigDecimal(amount),false,1,
                "НАЛОГИ",null,name,"НАЛОГИ","2026 07",null);
    }
}
