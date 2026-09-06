package org.example.proect.lavka.dto.folio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

public record FolioProductAnalyticsQueryRequest(
        @NotBlank String sourceDatabase,
        @NotEmpty List<@Positive Integer> warehouseIds,
        @Valid Period period,
        ProductFilters productFilters,
        MovementFilters movementFilters,
        Calculation calculation,
        Page page,
        List<Sort> sort) {

    public record Period(LocalDate from, LocalDate to) {
    }

    public record Selection(String mode, List<String> values) {
    }

    public record ProductFilters(
            String search,
            Selection skus,
            Selection groups,
            Selection groupLevel1,
            Selection groupLevel2,
            Selection groupLevel3,
            Selection groupLevel4,
            Selection groupLevel5,
            Selection groupLevel6,
            Selection departments,
            Selection productTypes,
            Selection units,
            Selection currentSuppliers,
            Selection supplierStates,
            Selection brands,
            Selection barcodes) {
    }

    public record MovementFilters(
            Selection operationKinds,
            Selection movementClasses,
            Selection demandModes,
            Selection documentTypes,
            Selection stockDirections,
            Selection paymentTerms,
            Selection customerSegments,
            Selection counterparties,
            Selection organizationTypes,
            Selection salesManagerCodes,
            Selection sourceWarehouseIds,
            Selection destinationWarehouseIds) {
    }

    public record Calculation(
            String abcBasis,
            Boolean includeReturns,
            Integer serviceLevelPercent,
            Integer demandHorizonDays,
            AvailabilityCalculation availability,
            TransitCalculation transit) {
        public Calculation(String abcBasis, Boolean includeReturns, Integer serviceLevelPercent,
                           Integer demandHorizonDays) {
            this(abcBasis, includeReturns, serviceLevelPercent, demandHorizonDays, null, null);
        }
        public Calculation(String abcBasis, Boolean includeReturns, Integer serviceLevelPercent,
                           Integer demandHorizonDays, AvailabilityCalculation availability) {
            this(abcBasis, includeReturns, serviceLevelPercent, demandHorizonDays, availability, null);
        }
    }

    public record TransitCalculation(List<Integer> warehouseIds, String configurationRevision) { }

    public record AvailabilityCalculation(Boolean enabled, String basis,
            String minimumStockEligibility, String presentation,
            String warehouseGroupsRevision, List<WarehouseGroup> warehouseGroups,
            Integer warehouseId, String groupCode, AvailabilityFilter filter) { }

    public record WarehouseGroup(String code, String name, List<Integer> warehouseIds,
                                 String availabilityMode) { }

    public record AvailabilityFilter(BigDecimal availabilityPercentFrom,
            BigDecimal availabilityPercentTo, BigDecimal stockoutPercentFrom,
            BigDecimal stockoutPercentTo, List<String> availabilityStatus) { }

    public record Page(Integer size, String cursor) {
    }

    public record Sort(String field, String direction) {
    }
}
