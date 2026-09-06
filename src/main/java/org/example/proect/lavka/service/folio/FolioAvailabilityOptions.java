package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.*;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.*;

public final class FolioAvailabilityOptions {
    public static final String BASIS = "PHYSICAL_END_OF_DAY";
    public static final Set<String> STATUSES = Set.of("MEASURED", "NOT_APPLICABLE",
            "POLICY_NOT_CONFIRMED", "DATA_INCOMPLETE", "PERIOD_OUTSIDE_HORIZON", "SNAPSHOT_NOT_READY");
    private FolioAvailabilityOptions() { }

    public static AvailabilityCalculation normalize(Calculation calculation, List<Integer> warehouses) {
        AvailabilityCalculation raw = calculation == null ? null : calculation.availability();
        if (raw == null) return null;
        if (Boolean.FALSE.equals(raw.enabled())) {
            if (raw.filter() != null) fail("Availability filters require enabled=true");
            return null;
        }
        exact(raw.basis(), BASIS); exact(raw.minimumStockEligibility(), "CURRENT_POLICY_GT_ZERO");
        exact(raw.presentation(), "WAREHOUSES_AND_GROUPS");
        List<WarehouseGroup> groups = new ArrayList<>();
        Set<String> codes = new HashSet<>(); Set<Integer> used = new HashSet<>();
        for (WarehouseGroup group : raw.warehouseGroups() == null ? List.<WarehouseGroup>of() : raw.warehouseGroups()) {
            if (group == null || group.code() == null || !group.code().trim().matches("[A-Za-z0-9_-]{1,64}"))
                fail("Group code must contain 1..64 letters, digits, underscore or hyphen");
            String code = group.code().trim();
            if (!codes.add(code)) fail("Group codes must be unique");
            exact(group.availabilityMode(), "ANY_ELIGIBLE_MEMBER");
            if (group.warehouseIds() == null || group.warehouseIds().isEmpty()
                    || group.warehouseIds().stream().anyMatch(id -> id == null || !warehouses.contains(id)))
                fail("Every group member must belong to request.warehouseIds");
            List<Integer> ids = group.warehouseIds().stream().distinct().sorted().toList();
            for (int id : ids) if (!used.add(id)) fail("Overlapping warehouse groups are not supported");
            String name = group.name() == null || group.name().isBlank() ? code : group.name().trim();
            if (name.length() > 200) fail("Group name exceeds 200 characters");
            groups.add(new WarehouseGroup(code, name, ids, "ANY_ELIGIBLE_MEMBER"));
        }
        groups.sort(Comparator.comparing(WarehouseGroup::code));
        String revision = raw.warehouseGroupsRevision();
        if (!groups.isEmpty() && (revision == null || !revision.matches("[a-fA-F0-9]{64}")))
            fail("warehouseGroupsRevision must be the SHA-256 revision supplied by WordPress");
        revision = groups.isEmpty() ? null : revision.toLowerCase(Locale.ROOT);
        if (raw.warehouseId() != null && !warehouses.contains(raw.warehouseId())) fail("Unknown availability warehouseId");
        if (raw.groupCode() != null && !codes.contains(raw.groupCode())) fail("Unknown availability groupCode");
        if (raw.groupCode() != null && raw.warehouseId() != null) fail("Select either a warehouse or a group context");
        AvailabilityFilter filter = raw.filter();
        if (filter != null) {
            range(filter.availabilityPercentFrom(), filter.availabilityPercentTo());
            range(filter.stockoutPercentFrom(), filter.stockoutPercentTo());
            if (filter.availabilityStatus() != null && filter.availabilityStatus().stream()
                    .anyMatch(status -> status == null || !STATUSES.contains(status))) fail("Unknown availability status");
        }
        AvailabilityCalculation result = new AvailabilityCalculation(true, BASIS, "CURRENT_POLICY_GT_ZERO",
                "WAREHOUSES_AND_GROUPS", revision, List.copyOf(groups), raw.warehouseId(), raw.groupCode(), filter);
        if (filter != null && context(result, warehouses).isEmpty()) fail("Availability filters require a warehouseId or groupCode context");
        return result;
    }

    public static List<Integer> context(AvailabilityCalculation options, List<Integer> warehouses) {
        if (options == null) return List.of();
        if (options.warehouseId() != null) return List.of(options.warehouseId());
        if (options.groupCode() != null) return options.warehouseGroups().stream()
                .filter(g -> g.code().equals(options.groupCode())).findFirst().orElseThrow().warehouseIds();
        return warehouses.size() == 1 ? warehouses : List.of();
    }

    private static void exact(String raw, String expected) {
        if (raw != null && !expected.equals(raw)) fail("Supported value: " + expected);
    }
    private static void range(BigDecimal from, BigDecimal to) {
        for (BigDecimal value : new BigDecimal[]{from, to})
            if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0))
                fail("Availability percentage must be between 0 and 100");
        if (from != null && to != null && from.compareTo(to) > 0) fail("Invalid percentage range");
    }
    private static void fail(String message) {
        throw new FolioProductAnalyticsException("INVALID_AVAILABILITY", HttpStatus.BAD_REQUEST, message);
    }
}
