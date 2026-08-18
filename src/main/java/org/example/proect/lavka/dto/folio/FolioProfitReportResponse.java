package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record FolioProfitReportResponse(
        boolean ok,
        String month,
        OffsetDateTime calculatedAt,
        boolean complete,
        String ruleVersion,
        Inputs inputs,
        List<CityResult> cities,
        List<ExpenseSummary> expenses,
        List<DocumentLine> documents,
        Controls controls,
        List<Warning> warnings
) {
    public record Inputs(
            BigDecimal odesaTaxShare,
            String taxAllocationMethod,
            BigDecimal rubToUahRate,
            BigDecimal odesaMasterClassIncome,
            BigDecimal odesaMasterClassReturn,
            BigDecimal odesaAdditionalSalary,
            List<Integer> kyivWarehouseIds,
            List<Integer> odesaWarehouseIds
    ) {
    }

    public record CityResult(
            String city,
            BigDecimal baseGrossProfit,
            BigDecimal manualGrossAdjustments,
            BigDecimal grossProfit,
            BigDecimal operatingExpenses,
            BigDecimal profit
    ) {
    }

    public record ExpenseSummary(
            String city,
            String category,
            String label,
            String accountingTreatment,
            BigDecimal amount,
            BigDecimal profitImpact,
            int documentCount
    ) {
    }

    public record DocumentLine(
            long paymentId,
            String documentNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate documentDate,
            String resolvedMonth,
            String periodSource,
            String stream,
            Integer warehouseId,
            String purposeCode,
            String expenseCode,
            String name,
            String documentClass,
            BigDecimal sourceAmount,
            String sourceCurrency,
            BigDecimal reportAmount,
            String reportCurrency,
            String city,
            String category,
            String accountingTreatment,
            boolean includedInProfit,
            String reason
    ) {
    }

    public record Controls(
            int selectedDocumentCount,
            BigDecimal selectedDocumentAmount,
            BigDecimal operatingExpenseTotal,
            BigDecimal capitalizedCostTotal,
            BigDecimal excludedDocumentAmount,
            BigDecimal unclassifiedDocumentAmount,
            int unclassifiedDocumentCount,
            boolean auditTruncated,
            Map<String, BigDecimal> taxPools
    ) {
    }

    public record Warning(String code, String message, Map<String, Object> details) {
    }
}
