package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.Category;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.City;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.Treatment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FolioProfitClassifierTest {

    private final FolioProfitClassifier classifier = new FolioProfitClassifier();

    @Test
    void convertsDonetskSalaryFromRubles() {
        var result = classifier.classify(row("Z/P RUB", null, "Зарплата Донецк", 7,
                new BigDecimal("95800"), null), new BigDecimal("0.41"));

        assertThat(result.city()).isEqualTo(City.KYIV);
        assertThat(result.category()).isEqualTo(Category.SALARY);
        assertThat(result.treatment()).isEqualTo(Treatment.OPERATING_EXPENSE);
        assertThat(result.sourceCurrency()).isEqualTo("RUB");
        assertThat(result.reportAmount()).isEqualByComparingTo("39278.00");
    }

    @Test
    void importTransportIsVisibleButDoesNotRepeatInventoryCost() {
        var result = classifier.classify(row("ТРАНС.ИМ", null, "Импорт", 1,
                new BigDecimal("12130"), null), new BigDecimal("0.41"));

        assertThat(result.city()).isEqualTo(City.KYIV);
        assertThat(result.category()).isEqualTo(Category.IMPORT_TRANSPORT);
        assertThat(result.treatment()).isEqualTo(Treatment.CAPITALIZED_IN_INVENTORY);
    }

    @Test
    void emptyWarehouseDoesNotDisableRule() {
        var result = classifier.classify(row(null, "АРЕНДОД", "Аренда", null,
                new BigDecimal("100"), null), new BigDecimal("0.41"));

        assertThat(result.city()).isEqualTo(City.ODESA);
        assertThat(result.category()).isEqualTo(Category.RENT);
    }

    @Test
    void supplierPaymentIsExcludedFromOperatingExpenses() {
        var result = classifier.classify(row(null, null, "Поставщик", 1,
                new BigDecimal("100"), "Оплата поставщику"), new BigDecimal("0.41"));

        assertThat(result.category()).isEqualTo(Category.SUPPLIER_PAYMENT);
        assertThat(result.treatment()).isEqualTo(Treatment.EXCLUDED);
    }

    private static PaymentRow row(
            String purposeCode,
            String expenseCode,
            String name,
            Integer warehouse,
            BigDecimal amount,
            String documentClass) {
        return new PaymentRow(1, "1", LocalDate.of(2026, 7, 1), amount, false, warehouse,
                purposeCode, expenseCode, name, documentClass, null);
    }
}
