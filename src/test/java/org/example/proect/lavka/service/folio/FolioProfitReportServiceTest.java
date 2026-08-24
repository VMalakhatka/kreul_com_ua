package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.GrossMarginRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryMovementRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryOpeningRow;
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
import static org.mockito.ArgumentMatchers.anyString;
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
                gross(5, "", true, true, "88")
        ));
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
                "2026-07", null, null, new BigDecimal("1000"), new BigDecimal("200"), BigDecimal.ZERO,
                null, null), true);

        assertThat(city(report, "KYIV").baseGrossProfit()).isEqualByComparingTo("264551.50");
        assertThat(city(report, "KYIV").operatingExpenses()).isEqualByComparingTo("78496.96");
        assertThat(city(report, "KYIV").profit()).isEqualByComparingTo("186054.54");
        assertThat(city(report, "ODESA").baseGrossProfit()).isEqualByComparingTo("98577.61");
        assertThat(city(report, "ODESA").manualGrossAdjustments()).isEqualByComparingTo("800.00");
        assertThat(city(report, "ODESA").operatingExpenses()).isEqualByComparingTo("19218.65");
        assertThat(city(report, "ODESA").profit()).isEqualByComparingTo("80158.96");
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
}
