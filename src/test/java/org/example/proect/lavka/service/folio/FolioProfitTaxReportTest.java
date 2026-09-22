package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitTaxReportTest {
    private final FolioProfitReportDao dao=mock(FolioProfitReportDao.class);
    private final FolioProfitTaxSettingsService settings=mock(FolioProfitTaxSettingsService.class);
    private final FolioProfitReportProperties properties=new FolioProfitReportProperties();
    private final FolioProfitReportService service=new FolioProfitReportService(dao,new FolioProfitClassifier(),properties,settings);
    private final FolioProfitReportService.Request request=new FolioProfitReportService.Request("2026-07",null,null,null,null,
            BigDecimal.ZERO,null,null,BigDecimal.ZERO,4,3);

    private void setup(List<PaymentRow> payments) {
        when(settings.get()).thenReturn(FolioProfitTaxSettings.defaults());
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        when(dao.findWarehouseNames(any())).thenReturn(java.util.Map.of(1,"Synthetic Kyiv",7,"Synthetic Wholesale",12,"Synthetic Storage",5,"Synthetic Odesa"));
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(payments);
    }
    @Test void allHistoricalAndCurrentFirmsSumAllDocumentsOnceWithExactCityAudit() throws Exception {
        var duplicate=payment(1,"МИХНФОП","7");
        setup(List.of(duplicate,duplicate,payment(2,"МАЛАФОП","14"),payment(3,"КУЗНФОП","21"),
                payment(4,"КОНДФОП","28"),payment(5,"НЕИЗВФОП","5"),payment(6,"МИХНФОП КУЗНФОП","11")));
        var report=service.calculate(request,true);
        assertThat(report.ruleVersion()).isEqualTo("2026-09-22.1");
        assertThat(report.complete()).isFalse();
        assertThat(report.controls().selectedDocumentCount()).isEqualTo(6);
        assertThat(report.taxDetails().retailAmount()).isEqualByComparingTo("21");
        assertThat(report.taxDetails().wholesaleAmount()).isEqualByComparingTo("49");
        assertThat(report.taxDetails().unallocatedAmount()).isEqualByComparingTo("16");
        assertThat(report.taxDetails().unallocatedDocuments()).extracting(FolioProfitReportResponse.DocumentLine::paymentId).containsExactly(5L,6L);
        assertThat(report.taxDetails().unallocatedDocuments()).allSatisfy(d->{
            assertThat(d.accountingTreatment()).isEqualTo("UNALLOCATED");
            assertThat(d.profitImpact()).isZero();
        });
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("61");
        assertThat(report.cities().get(1).operatingExpenses()).isEqualByComparingTo("9");
        assertThat(report.controls().operatingExpenseTotal()).isEqualByComparingTo("70");
        assertThat(report.warnings()).filteredOn(w->w.code().equals("UNKNOWN_TAX_POOL")).hasSize(2);
        assertThat(report.expenseLines()).filteredOn(l->l.lineId().equals("KYIV_TAX_MALAFOP"))
                .singleElement().satisfies(l->assertThat(l.filters().purposeCodes()).containsExactly("МИХНФОП","МАЛАФОП"));
        verify(settings,times(1)).get();
        String fixture=System.getProperty("folio.profit.tax.fixture.output");
        if(fixture!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture),
                new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    @Test void capturedSettingsNotCurrentSettingsDriveEveryLineAndLegacyShare() {
        setup(List.of(payment(1,"НОВАФОП","100"),payment(2,"КУЗНФОП","50")));
        var captured=new FolioProfitTaxSettings(3L,List.of("НОВАФОП"),List.of("КУЗНФОП"));
        var legacy=new FolioProfitReportService.Request("2026-07",new BigDecimal("0.41"),null,null,null,BigDecimal.ZERO,null,null,BigDecimal.ZERO);
        var report=service.calculate(legacy,true,captured);
        assertThat(report.taxDetails().settings()).isEqualTo(captured);
        assertThat(report.taxDetails().unallocatedAmount()).isZero();
        assertThat(report.complete()).isTrue();
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("109");
        assertThat(report.cities().get(1).operatingExpenses()).isEqualByComparingTo("41");
        assertThat(report.expenseLines()).filteredOn(l->l.lineId().equals("ODESA_TAX_MALAFOP")).singleElement()
                .satisfies(l->assertThat(l.filters().purposeCodes()).containsExactly("НОВАФОП"));
        verifyNoInteractions(settings);
    }
    @Test void emptyPoolsDisableAllocationNotTaxDocumentSelectionAndUnknownAuditIsNotCapped() {
        setup(List.of(payment(1,"МАЛАФОП","5"),payment(2,"КУЗНФОП","7"),payment(3,"МИХНФОП","9")));
        properties.setMaxAuditDocuments(1);
        var report=service.calculate(request,false,new FolioProfitTaxSettings(1L,List.of(),List.of()));
        assertThat(report.documents()).isEmpty();
        assertThat(report.taxDetails().unallocatedDocuments()).hasSize(3);
        assertThat(report.taxDetails().unallocatedAmount()).isEqualByComparingTo("21");
        assertThat(report.taxDetails().retailAmount()).isZero();
        assertThat(report.taxDetails().wholesaleAmount()).isZero();
        assertThat(report.complete()).isFalse();
    }
    @Test void firmCodeDoesNotTurnRentIntoTaxAndSubstringDoesNotMatchFirm() {
        setup(List.of(new PaymentRow(1,"TEST",LocalDate.of(2026,7,1),new BigDecimal("10"),false,1,
                "АРЕНДАКИ",null,"МАЛАФОП","АРЕНДА","2026 07"),payment(2,"НЕ-МАЛАФОП","5")));
        var report=service.calculate(request,true);
        assertThat(report.taxDetails().retailAmount()).isZero();
        assertThat(report.taxDetails().unallocatedAmount()).isEqualByComparingTo("5");
        assertThat(report.cities().get(0).operatingExpenses()).isEqualByComparingTo("10");
    }
    @Test void periodRulesDoNotChangeAndThereAreNoFirmEffectiveDates() {
        setup(List.of(new PaymentRow(1,"TEST",LocalDate.of(2020,1,1),new BigDecimal("7"),false,1,
                "НАЛОГИ",null,"МИХНФОП","НАЛОГИ","2026 07"),
                new PaymentRow(2,"TEST",LocalDate.of(2026,7,1),new BigDecimal("99"),false,1,
                        "НАЛОГИ",null,"КУЗНФОП","НАЛОГИ","2026 06")));
        var report=service.calculate(request,true);
        assertThat(report.taxDetails().retailAmount()).isEqualByComparingTo("7");
        assertThat(report.taxDetails().wholesaleAmount()).isZero();
        assertThat(report.documents()).extracting(FolioProfitReportResponse.DocumentLine::paymentId).containsExactly(1L);
        assertThat(report.periodDiagnostics()).hasSize(1);
    }
    @Test void supportedSourceFieldsAndDistinctPaymentsWithSameDocumentNumberArePreserved() {
        setup(List.of(
                new PaymentRow(1,"SAME-NUMBER",LocalDate.of(2026,7,1),new BigDecimal("7"),false,1,"МИХНФОП","НАЛОГИ","","","2026 07"),
                new PaymentRow(2,"SAME-NUMBER",LocalDate.of(2026,7,1),new BigDecimal("7"),true,5,"НАЛОГИ","михнфоп","","","2026 07"),
                new PaymentRow(3,"SAME-NUMBER",LocalDate.of(2026,7,1),new BigDecimal("7"),false,1,"НАЛОГИ",null,"[михнфоп]","","2026 07"),
                new PaymentRow(4,"SAME-NUMBER",LocalDate.of(2026,7,1),new BigDecimal("7"),false,1,"НАЛОГИ",null,"","НАЛОГИ (МИХНФОП)","2026 07")));
        var report=service.calculate(request,true);
        assertThat(report.taxDetails().retailAmount()).isEqualByComparingTo("28");
        assertThat(report.taxDetails().unallocatedDocuments()).isEmpty();
        assertThat(report.documents()).hasSize(4);
        assertThat(report.complete()).isTrue();
    }
    @Test void expectedVersionIsCheckedWithoutFolioReads() {
        setup(List.of());
        assertThat(service.resolveTaxSettings(0L)).isEqualTo(FolioProfitTaxSettings.defaults());
        assertThatThrownBy(()->service.resolveTaxSettings(1L)).isInstanceOfSatisfying(FolioAccountConflictException.class,
                e->assertThat(e.getCode()).isEqualTo("PROFIT_TAX_SETTINGS_VERSION_CONFLICT"));
        assertThatThrownBy(()->service.resolveTaxSettings(-1L)).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao);
    }
    @Test void missingSettingsAndConflictingDuplicateAmountsNeverBecomeConfirmedZero() {
        setup(List.of(payment(1,"МИХНФОП","7"),payment(1,"МИХНФОП","8")));
        var conflict=service.calculate(request,true);
        assertThat(conflict.sections().get("EXPENSES").errorCode()).isEqualTo("PROFIT_PAYMENT_DUPLICATE_CONFLICT");
        assertThat(conflict.taxDetails().retailAmount()).isNull();
        when(settings.get()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
        var missing=service.calculate(request,true);
        assertThat(missing.taxDetails().settings()).isNull();
        assertThat(missing.taxDetails().retailAmount()).isNull();
        assertThat(missing.complete()).isFalse();
        assertThat(missing.sections().get("GROSS_MARGIN").status()).isEqualTo("AVAILABLE");
        assertThat(missing.sections().get("MASTER_CLASS").status()).isEqualTo("AVAILABLE");
    }
    static PaymentRow payment(long id,String firm,String amount) {
        return new PaymentRow(id,"SYNTHETIC-"+id,LocalDate.of(2026,7,1),new BigDecimal(amount),false,1,
                "НАЛОГИ",null,firm,"НАЛОГИ","2026 07",null);
    }
}
