package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.AnalyticsWarning;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.DictionaryItem;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.WarehouseGeneration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record FolioProductAnalyticsQueryResponse(
        boolean ok,
        Context context,
        Object appliedFilters,
        Totals totals,
        List<Row> rows,
        Map<String, List<DictionaryItem>> facets,
        String nextCursor,
        List<AnalyticsWarning> warnings,
        List<Object> errors) {

    public record Context(
            int analyticsSchemaVersion,
            List<WarehouseGeneration> warehouses,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate periodFrom,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate periodTo) {
    }

    public record Totals(
            long productCount,
            long warehouseRowCount,
            Metrics metrics) {
    }

    public record Row(
            String sku,
            String productName,
            String abcClass,
            Dimensions dimensions,
            Metrics metrics,
            InTransitStock inTransitStock,
            NetworkOrderPolicy networkOrderPolicy,
            List<WarehouseBreakdown> warehouseBreakdown) {
    }

    public record WarehouseBreakdown(
            int warehouseId,
            String warehouseName,
            String currentSupplier,
            String supplierState,
            WarehouseOrderPolicy orderPolicy,
            Metrics metrics) {
    }

    public record WarehouseOrderPolicy(
            BigDecimal minimumStock,
            BigDecimal maximumStock,
            String replenishmentMode,
            Boolean orderAllowed,
            BigDecimal reserveAboveForecast,
            Boolean maximumStockLimited,
            BigDecimal maximumStockLimit,
            String validationState) {
    }

    public record NetworkOrderPolicy(
            int policyWarehouseId,
            String policyWarehouseName,
            Long generationId,
            String status,
            Boolean orderAllowed,
            WarehouseOrderPolicy policy) {
    }

    public record Dimensions(
            String groupLevel1Code, String groupLevel1Name,
            String groupLevel2Code, String groupLevel2Name,
            String groupLevel3Code, String groupLevel3Name,
            String groupLevel4Code, String groupLevel4Name,
            String groupLevel5Code, String groupLevel5Name,
            String groupLevel6Code, String groupLevel6Name,
            String departmentCode, String departmentName,
            String productTypeCode, String productTypeName,
            String unitCode, String unitName,
            BigDecimal packageQuantity, BigDecimal minimumOrderQuantity,
            BigDecimal minimumStock, BigDecimal maximumStock,
            String primaryBarcode,
            String brandCode, String brandName,
            List<String> currentSuppliers) {
    }

    public record InTransitStock(
            int warehouseId,
            String warehouseName,
            Long generationId,
            String status,
            Boolean supplierOriginConfirmed,
            BigDecimal physicalQuantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity,
            BigDecimal availableForPlanningQuantity,
            BigDecimal openingQuantityAtHorizon,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate lastSupplierReceiptDate,
            List<TransitSupplier> suppliers) {
    }

    public record TransitSupplier(
            String code,
            String name,
            BigDecimal receiptQuantityInHorizon,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate lastReceiptDate) {
    }

    public record Metrics(
            BigDecimal physicalQuantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity,
            BigDecimal inventoryValue,
            BigDecimal soldUnits,
            BigDecimal salesRevenue,
            BigDecimal salesCogs,
            BigDecimal grossProfit,
            BigDecimal returnQuantity,
            BigDecimal returnRevenue,
            BigDecimal regularSoldUnits,
            BigDecimal regularRevenue,
            BigDecimal regularCogs,
            BigDecimal oneOffSoldUnits,
            BigDecimal oneOffRevenue,
            BigDecimal oneOffCogs,
            BigDecimal averageInventoryValue,
            BigDecimal inventoryTurns,
            BigDecimal gmroi,
            BigDecimal grossMarginPercent,
            BigDecimal coverageDays) {
    }
}
