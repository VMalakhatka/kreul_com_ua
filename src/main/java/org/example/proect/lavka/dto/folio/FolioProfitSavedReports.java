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
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal kyivAdditionalSalary,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=EmployeeCountDeserializer.class) Integer kyivEmployeeCount,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=EmployeeCountDeserializer.class) Integer odesaEmployeeCount) {
        public CalculateRequest(String id, BigDecimal share, BigDecimal rate, BigDecimal mkIncome, BigDecimal mkReturn,
                BigDecimal odesaSalary, List<Integer> kyivStock, List<Integer> odesaStock, BigDecimal kyivSalary) {
            this(id, share, rate, mkIncome, mkReturn, odesaSalary, kyivStock, odesaStock, kyivSalary, null, null);
        }
    }
    /** Do not silently truncate JSON 1.5 into one employee. */
    public static final class EmployeeCountDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser p,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT) return p.getIntValue();
            if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_STRING) {
                String value=p.getText();
                if (value.matches("-?[0-9]+")) {
                    try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { /* handled below */ }
                }
            }
            throw com.fasterxml.jackson.databind.JsonMappingException.from(p, "Employee count must be an integer in 0..2147483647");
        }
    }
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
