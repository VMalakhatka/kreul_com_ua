package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FolioProductAnalyticsCapabilitiesResponse(
        boolean ok,
        int analyticsSchemaVersion,
        boolean compatibleGeneration,
        List<WarehouseGeneration> warehouses,
        Map<String, FilterCapability> filters,
        Map<String, List<DictionaryItem>> dictionaries,
        PurchasePolicyCapability purchasePolicy,
        TransitCapability transit,
        Map<String, Object> features,
        List<AnalyticsWarning> warnings) {

    public record WarehouseGeneration(
            int id,
            String name,
            Long generationId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOf,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime completedAt,
            Integer horizonMonths,
            Integer analyticsSchemaVersion,
            String status) {
    }

    public record FilterCapability(
            boolean supported,
            boolean multi,
            List<String> modes,
            String reason) {
    }

    public record DictionaryItem(String code, String name, long count) {
    }

    public record AnalyticsWarning(String code, String message) {
    }

    public record PurchasePolicyCapability(
            int networkPolicyWarehouseId,
            String networkPolicyWarehouseName,
            boolean networkPolicyReady,
            Long generationId,
            String unavailableReason,
            BigDecimal unlimitedMaximumThreshold,
            List<String> replenishmentModes) {
    }

    public record TransitCapability(
            Integer warehouseId,
            String warehouseName,
            boolean ready,
            Long generationId,
            String unavailableReason,
            List<String> supplierOrganizationTypes,
            String calculationMode,
            boolean configurable,
            int calculationVersion,
            int maxWarehouseCount,
            boolean enabled,
            List<Integer> warehouseIds,
            String configurationRevision,
            List<TransitSourceCapability> sources,
            NetworkSnapshotConsistency networkSnapshotConsistency,
            List<String> warnings) {
    }

    public record NetworkSnapshotConsistency(String status, boolean confirmed,
            List<TransitSourceCapability> salesSources, List<TransitSourceCapability> transitSources,
            String recommendation) { }

    public record TransitSourceCapability(int warehouseId, String warehouseName, Long generationId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate asOf,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS") LocalDateTime completedAt,
            Integer analyticsSchemaVersion, boolean ready, String unavailableReason) { }
}
