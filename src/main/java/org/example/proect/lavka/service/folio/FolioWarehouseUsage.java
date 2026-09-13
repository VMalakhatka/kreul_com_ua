package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.*;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Current stock can cover demand without contributing sales, policy or daily history. */
public final class FolioWarehouseUsage {
    private FolioWarehouseUsage() { }
    public static List<Integer> stockOnly(Calculation calculation, List<Integer> scope) {
        List<Integer> ids = calculation == null ? null : calculation.stockOnlyWarehouseIds();
        if (ids == null) return List.of();
        if (ids.stream().anyMatch(id -> id == null || id <= 0 || !scope.contains(id)))
            throw new FolioProductAnalyticsException("INVALID_WAREHOUSE_USAGE", HttpStatus.BAD_REQUEST,
                    "Stock-only warehouses must belong to the selected scope");
        return ids.stream().distinct().sorted().toList();
    }
    public static AvailabilityCalculation availability(Calculation calculation, List<Integer> scope, List<Integer> stockOnly) {
        if (stockOnly.isEmpty() || calculation == null || calculation.availability() == null)
            return FolioAvailabilityOptions.normalize(calculation, scope);
        AvailabilityCalculation raw = calculation.availability();
        if (Boolean.FALSE.equals(raw.enabled())) return FolioAvailabilityOptions.normalize(calculation, scope);
        // Validate the supplied groups before filtering; an invalid member must not disappear.
        FolioAvailabilityOptions.normalize(calculation, scope);
        List<Integer> demand = scope.stream().filter(id -> !stockOnly.contains(id)).toList();
        List<WarehouseGroup> groups = raw.warehouseGroups() == null ? List.of() : raw.warehouseGroups().stream()
                .map(g -> new WarehouseGroup(g.code(), g.name(), g.warehouseIds().stream()
                        .filter(id -> !stockOnly.contains(id)).toList(), g.availabilityMode()))
                .filter(g -> !g.warehouseIds().isEmpty()).toList();
        AvailabilityCalculation filtered = new AvailabilityCalculation(raw.enabled(), raw.basis(),
                raw.minimumStockEligibility(), raw.presentation(), raw.warehouseGroupsRevision(), groups,
                raw.warehouseId(), raw.groupCode(), raw.filter());
        return FolioAvailabilityOptions.normalize(new Calculation(calculation.abcBasis(), calculation.includeReturns(),
                calculation.serviceLevelPercent(), calculation.demandHorizonDays(), filtered, calculation.transit(), stockOnly), demand);
    }
}
