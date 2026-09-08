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
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime calculatedAt,
        boolean complete,
        String ruleVersion,
        Inputs inputs,
        List<CityResult> cities,
        List<InventoryResult> inventory,
        List<ExpenseSummary> expenses,
        List<DocumentLine> documents,
        MasterClassSummary masterClass,
        List<MasterClassDocumentLine> masterClassDocuments,
        Controls controls,
        List<Warning> warnings,
        List<ExpenseLine> expenseLines,
        PeriodPolicy periodPolicy,
        List<PeriodDiagnostic> periodDiagnostics,
        boolean periodDiagnosticsTruncated,
        Map<String, SectionStatus> sections
) {
    public record Inputs(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal odesaTaxShare,
            String taxAllocationMethod,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rubToUahRate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal odesaMasterClassIncome,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal odesaMasterClassReturn,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal odesaAdditionalSalary,
            String odesaAdditionalSalarySource,
            List<Integer> kyivWarehouseIds,
            List<Integer> odesaWarehouseIds,
            List<Integer> kyivStockWarehouseIds,
            List<Integer> odesaStockWarehouseIds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal kyivAdditionalSalary,
            String kyivAdditionalSalarySource
    ) {
    }

    public record InventoryResult(
            String city,
            List<Integer> warehouseIds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingAccountingValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingAccountingValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal accountingValueChange,
            int openingPositionCount,
            int closingPositionCount,
            int negativeClosingPositionCount,
            int zeroValueClosingPositionCount,
            List<WarehouseInventoryResult> warehouses
    ) {
    }

    public record WarehouseInventoryResult(
            int warehouseId,
            String warehouseName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingAccountingValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingAccountingValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal accountingValueChange,
            int openingPositionCount,
            int closingPositionCount,
            int negativeClosingPositionCount,
            int zeroValueClosingPositionCount
    ) {
    }

    public record CityResult(
            String city,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseGrossProfit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal manualGrossAdjustments,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossProfit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal operatingExpenses,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal profit
    ) {
    }

    public record ExpenseSummary(
            String city,
            String category,
            String label,
            String accountingTreatment,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal profitImpact,
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
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceAmount,
            String sourceCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reportAmount,
            String reportCurrency,
            String city,
            String category,
            String accountingTreatment,
            boolean includedInProfit,
            String reason,
            String expenseLineId,
            List<String> expenseLineIds,
            String sourceInfo,
            String periodNote,
            String periodStatus,
            List<String> warnings,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal profitImpact,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal kyivAllocation,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal odesaAllocation,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal appliedRate
    ) {
    }

    public record MasterClassSummary(
            int warehouseId,
            String sku,
            boolean articleFound,
            String source,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal income,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returns,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netContribution,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossProfitAlreadyInBase,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossAdjustmentApplied,
            int incomeLineCount,
            int returnLineCount,
            int ignoredLineCount,
            int duplicateLineCount,
            boolean auditTruncated
    ) {
    }

    public record MasterClassDocumentLine(
            long movementId,
            String documentId,
            String documentNumber,
            String documentNumberSuffix,
            int lineNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate documentDate,
            int warehouseId,
            String sku,
            String documentType,
            String movementType,
            String operationKind,
            boolean returnDocument,
            boolean accounted,
            String classification,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            String currency,
            String amountSource,
            boolean includedInMasterClassContribution,
            String reason
    ) {
    }

    public record Controls(
            int selectedDocumentCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal selectedDocumentAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal operatingExpenseTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal capitalizedCostTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal excludedDocumentAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unclassifiedDocumentAmount,
            int unclassifiedDocumentCount,
            boolean auditTruncated,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(contentUsing = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
            Map<String, BigDecimal> taxPools,
            int periodDiagnosticCount,
            int periodProblemCount,
            int provisionalDocumentCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal provisionalDocumentAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal provisionalOperatingExpenseTotal
    ) {
    }

    public record Warning(String code, String message, Map<String, Object> details) {
    }

    public record ExpenseFilters(List<String> expenseCodes, List<String> operationTypes,
            List<String> purposeCodes, List<Integer> cashWarehouseIds, List<Integer> bankWarehouseIds,
            String cashWarehouseMode, String bankWarehouseMode, boolean operationRequired, String note) {}

    public record ExpenseLine(String lineId, int sortOrder, String city, String category, String label,
            int documentCount, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal profitImpact,
            String accountingTreatment, String source, ExpenseFilters filters) {}

    public record PeriodPolicy(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate candidateFrom,
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate candidateToExclusive,
            boolean explicitPeriodPriority, String description) {}

    public record PeriodDiagnostic(DocumentLine document, String status, String reason,
            boolean includedInTotals, String amountTreatment) {}

    public record SectionStatus(String status, String errorCode, String errorId, String message) {}
}
