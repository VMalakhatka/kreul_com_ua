package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.GrossMarginRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryMovementRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryOpeningRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.MasterClassMovementRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolioProfitReportServiceTest {

    @Mock
    private FolioProfitReportDao dao;

    private FolioProfitReportService service;

    @BeforeEach
    void setUp() {
        FolioProfitReportProperties properties = new FolioProfitReportProperties();
        service = new FolioProfitReportService(dao, new FolioProfitClassifier(), properties);
        lenient().when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        lenient().when(dao.findMasterClassMovements(anyInt(), anyString(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void buildsJulyGoldenMasterWithoutRepeatingImportTransport() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of(
                payment(1, "ВЫХОДЦЕВ", null, "Бухгалтер", 1, "11000", "2026 07 бухгалтерские услуги"),
                payment(2, null, "Z/P RUB", "Зарплата Донецк", 7, "95800", "2026 07"),
                payment(3, null, "ТРАНС.ИМ", "Транспорт импорт", 1, "12130", "2026 07"),
                payment(4, "НАЛОГИ", null, "MALAFOP", 5, "44843.51", "2026 07"),
                payment(5, "НАЛОГИ", null, "KONDFOP", 7, "2594.10", "2026 07")
        ));
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of(
                gross(1, "", false, true, "264551.50"),
                gross(5, "", false, true, "98577.61"),
                gross(1, "Я", false, true, "99"),
                gross(5, "Я", false, true, "10740"),
                gross(5, "", true, true, "88")
        ));
        when(dao.findMasterClassMovements(eq(5), eq("Мастер-Класс июль"), any(), any()))
                .thenReturn(julyMasterClassRows());
        when(dao.findWarehouseNames(any())).thenReturn(Map.of(
                1, "Киев 1", 7, "Киев 7", 12, "Киев 12", 5, "Одесса"));
        when(dao.findInventoryOpenings(any())).thenReturn(List.of(
                new InventoryOpeningRow(1, "A", new BigDecimal("10"), new BigDecimal("5")),
                new InventoryOpeningRow(5, "B", new BigDecimal("20"), new BigDecimal("4"))
        ));
        when(dao.findInventoryMovements(any(), any(), any())).thenReturn(List.of(
                new InventoryMovementRow(1, "A", new BigDecimal("2"), new BigDecimal("12"),
                        new BigDecimal("3"), new BigDecimal("18")),
                new InventoryMovementRow(5, "B", BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("-5"), new BigDecimal("-20"))
        ));

        FolioProfitReportResponse report = service.calculate(new FolioProfitReportService.Request(
                "2026-07", null, null, null, null, null,
                null, null), true);

        assertThat(city(report, "KYIV").baseGrossProfit()).isEqualByComparingTo("264551.50");
        assertThat(city(report, "KYIV").operatingExpenses()).isEqualByComparingTo("78496.96");
        assertThat(city(report, "KYIV").profit()).isEqualByComparingTo("186054.54");
        assertThat(city(report, "ODESA").baseGrossProfit()).isEqualByComparingTo("98577.61");
        assertThat(city(report, "ODESA").manualGrossAdjustments()).isEqualByComparingTo("4690.00");
        assertThat(city(report, "ODESA").operatingExpenses()).isEqualByComparingTo("24218.65");
        assertThat(city(report, "ODESA").profit()).isEqualByComparingTo("79048.96");
        assertThat(report.masterClass().income()).isEqualByComparingTo("10740.00");
        assertThat(report.masterClass().returns()).isEqualByComparingTo("6050.00");
        assertThat(report.masterClass().netContribution()).isEqualByComparingTo("4690.00");
        assertThat(report.masterClass().grossProfitAlreadyInBase()).isEqualByComparingTo("0.00");
        assertThat(report.masterClassDocuments()).hasSize(26);
        assertThat(report.inputs().odesaAdditionalSalary()).isEqualByComparingTo("5000.00");
        assertThat(report.inputs().odesaAdditionalSalarySource()).isEqualTo("DEFAULT");
        assertThat(report.controls().capitalizedCostTotal()).isEqualByComparingTo("12130.00");
        assertThat(report.expenses()).anySatisfy(row -> {
            assertThat(row.category()).isEqualTo("IMPORT_TRANSPORT");
            assertThat(row.amount()).isEqualByComparingTo("12130.00");
            assertThat(row.profitImpact()).isEqualByComparingTo("0.00");
        });
        assertThat(report.documents()).hasSize(5);
        assertThat(report.documents().toString()).doesNotContain("бухгалтерские услуги");
        assertThat(inventory(report, "KYIV").openingAccountingValue()).isEqualByComparingTo("62.00");
        assertThat(inventory(report, "KYIV").closingAccountingValue()).isEqualByComparingTo("68.00");
        assertThat(inventory(report, "KYIV").accountingValueChange()).isEqualByComparingTo("6.00");
        assertThat(inventory(report, "ODESA").openingAccountingValue()).isEqualByComparingTo("80.00");
        assertThat(inventory(report, "ODESA").closingAccountingValue()).isEqualByComparingTo("60.00");
        assertThat(inventory(report, "ODESA").accountingValueChange()).isEqualByComparingTo("-20.00");
        assertThat(report.complete()).isTrue();
    }

    @Test
    void ignoresLegacyMasterClassInputsAndHonorsExplicitZeroSalary() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of());
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of());
        stubEmptyInventory();

        FolioProfitReportResponse report = service.calculate(new FolioProfitReportService.Request(
                "2026-07", null, null, new BigDecimal("999"), new BigDecimal("888"), BigDecimal.ZERO,
                null, null), false);

        assertThat(report.masterClass().income()).isEqualByComparingTo("0.00");
        assertThat(report.masterClass().returns()).isEqualByComparingTo("0.00");
        assertThat(report.inputs().odesaMasterClassIncome()).isNull();
        assertThat(report.inputs().odesaMasterClassReturn()).isNull();
        assertThat(report.inputs().odesaAdditionalSalary()).isEqualByComparingTo("0.00");
        assertThat(report.inputs().odesaAdditionalSalarySource()).isEqualTo("REQUEST_OVERRIDE");
        assertThat(report.warnings()).extracting(FolioProfitReportResponse.Warning::code)
                .contains("MASTER_CLASS_LEGACY_PARAMETERS_IGNORED");
    }

    @Test
    void missingMonthlyMasterClassArticleIsNotReportedAsConfirmedZero() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of());
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of());
        when(dao.masterClassArticleExists("Мастер-Класс сентябр")).thenReturn(false);
        stubEmptyInventory();

        FolioProfitReportResponse report = service.calculate(new FolioProfitReportService.Request(
                "2025-09", null, null, null, null, null, null, null), false);

        assertThat(report.complete()).isFalse();
        assertThat(report.masterClass().articleFound()).isFalse();
        assertThat(report.masterClass().sku()).isEqualTo("Мастер-Класс сентябр");
        assertThat(report.warnings()).extracting(FolioProfitReportResponse.Warning::code)
                .contains("MASTER_CLASS_ARTICLE_NOT_FOUND");
        verify(dao).masterClassArticleExists("Мастер-Класс сентябр");
    }

    @Test
    void replacesAnyMasterClassGrossAlreadyPresentInBaseWithoutDoubleCounting() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of());
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of(
                gross(5, "К", false, true, "80")));
        when(dao.findMasterClassMovements(eq(5), eq("Мастер-Класс июль"), any(), any()))
                .thenReturn(List.of(
                        masterClassRow(1, "Р", "", false, true, "100", "20", "К"),
                        masterClassRow(2, "П", "ВОЗВРАТ", true, true, "10", "0", "К"),
                        masterClassRow(3, "П", "ПОЛУЧЕНИЕ", false, true, "50", "0", "Т"),
                        masterClassRow(4, "Р", "", false, false, "30", "0", "К")));
        stubEmptyInventory();

        FolioProfitReportResponse report = service.calculate(new FolioProfitReportService.Request(
                "2026-07", null, null, null, null, BigDecimal.ZERO, null, null), true);

        assertThat(report.masterClass().income()).isEqualByComparingTo("100.00");
        assertThat(report.masterClass().returns()).isEqualByComparingTo("10.00");
        assertThat(report.masterClass().grossProfitAlreadyInBase()).isEqualByComparingTo("80.00");
        assertThat(report.masterClass().grossAdjustmentApplied()).isEqualByComparingTo("10.00");
        assertThat(report.masterClass().ignoredLineCount()).isEqualTo(2);
        assertThat(city(report, "ODESA").grossProfit()).isEqualByComparingTo("90.00");
        assertThat(report.masterClassDocuments()).extracting(
                        FolioProfitReportResponse.MasterClassDocumentLine::classification)
                .containsExactly("INCOME", "RETURN", "IGNORED", "IGNORED");
    }

    @Test
    void selectsExactSeptemberArticleAndCalendarYearBoundaries() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of());
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of());
        stubEmptyInventory();

        service.calculate(new FolioProfitReportService.Request(
                "2025-09", null, null, null, null, BigDecimal.ZERO, null, null), false);

        verify(dao).findMasterClassMovements(
                5, "Мастер-Класс сентябр", LocalDate.of(2025, 9, 1), LocalDate.of(2025, 10, 1));
    }

    @Test
    void explicitPeriodOverridesDocumentDate() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of(
                new PaymentRow(1, "1", LocalDate.of(2026, 7, 5), new BigDecimal("100"), false, 1,
                        "АРЕНДАКИ", null, "Июльский документ", null, "отнести на 2026 08"),
                new PaymentRow(2, "2", LocalDate.of(2026, 8, 5), new BigDecimal("200"), false, 1,
                        "АРЕНДАКИ", null, "Августовский документ", null, "отнести на 2026 07")
        ));
        when(dao.findGrossMargins(any(), any())).thenReturn(List.of());
        stubEmptyInventory();

        FolioProfitReportResponse report = service.calculate(new FolioProfitReportService.Request(
                "2026-07", null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null), true);

        assertThat(report.controls().selectedDocumentCount()).isEqualTo(1);
        assertThat(report.documents()).extracting(FolioProfitReportResponse.DocumentLine::paymentId)
                .containsExactly(2L);
        assertThat(city(report, "KYIV").operatingExpenses()).isEqualByComparingTo("200.00");
    }

    @Test
    void rejectsWarehouseAssignedToBothCities() {
        assertThatThrownBy(() -> service.calculate(new FolioProfitReportService.Request(
                "2026-07", null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(1, 5), List.of(5)), false))
                .isInstanceOf(FolioAccountValidationException.class)
                .extracting(error -> ((FolioAccountValidationException) error).getCode())
                .isEqualTo("STOCK_WAREHOUSES_OVERLAP");
    }

    @Test void detailedLinesReconcileAndPreservePaymentIdentityAndZeroOverrides() throws Exception {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of(
                new PaymentRow(1, "245", LocalDate.of(2026,6,23), new BigDecimal("500"), true, 1,
                        "ЧР-КИЕВ", "ИНТЕРНОД", "Интернет", "ИНОЙ ТИП", "2026\t07"),
                new PaymentRow(2, "245", LocalDate.of(2026,6,17), new BigDecimal("799"), false, 7,
                        null, "ИНТЕРНКИ", "Интернет", null, "2026\u00a007"),
                payment(3, null, "З/ПЛАТА", "Зарплата", 1, "129738.19", "2026 07"),
                payment(4, null, "З/П", "Донецк", 7, "95800", "2026 07"),
                payment(5, null, "З/П ОДЕС", "Зарплата", 5, "66770.67", "2026 07"),
                payment(6, null, "ТЕЛЕФКАЛ", "Телефон", 1, "810", "2026 07"),
                payment(7, null, "ТРАНС.ИМ", "Импорт", 1, "12130", "2026 07"),
                payment(8, "МАЛАФОП", "НАЛОГИ", "Налог", 1, "10.01", "2026 07"),
                payment(9, "КОНДФОП", "НАЛОГИ", "Налог", 5, "20.02", "2026 07"),
                new PaymentRow(10, "10", LocalDate.of(2026,8,18), new BigDecimal("3353"), false, 5,
                        null, "КОММУНОД", "Коммунальные", null, "2026  07", "ком"),
                payment(11, null, "АРЕНДОД", "Синтетический пример", 5, "1.23", "2026 07-08"),
                payment(12, null, "АРЕНДОД", "Синтетический пример", 5, "7.89", "2026 08")));
        stubEmptyInventory();
        var report = service.calculate(new FolioProfitReportService.Request("2026-07", null, null,
                null, null, BigDecimal.ZERO, null, null, new BigDecimal("100")), true);
        verify(dao).findPaymentCandidates(LocalDate.of(2026,6,1), LocalDate.of(2026,9,1), "2026 07");
        assertThat(report.expenseLines()).filteredOn(r -> r.lineId().equals("KYIV_SALARY_RUB"))
                .singleElement().satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("39278"));
        assertThat(report.expenseLines()).filteredOn(r -> r.lineId().equals("ODESA_ADDITIONAL_SALARY"))
                .singleElement().satisfies(r -> {
                    assertThat(r.amount()).isZero(); assertThat(r.documentCount()).isZero();
                    assertThat(r.source()).isEqualTo("REQUEST_OVERRIDE");
                });
        assertThat(report.inputs().kyivAdditionalSalary()).isEqualByComparingTo("100");
        assertThat(report.documents()).filteredOn(r -> r.documentNumber().equals("245")).hasSize(2);
        assertThat(report.documents()).filteredOn(r -> r.paymentId() == 8).singleElement().satisfies(r -> {
            assertThat(r.expenseLineId()).isEqualTo("SHARED_TAX_MALAFOP");
            assertThat(r.kyivAllocation().add(r.odesaAllocation())).isEqualByComparingTo("10.01");
            assertThat(r.profitImpact()).isEqualByComparingTo("10.01");
        });
        assertLineReconciliation(report);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var json = mapper.readTree(mapper.writeValueAsString(report));
        assertThat(json.path("expenseLines").get(0).path("amount").isTextual()).isTrue();
        assertThat(json.path("documents").get(0).path("profitImpact").isTextual()).isTrue();
        assertThat(json.path("cities").get(0).path("operatingExpenses").isTextual()).isTrue();
        assertThat(report.documents()).filteredOn(r -> r.paymentId() == 10).singleElement()
                .satisfies(r -> assertThat(r.sourceInfo()).isEqualTo("ком"));
        String fixtureOutput = System.getProperty("folio.profit.fixture.output");
        if (fixtureOutput != null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixtureOutput),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Test void provisionalPeriodsAreVisibleWithoutSilentRedistributionOrDuplicateDocuments() {
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of(
                payment(1, null, "АРЕНДОД", "Rent", 5, "405", "2026 07-08; secret must not escape"),
                payment(2, null, "АРЕНДОД", "Rent", 5, "100", "2026 13"),
                payment(3, null, "АРЕНДОД", "Rent", 5, "900", "2026 07 2026 08"),
                payment(4, null, "АРЕНДОД", "Rent", 5, "200", "2026 08")));
        stubEmptyInventory();
        var report = service.calculate(new FolioProfitReportService.Request("2026-07", null, null, null, null,
                BigDecimal.ZERO, null, null), true);
        assertThat(report.complete()).isFalse();
        assertThat(report.documents()).isEmpty();
        assertThat(report.periodDiagnostics()).hasSize(4);
        assertThat(report.periodDiagnostics().get(0).includedInTotals()).isTrue();
        assertThat(report.periodDiagnostics().get(0).document().profitImpact()).isEqualByComparingTo("405");
        assertThat(report.periodDiagnostics().get(2).document().profitImpact()).isZero();
        assertThat(report.periodDiagnostics().toString()).doesNotContain("secret must not escape");
        assertThat(city(report, "ODESA").operatingExpenses()).isEqualByComparingTo("505");
        assertLineReconciliation(report);
    }

    @Test void diagnosticsAndAuditHaveIndependentTruncationButTotalsAreNotTruncated() {
        var props = new FolioProfitReportProperties(); props.setMaxAuditDocuments(1);
        service = new FolioProfitReportService(dao, new FolioProfitClassifier(), props);
        when(dao.findPaymentCandidates(any(), any(), anyString())).thenReturn(List.of(
                payment(1, null, "АРЕНДОД", "Rent", 5, "10", ""),
                payment(2, null, "АРЕНДОД", "Rent", 5, "20", ""),
                payment(3, null, "АРЕНДОД", "Rent", 5, "30", "2026 13"),
                payment(4, null, "АРЕНДОД", "Rent", 5, "40", "2026 08")));
        stubEmptyInventory();
        var report = service.calculate(new FolioProfitReportService.Request("2026-07", null, null, null, null,
                BigDecimal.ZERO, null, null), true);
        assertThat(report.documents()).hasSize(1);
        assertThat(report.periodDiagnostics()).hasSize(1);
        assertThat(report.controls().auditTruncated()).isTrue();
        assertThat(report.periodDiagnosticsTruncated()).isTrue();
        assertThat(city(report,"ODESA").operatingExpenses()).isEqualByComparingTo("60");
    }

    @Test void rejectsNegativeKyivManualAndHandlesYearBoundary() {
        assertThatThrownBy(() -> service.calculate(new FolioProfitReportService.Request("2026-01", null,
                null, null, null, null, null, null, new BigDecimal("-1")), false))
                .isInstanceOf(FolioAccountValidationException.class);
        stubEmptyInventory();
        var report = service.calculate(new FolioProfitReportService.Request("2026-01", null, null,
                null, null, null, null, null), false);
        verify(dao).findPaymentCandidates(LocalDate.of(2025,12,1), LocalDate.of(2026,3,1), "2026 01");
        assertThat(report.inputs().kyivAdditionalSalarySource()).isEqualTo("DEFAULT");
        assertThat(report.inputs().kyivAdditionalSalary()).isZero();
        assertThat(report.expenseLines()).hasSize(32);
        assertLineReconciliation(report);
    }

    private static void assertLineReconciliation(FolioProfitReportResponse report) {
        for (var city : report.cities()) {
            BigDecimal impact = report.expenseLines().stream().filter(r -> r.city().equals(city.city()))
                    .map(FolioProfitReportResponse.ExpenseLine::profitImpact).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(impact).isEqualByComparingTo(city.operatingExpenses());
        }
    }

    @Test void julyArchivedTaxesRoundEveryDocumentNotThePool() {
        // Archive review-2026-09-08, Taxes.xlsx, Лист1 B4:B16/F4:F16.
        // Only amounts/pool labels retained, no bank requisites or raw notes.
        String[] amounts = {"1606.50","3335.66","864.70","6347.13","13168.38","1729.40",
                "1902.34","1902.34","864.70","5783.39","12008.35","864.70","864.70"};
        java.util.ArrayList<PaymentRow> rows = new java.util.ArrayList<>();
        for (int i=0; i<amounts.length; i++) {
            String pool = java.util.Set.of(6,8,11,12).contains(i) ? "КОНДФОП" : "МАЛАФОП";
            rows.add(payment(i+1, pool, "НАЛОГИ", "Налог", 5, amounts[i], "2026 07 за липень 2026"));
        }
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(rows);
        stubEmptyInventory();
        var report = service.calculate(new FolioProfitReportService.Request("2026-07", null, null,
                null,null,BigDecimal.ZERO,null,null),true);
        assertThat(report.complete()).isTrue();
        assertThat(report.documents()).hasSize(13);
        assertThat(city(report,"ODESA").operatingExpenses()).isEqualByComparingTo("20033.95");
        assertThat(city(report,"KYIV").operatingExpenses()).isEqualByComparingTo("31208.34");
        assertThat(report.controls().operatingExpenseTotal()).isEqualByComparingTo("51242.29");
        assertLineReconciliation(report);
    }

    @Test void archivedOdesaSalaryKeepsTwelveDocumentsIncludingMissingSourceInfo() {
        // Archive Одесса.xlsx, Лист1 B27:B38: the 3128 payment has no IST_INF.
        String[] amounts = {"130","5000","5000","2000","3000","10000","5000","8500",
                "3128","2142","11082.97","11787.70"};
        java.util.ArrayList<PaymentRow> rows = new java.util.ArrayList<>();
        for (int i=0; i<amounts.length; i++) rows.add(new PaymentRow(i+1, Integer.toString(i+1),
                LocalDate.of(2026,7,10), new BigDecimal(amounts[i]), false, 5, null, "З/П ОДЕС",
                "Зарплата", "РАСХОДЫ ОДЕССЫ", "2026 07", i == 8 ? null : "зп"));
        when(dao.findPaymentCandidates(any(),any(),anyString())).thenReturn(rows);
        stubEmptyInventory();
        var report = service.calculate(new FolioProfitReportService.Request("2026-07",null,null,
                null,null,null,null,null), true);
        assertThat(report.documents()).hasSize(12);
        assertThat(report.expenseLines()).filteredOn(r -> r.lineId().equals("ODESA_SALARY_DOCUMENTS"))
                .singleElement().satisfies(r -> {
                    assertThat(r.documentCount()).isEqualTo(12);
                    assertThat(r.amount()).isEqualByComparingTo("66770.67");
                    assertThat(r.filters().operationRequired()).isFalse();
                });
        assertThat(city(report,"ODESA").operatingExpenses()).isEqualByComparingTo("71770.67");
        assertThat(report.inputs().odesaAdditionalSalarySource()).isEqualTo("DEFAULT");
        assertThat(report.documents()).filteredOn(r -> r.paymentId() == 9).singleElement()
                .satisfies(r -> assertThat(r.sourceInfo()).isNull());
        assertLineReconciliation(report);
    }

    private static FolioProfitReportResponse.CityResult city(FolioProfitReportResponse report, String city) {
        return report.cities().stream().filter(row -> city.equals(row.city())).findFirst().orElseThrow();
    }

    private static FolioProfitReportResponse.InventoryResult inventory(
            FolioProfitReportResponse report,
            String city) {
        return report.inventory().stream().filter(row -> city.equals(row.city())).findFirst().orElseThrow();
    }

    private void stubEmptyInventory() {
        when(dao.findWarehouseNames(any())).thenReturn(Map.of(
                1, "Киев 1", 7, "Киев 7", 12, "Киев 12", 5, "Одесса"));
        when(dao.findInventoryOpenings(any())).thenReturn(List.of());
        when(dao.findInventoryMovements(any(), any(), any())).thenReturn(List.of());
    }

    private static PaymentRow payment(
            long id,
            String purpose,
            String expense,
            String name,
            Integer warehouse,
            String amount,
            String note) {
        return new PaymentRow(id, Long.toString(id), LocalDate.of(2026, 7, 10), new BigDecimal(amount),
                false, warehouse, purpose, expense, name, null, note);
    }

    private static GrossMarginRow gross(
            int warehouse,
            String organizationType,
            boolean returnDocument,
            boolean accounted,
            String amount) {
        return new GrossMarginRow(warehouse, organizationType, returnDocument, accounted, 1,
                new BigDecimal(amount));
    }

    private static List<MasterClassMovementRow> julyMasterClassRows() {
        List<MasterClassMovementRow> rows = new java.util.ArrayList<>();
        String[] income = {"1730", "884", "442", "442", "486", "874", "437", "437", "588",
                "472", "472", "472", "656", "423", "656", "423", "846"};
        String[] returns = {"900", "1000", "350", "1000", "350", "750", "350", "1000", "350"};
        long id = 1;
        for (String amount : income) {
            rows.add(masterClassRow(id++, "Р", "", false, amount));
        }
        for (String amount : returns) {
            rows.add(masterClassRow(id++, "П", "ВОЗВРАТ", true, amount));
        }
        return List.copyOf(rows);
    }

    private static MasterClassMovementRow masterClassRow(
            long id,
            String type,
            String operation,
            boolean returned,
            String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new MasterClassMovementRow(
                id, Long.toString(100000 + id), Long.toString(200000 + id), null, 1,
                LocalDate.of(2026, 7, 1), 5, "Мастер-Класс июль", type, type, operation,
                returned, returned, true, true, value, BigDecimal.ONE, value,
                BigDecimal.ZERO, "Я");
    }

    private static MasterClassMovementRow masterClassRow(
            long id,
            String type,
            String operation,
            boolean returned,
            boolean accounted,
            String amount,
            String accountingCost,
            String organizationType) {
        BigDecimal value = new BigDecimal(amount);
        return new MasterClassMovementRow(
                id, Long.toString(100000 + id), Long.toString(200000 + id), null, 1,
                LocalDate.of(2026, 7, 1), 5, "Мастер-Класс июль", type, type, operation,
                returned, returned, accounted, accounted, value, BigDecimal.ONE, value,
                new BigDecimal(accountingCost), organizationType);
    }
}
