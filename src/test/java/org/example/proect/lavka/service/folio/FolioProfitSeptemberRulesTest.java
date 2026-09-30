package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.*;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitSeptemberRulesTest {
    final FolioProfitReportDao dao = mock(FolioProfitReportDao.class);
    final FolioProfitReportProperties properties = new FolioProfitReportProperties();
    final FolioProfitReportService service = new FolioProfitReportService(dao, new FolioProfitClassifier(), properties);
    final FolioProfitReportService.Request request = new FolioProfitReportService.Request("2026-07",
            null,null,null,null,BigDecimal.ZERO,null,null);
    @BeforeEach void setup() {
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        when(dao.findWarehouseNames(any())).thenReturn(Map.of(1,"Kyiv",7,"Wholesale",12,"Storage",5,"Odesa"));
    }
    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static PaymentRow payment(long id, String code, String operation, Integer warehouse, boolean bank) {
        return new PaymentRow(id,"TEST",LocalDate.of(2026,7,1),bd("10"),bank,warehouse,null,code,"Synthetic",operation,"2026 07");
    }
    static MasterClassMovementRow mk(long id, int warehouse, String type, String operation, String amount, String cost) {
        return new MasterClassMovementRow(id,"TEST", "TEST", "",1,LocalDate.of(2026,7,1),warehouse,"Мастер-Класс июль",
                type,type,operation,type.equals("П"),type.equals("П"),true,true,BigDecimal.ONE,bd(amount),bd(amount),bd(cost),"К");
    }
    static GrossMarginRow gross(int warehouse, String type, String amount) {
        return new GrossMarginRow(warehouse,type,false,true,1,bd(amount));
    }

    @Test void bankIsAllWarehousesKyivAndOdesaExpenseRulesRequireExactOperation() {
        var payments = new ArrayList<PaymentRow>();
        long id = 0;
        for (Integer warehouse : Arrays.asList(1,5,7,12,null)) for (boolean bank : List.of(false,true))
            payments.add(payment(++id,"БАНКОВСК","РАСХОДЫ СЕТИ",warehouse,bank));
        payments.add(payment(++id,"НЕРЕГ ОД","РАСХОДЫ СЕТИ",1,false));
        payments.add(payment(++id,"ТРАНСПОД","РАСХОДЫ СЕТИ",7,true));
        for (String old : List.of("НЕРЕГМИХ","НЕРЕГДОН","НЕРЕГ ОД extra"))
            payments.add(payment(++id,old,"РАСХОДЫ СЕТИ",5,false));
        for (String code : List.of("НЕРЕГ ОД","ТРАНСПОД","БАНКОВСК"))
            payments.add(payment(++id,code,"РАСХОДЫ ОДЕССЫ",5,false));
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(payments);
        var report = service.calculate(request,true);
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("100");
        assertThat(report.cities().get(1).operatingExpenses()).isEqualByComparingTo("20");
        assertThat(report.controls().unclassifiedDocumentCount()).isEqualTo(6);
        assertThat(report.complete()).isFalse();
        assertThat(report.expenseLines()).filteredOn(l -> l.lineId().equals("KYIV_BANK_SERVICES")).singleElement().satisfies(l -> {
            assertThat(l.documentCount()).isEqualTo(10);
            assertThat(l.filters().cashWarehouseMode()).isEqualTo("ALL");
            assertThat(l.filters().bankWarehouseMode()).isEqualTo("ALL");
            assertThat(l.filters().operationRequired()).isTrue();
        });
    }

    @Test void bothCitiesMasterClassReplacesItsBaseMarginOnceAndLegacyAliasStaysOdesa() throws Exception {
        when(dao.findGrossMargins(any(),any())).thenReturn(List.of(gross(1,"К","80"),gross(5,"К","150"),gross(12,"К","999")));
        when(dao.findMasterClassMovements(eq(1),anyString(),any(),any())).thenReturn(List.of(
                mk(1,1,"Р","", "100","20"), mk(2,1,"П","ВОЗВРАТ","10","0")));
        when(dao.findMasterClassMovements(eq(5),anyString(),any(),any())).thenReturn(List.of(
                mk(3,5,"Р","", "200","50"), mk(4,5,"П","ВОЗВРАТ","20","0")));
        var report = service.calculate(request,true);
        assertThat(report.complete()).isTrue();
        assertThat(report.cities().get(0).baseGrossProfit()).isEqualByComparingTo("80");
        assertThat(report.cities().get(0).grossProfit()).isEqualByComparingTo("90");
        assertThat(report.cities().get(1).grossProfit()).isEqualByComparingTo("180");
        assertThat(report.masterClassesByCity().get("KYIV").grossAdjustmentApplied()).isEqualByComparingTo("10");
        assertThat(report.masterClassesByCity().get("ODESA").grossAdjustmentApplied()).isEqualByComparingTo("30");
        assertThat(report.masterClass()).isEqualTo(report.masterClassesByCity().get("ODESA"));
        assertThat(report.masterClassDocuments()).isEqualTo(report.masterClassDocumentsByCity().get("ODESA"));
        assertThat(report.masterClassDocumentsByCity().get("KYIV")).hasSize(2).allMatch(d -> d.warehouseId()==1);
        assertThat(report.inputs().kyivWarehouseIds()).containsExactly(1,7);
        assertThat(report.inputs().kyivStockWarehouseIds()).containsExactly(1,7,12);
        assertThat(FolioProfitHistoryService.auditComplete(report)).isTrue();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var restored = mapper.readValue(mapper.writeValueAsString(report),FolioProfitReportResponse.class);
        assertThat(restored.masterClassesByCity()).isEqualTo(report.masterClassesByCity());
        assertThat(restored.masterClassDocumentsByCity()).isEqualTo(report.masterClassDocumentsByCity());
        assertThat(restored.grossProfitLines()).isEqualTo(report.grossProfitLines());
        assertThat(restored.cities()).isEqualTo(report.cities());
        assertThat(restored.calculatedAt().toInstant()).isEqualTo(report.calculatedAt().toInstant());
        String fixture = System.getProperty("folio.profit.v930.fixture.output");
        if (fixture != null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Test void kyivFailureDoesNotInvalidateOdesaAndKyivAuditTruncationBlocksFullAudit() {
        when(dao.findMasterClassMovements(eq(1),anyString(),any(),any())).thenThrow(new QueryTimeoutException("synthetic"));
        var failure = service.calculate(request,true);
        assertThat(failure.masterClassesByCity().get("KYIV").income()).isNull();
        assertThat(failure.cities().get(0).profit()).isNull();
        assertThat(failure.cities().get(1).profit()).isNotNull();
        assertThat(failure.sections().get("MASTER_CLASS_KYIV").errorCode()).isEqualTo("PROFIT_REPORT_READ_TIMEOUT");
        doReturn(List.of(mk(1,1,"Р","","100","0"),mk(2,1,"Р","","200","0")))
                .when(dao).findMasterClassMovements(eq(1),anyString(),any(),any());
        properties.setMaxAuditDocuments(1);
        var truncated = service.calculate(request,true);
        assertThat(truncated.masterClass().auditTruncated()).isFalse();
        assertThat(truncated.masterClassesByCity().get("KYIV").auditTruncated()).isTrue();
        assertThat(truncated.masterClassDocumentsByCity().get("KYIV")).hasSize(1);
        assertThat(FolioProfitHistoryService.auditComplete(truncated)).isFalse();
    }

    @Test void categoryBreakdownRetainsOtherAndExactUnicodeAndExcludesWarehouse12() {
        when(dao.findGrossMargins(any(),any())).thenReturn(List.of(gross(1,"S","10"),gross(7,"H","20"),
                gross(1,"П","30"),gross(7,"Д","40"),gross(1,"К","50"),gross(7,"C","60"),
                gross(1,"С","70"),gross(12,"S","999"),gross(1,"Я","888"),gross(5,"S","80"),
                new GrossMarginRow(1,"S",true,true,1,bd("777")),new GrossMarginRow(1,"S",false,false,1,bd("666"))));
        var report = service.calculate(request,false);
        assertThat(report.grossProfitLines()).hasSize(12);
        assertThat(report.grossProfitLines()).filteredOn(l -> l.lineId().equals("KYIV_GROSS_OTHER"))
                .singleElement().satisfies(l -> {
                    assertThat(l.organizationTypes()).containsExactly("C","С");
                    assertThat(l.amount()).isEqualByComparingTo("130");
                });
        for (var city : report.cities()) {
            var amount = report.grossProfitLines().stream().filter(l -> l.city().equals(city.city()))
                    .map(l -> l.amount()).reduce(BigDecimal.ZERO,BigDecimal::add);
            assertThat(amount).isEqualByComparingTo(city.baseGrossProfit());
        }
        assertThat(report.cities().get(0).baseGrossProfit()).isEqualByComparingTo("280");
    }

    @Test void categoryRoundingReconcilesWithoutFabricatingUnknownAmountWhenNoUnknownRows() {
        var lines = FolioProfitGrossLines.rows(List.of(gross(1,"S","0.004"),gross(1,"П","0.004"),gross(1,"Д","0.004")),List.of(1),List.of(5));
        assertThat(lines.stream().filter(l -> l.city().equals("KYIV")).map(l -> l.amount()).reduce(BigDecimal.ZERO,BigDecimal::add))
                .isEqualByComparingTo("0.01");
        assertThat(lines).filteredOn(l -> l.lineId().equals("KYIV_GROSS_OTHER")).singleElement()
                .satisfies(l -> assertThat(l.amount()).isZero());
        assertThat(FolioProfitGrossLines.rows(null,List.of(1),List.of(5))).allSatisfy(l -> {
            assertThat(l.amount()).isNull(); assertThat(l.lineCount()).isNull();
        });
    }

    @Test void deprecatedInputsCannotRestoreOldTaxSplitAndNotApplicableRowsAreExplicit() {
        var report = service.calculate(new FolioProfitReportService.Request("2026-07",bd("0.5"),null,null,null,
                null,null,null,bd("900"),4,3),true);
        assertThat(report.inputs().taxAllocationMethod()).isEqualTo("ALL_TAXES_KYIV");
        assertThat(report.inputs().kyivTaxShare()).isEqualByComparingTo("1");
        assertThat(report.inputs().odesaTaxShare()).isZero();
        assertThat(report.inputs().totalEmployeeCount()).isNull();
        assertThat(report.inputs().kyivAdditionalSalary()).isZero();
        assertThat(report.inputs().odesaAdditionalSalary()).isEqualByComparingTo("5000");
        assertThat(report.expenseLines()).filteredOn(l -> l.source().equals("NOT_APPLICABLE")).hasSize(5).allSatisfy(l -> {
            assertThat(l.amount()).isZero(); assertThat(l.profitImpact()).isZero(); assertThat(l.documentCount()).isZero();
        });
        var ids = report.expenseLines().stream().map(l -> l.lineId()).toList();
        assertThat(ids.indexOf("KYIV_ADDITIONAL_SALARY")).isEqualTo(ids.indexOf("KYIV_SALARY_RUB")+1);
        assertThat(ids.indexOf("KYIV_BANK_SERVICES")).isEqualTo(ids.indexOf("KYIV_ADDITIONAL_SALARY")+1);
        assertThat(ids.indexOf("KYIV_RENT_WHOLESALE")).isGreaterThan(ids.indexOf("KYIV_PHONE"));
        assertThat(ids.indexOf("KYIV_IMPORT_TRANSPORT")).isGreaterThan(ids.indexOf("KYIV_PHONE_KAL"));
    }
}
