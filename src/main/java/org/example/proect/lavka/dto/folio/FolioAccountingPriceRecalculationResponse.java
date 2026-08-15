package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolioAccountingPriceRecalculationResponse(
        boolean ok,
        boolean previewOnly,
        String status,
        String sku,
        int requestedWarehouseId,
        List<Integer> affectedWarehouseIds,
        AccountingMethod accountingMethod,
        boolean eligibleToApply,
        boolean procedureExecuted,
        Boolean priceChanged,
        List<PriceState> before,
        List<PriceState> after,
        List<Issue> warnings,
        List<Issue> errors
) {
    public record AccountingMethod(
            Integer rawCode,
            int calculationMode,
            int periodMode,
            boolean includeTax,
            String name
    ) {
    }

    public record PriceState(
            int warehouseId,
            String warehouseName,
            BigDecimal initialQuantity,
            BigDecimal physicalQuantity,
            BigDecimal availableQuantity,
            BigDecimal accountingQuantity,
            BigDecimal accountingAmount,
            BigDecimal accountingCurrencyAmount,
            BigDecimal accountingPrice,
            BigDecimal accountingCurrencyPrice,
            BigDecimal initialAccountingPrice,
            BigDecimal initialAccountingCurrencyPrice,
            int accountedMovementCount,
            BigDecimal accountedMovementQuantity,
            BigDecimal accountedMovementAmount,
            BigDecimal accountedMovementCurrencyAmount
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Issue(
            String code,
            String message,
            Map<String, Object> details
    ) {
        public Issue(String code, String message) {
            this(code, message, Map.of());
        }
    }
}
