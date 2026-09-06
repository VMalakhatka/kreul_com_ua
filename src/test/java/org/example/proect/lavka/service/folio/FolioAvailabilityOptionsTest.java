package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FolioAvailabilityOptionsTest {
    private static final String REVISION = "a".repeat(64);

    @Test void groupsAreNormalizedButNeverImplicitlyCombined() {
        var normalized = normalize(options(List.of(group("KYIV", 7, 1, 7)), REVISION, null));
        assertThat(normalized.warehouseGroups().get(0).warehouseIds()).containsExactly(1, 7);
        assertThat(FolioAvailabilityOptions.context(normalized, List.of(1, 7))).isEmpty();
        assertThat(normalized.basis()).isEqualTo("PHYSICAL_END_OF_DAY");
    }

    @Test void invalidGroupsAndRevisionAreRejected() {
        invalid(options(List.of(group("KYIV", 1, 7), group("OTHER", 7)), REVISION, null));
        invalid(options(List.of(group("KYIV", 1), group("KYIV", 7)), REVISION, null));
        invalid(options(List.of(group("KYIV")), REVISION, null));
        invalid(options(List.of(group("KYIV", 20)), REVISION, null));
        invalid(options(List.of(group("KYIV", 1, 7)), null, null));
        invalid(options(List.of(group("KYIV", 1, 7)), "bad", null));
    }

    @Test void percentagesAndExplicitFilterContextAreValidated() {
        invalid(options(List.of(), null, new AvailabilityFilter(BigDecimal.ZERO, null, null, null, null)));
        var bad = new AvailabilityCalculation(true, null, null, null, null, List.of(), 1, null,
                new AvailabilityFilter(new BigDecimal("101"), null, null, null, null));
        invalid(bad);
        invalid(new AvailabilityCalculation(true, null, null, null, null, List.of(), 1, null,
                new AvailabilityFilter(null, null, null, null, Arrays.asList("MEASURED", null))));
        invalid(new AvailabilityCalculation(true, "RESERVED_AVAILABLE", null, null, null, List.of(), 1, null, null));
    }

    private static WarehouseGroup group(String code, Integer... members) {
        return new WarehouseGroup(code, null, List.of(members), null);
    }
    private static AvailabilityCalculation options(List<WarehouseGroup> groups, String revision, AvailabilityFilter filter) {
        return new AvailabilityCalculation(true, null, null, null, revision, groups, null, null, filter);
    }
    private static AvailabilityCalculation normalize(AvailabilityCalculation value) {
        return FolioAvailabilityOptions.normalize(new Calculation(null, null, null, null, value), List.of(1, 7));
    }
    private static void invalid(AvailabilityCalculation value) {
        assertThatThrownBy(() -> normalize(value)).isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                e -> assertThat(e.code()).isEqualTo("INVALID_AVAILABILITY"));
    }
}
