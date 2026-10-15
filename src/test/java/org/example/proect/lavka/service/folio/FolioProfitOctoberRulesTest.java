package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.example.proect.lavka.service.folio.FolioProfitClassifier.*;

class FolioProfitOctoberRulesTest {
    private final FolioProfitClassifier classifier = new FolioProfitClassifier();
    private static PaymentRow row(long id, String code, String operation, boolean bank, Integer warehouse) {
        return new PaymentRow(id,"same-visible-number",LocalDate.of(2026,7,1),new BigDecimal("10"),
                bank,warehouse,"ANY",code,"Synthetic",operation,"2026 07");
    }

    @Test void householdAllowsBothExactOperationsForCashAndBankAllWarehouses() {
        for (var warehouse : Arrays.asList(1,7,5,12,20,null)) {
            for (boolean bank : List.of(false,true)) {
                for (String operation : List.of("РАСХОДЫ КИЕВА","РАСХОДЫ КИЕВОПТ"," расходы киевопт ")) {
                    var payment = classifier.classify(row(1," НЕРЕГУЛ ",operation,bank,warehouse),new BigDecimal("0.41"));
                    assertThat(payment.city()).isEqualTo(City.KYIV);
                    assertThat(payment.category()).isEqualTo(Category.HOUSEHOLD);
                    assertThat(payment.treatment()).isEqualTo(Treatment.OPERATING_EXPENSE);
                }
            }
        }
        for (String operation : List.of("КИЕВ","РАСХОДЫ КИЕВА extra","РАСХОДЫ СЕТИ","")) {
            assertThat(classifier.classify(row(1,"НЕРЕГУЛ",operation,true,1),BigDecimal.ONE).treatment())
                    .isEqualTo(Treatment.UNCLASSIFIED);
        }
        assertThat(classifier.classify(row(1,"НЕРЕГУЛ","РАСХОДЫ ОДЕССЫ",false,1),BigDecimal.ONE).city())
                .isEqualTo(City.ODESA);
    }

    @Test void irregularAllowsCashAndBankWithUnrestrictedOperationAndWarehouse() {
        for (var warehouse : Arrays.asList(1,7,5,12,20,null)) for (String operation : Arrays.asList(null,"", "РАСХОДЫ СЕТИ","ОПЛАТА ПОСТАВЩИКУ")) {
            var bank = classifier.classify(row(1,"нерегдон",operation,true,warehouse),BigDecimal.ONE);
            assertThat(bank.city()).isEqualTo(City.KYIV);
            assertThat(bank.category()).isEqualTo(Category.IRREGULAR);
            assertThat(bank.treatment()).isEqualTo(Treatment.OPERATING_EXPENSE);
            var cash = classifier.classify(row(2,"НЕРЕГДОН",operation,false,warehouse),BigDecimal.ONE);
            assertThat(cash.city()).isEqualTo(City.KYIV);
            assertThat(cash.category()).isEqualTo(Category.IRREGULAR);
            assertThat(cash.treatment()).isEqualTo(Treatment.OPERATING_EXPENSE);
        }
        for (String code : List.of("НЕРЕГ КИ","НЕРЕГДОН extra"))
            assertThat(classifier.classify(row(1,code,"",true,1),BigDecimal.ONE).treatment()).isEqualTo(Treatment.UNCLASSIFIED);
    }

    @Test void reportCountsEachPaymentOnceAndDescribesExactFilters() {
        var dao = mock(FolioProfitReportDao.class);
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        var first = row(1,"НЕРЕГУЛ","РАСХОДЫ КИЕВА",false,5);
        var second = row(2,"НЕРЕГУЛ","РАСХОДЫ КИЕВОПТ",true,20);
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(List.of(first,second,first,
                row(3,"НЕРЕГДОН","",true,null),row(4,"НЕРЕГДОН","",false,1)));
        var service = new FolioProfitReportService(dao,classifier,new FolioProfitReportProperties());
        var report = service.calculate(new FolioProfitReportService.Request("2026-07",null,null,null,null,BigDecimal.ZERO,null,null),true);
        assertThat(report.ruleVersion()).isEqualTo("2026-10-15.1");
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("40");
        assertThat(report.documents()).hasSize(4); // IDs, not visible numbers, define identity.
        assertThat(report.expenseLines()).noneMatch(l -> l.lineId().equals("KYIV_HOUSEHOLD_BANK"));
        assertThat(report.expenseLines()).filteredOn(l -> l.lineId().equals("KYIV_HOUSEHOLD")).singleElement().satisfies(l -> {
            assertThat(l.documentCount()).isEqualTo(2);
            assertThat(l.amount()).isEqualByComparingTo("20");
            assertThat(l.filters().operationTypes()).containsExactly("РАСХОДЫ КИЕВА","РАСХОДЫ КИЕВОПТ");
            assertThat(l.filters().operationRequired()).isTrue();
            assertThat(l.filters().cashWarehouseMode()).isEqualTo("ALL");
            assertThat(l.filters().bankWarehouseMode()).isEqualTo("ALL");
            assertThat(l.filters().purposeCodes()).isEmpty();
        });
        assertThat(report.expenseLines()).filteredOn(l -> l.lineId().equals("KYIV_IRREGULAR")).singleElement().satisfies(l -> {
            assertThat(l.amount()).isEqualByComparingTo("20");
            assertThat(l.documentCount()).isEqualTo(2);
            assertThat(l.filters().expenseCodes()).containsExactly("НЕРЕГДОН");
            assertThat(l.filters().cashWarehouseMode()).isEqualTo("ALL");
            assertThat(l.filters().bankWarehouseMode()).isEqualTo("ALL");
            assertThat(l.filters().operationTypes()).isEmpty();
            assertThat(l.filters().operationRequired()).isFalse();
            assertThat(l.filters().purposeCodes()).isEmpty();
            assertThat(l.filters().cashWarehouseIds()).isEmpty();
            assertThat(l.filters().bankWarehouseIds()).isEmpty();
        });
        assertThat(report.controls().excludedDocumentAmount()).isZero();
    }

    @Test void irregularHasNoPurposeRestrictionAndKeepsExactCodeMatching() {
        for (boolean bank : List.of(false,true)) {
            for (String purpose : Arrays.asList(null,"","МАЛАФОП","ЧР-ОДЕСС","ANY")) {
                var p = new PaymentRow(1,"test",LocalDate.of(2026,7,1),new BigDecimal("184"),
                        bank,5,purpose," нерегдон ","Synthetic","РАСХОДЫ ОДЕССЫ","2026 07");
                var classified = classifier.classify(p,BigDecimal.ONE);
                assertThat(classified.city()).isEqualTo(City.KYIV);
                assertThat(classified.treatment()).isEqualTo(Treatment.OPERATING_EXPENSE);
                assertThat(classified.category()).isEqualTo(Category.IRREGULAR);
            }
            var purposeOnly = new PaymentRow(2,"test",LocalDate.of(2026,7,1),BigDecimal.TEN,
                    bank,5," НЕРЕГДОН ",null,"Synthetic",null,"");
            assertThat(classifier.classify(purposeOnly,BigDecimal.ONE).city()).isEqualTo(City.KYIV);
            for (String code : List.of("НЕРЕГМИХ","НЕРЕГ КИ","НЕРЕГДОН extra","XНЕРЕГДОН")) {
                assertThat(classifier.classify(row(3,code,"",bank,5),BigDecimal.ONE).treatment())
                        .isEqualTo(Treatment.UNCLASSIFIED);
            }
        }
    }

    @Test void archivedCashExamplesKeepSourceAmountsAndAreNotAssignedToOdesaOrCountedTwice() {
        // Archived examples, not a live database assertion. Same visible number is not identity.
        var julyCash = irregular(95894,"366","2026-07-28","184",false,5,"2026 07");
        var julyBank = irregular(95895,"366","2026-07-28","16",true,20,"2026 07");
        var july = calculate("2026-07",List.of(julyCash,julyBank,julyCash,julyBank));
        assertThat(july.documents()).hasSize(2).allSatisfy(d -> {
            assertThat(d.expenseLineId()).isEqualTo("KYIV_IRREGULAR");
            assertThat(d.profitImpact()).isEqualByComparingTo(d.reportAmount());
        });
        assertThat(july.cities().get(0).operatingExpenses()).isEqualByComparingTo("200");
        assertThat(july.cities().get(1).operatingExpenses()).isZero();
        assertThat(july.expenseLines()).filteredOn(l -> l.lineId().equals("KYIV_IRREGULAR"))
                .singleElement().satisfies(l -> {
                    assertThat(l.documentCount()).isEqualTo(2);
                    assertThat(l.amount()).isEqualByComparingTo("200");
                    assertThat(l.profitImpact()).isEqualByComparingTo("200");
                });
        var octoberCash = irregular(91934,"530","2025-10-18","3000",false,5,"2025 10");
        var october = calculate("2025-10",List.of(octoberCash,octoberCash));
        assertThat(october.documents()).singleElement().satisfies(d -> {
            assertThat(d.paymentId()).isEqualTo(91934);
            assertThat(d.profitImpact()).isEqualByComparingTo("3000");
        });
        assertThat(october.cities().get(0).operatingExpenses()).isEqualByComparingTo("3000");
        assertThat(october.cities().get(1).operatingExpenses()).isZero();
    }

    @Test void irregularKeepsExplicitPeriodPriorityAndDocumentDateFallback() {
        var report = calculate("2026-07",List.of(
                irregular(1,"1","2026-06-30","10",false,5,"2026 07"),
                irregular(2,"2","2026-08-01","20",true,20,"2026 07"),
                irregular(3,"3","2026-07-10","30",false,7,""),
                irregular(4,"4","2026-07-10","900",false,5,"2026 08")));
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("60");
        assertThat(report.documents()).extracting(d -> d.paymentId()).containsExactly(1L,2L,3L);
        assertThat(report.periodDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.document().paymentId()).isEqualTo(4);
            assertThat(d.includedInTotals()).isFalse();
            assertThat(d.document().profitImpact()).isZero();
        });
    }

    @Test void irregularDoesNotSilentlyResolveDamagedOrAmbiguousPeriods() {
        var report = calculate("2026-07",List.of(
                irregular(1,"1","2026-07-10","10",false,5,"2026 13"),
                irregular(2,"2","2026-07-10","900",true,7,"2026 07 2026 08")));
        assertThat(report.complete()).isFalse();
        assertThat(report.documents()).isEmpty();
        assertThat(report.periodDiagnostics()).hasSize(2);
        // Existing provisional treatment for damaged periods remains unchanged.
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("10");
        assertThat(report.periodDiagnostics()).filteredOn(d -> d.document().paymentId()==2)
                .singleElement().satisfies(d -> assertThat(d.includedInTotals()).isFalse());
    }

    private static PaymentRow irregular(long id,String number,String date,String amount,boolean bank,
                                        Integer warehouse,String note) {
        return new PaymentRow(id,number,LocalDate.parse(date),new BigDecimal(amount),bank,warehouse,
                "ANY","НЕРЕГДОН","Synthetic","РАСХОДЫ СЕТИ",note);
    }

    private org.example.proect.lavka.dto.folio.FolioProfitReportResponse calculate(String month,List<PaymentRow> rows) {
        var dao = mock(FolioProfitReportDao.class);
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(rows);
        return new FolioProfitReportService(dao,classifier,new FolioProfitReportProperties()).calculate(
                new FolioProfitReportService.Request(month,null,null,null,null,BigDecimal.ZERO,null,null),true);
    }
}
