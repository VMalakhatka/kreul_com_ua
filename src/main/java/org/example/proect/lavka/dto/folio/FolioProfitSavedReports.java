package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FolioProfitSavedReports {
    private FolioProfitSavedReports() {}
    public record CalculateRequest(String requestId,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal odesaTaxShare,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal rubToUahRate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal odesaMasterClassIncome,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal odesaMasterClassReturn,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal odesaAdditionalSalary,
            List<Integer> kyivStockWarehouseIds,List<Integer> odesaStockWarehouseIds,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal kyivAdditionalSalary) {}
    public record Revision(boolean ok,String status,String sourceDatabase,String month,Long revisionId,String requestId,
            Long publishedRevisionId,Long latestRevisionId,String latestStatus,boolean published,boolean auditComplete,
            @JsonFormat(shape=JsonFormat.Shape.STRING) Instant createdAt,@JsonFormat(shape=JsonFormat.Shape.STRING) Instant completedAt,
            CalculateRequest request,FolioProfitReportResponse report,String errorCode) {}
    public record History(boolean ok,String month,List<Revision> revisions,boolean hasMore,Long nextBeforeRevisionId) {}
    public record Month(String month,Long revisionId,String status,Long latestRevisionId,String latestStatus,
            String calculatedAt,String ruleVersion,boolean auditComplete,
            FolioProfitReportResponse.Inputs inputs,List<FolioProfitReportResponse.CityResult> cities,
            List<FolioProfitReportResponse.InventoryResult> inventory) {}
    public record Range(boolean ok,String fromMonth,String toMonth,String sourceDatabase,boolean complete,
            List<String> missingMonths,List<Month> months,List<Totals> totals,List<String> warnings) {}
    public record Totals(String city,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal baseGrossProfit,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal manualGrossAdjustments,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal grossProfit,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal operatingExpenses,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal profit,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal openingAccountingValue,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal closingAccountingValue,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal accountingValueChange,boolean complete) {}
}
