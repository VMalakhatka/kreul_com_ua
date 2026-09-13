package org.example.proect.lavka.service.folio;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FolioWarehouseUsageTest {
    private Calculation calculation(List<Integer> ids, Integer context) {
        return new Calculation("SOLD_UNITS",true,null,null,
                new AvailabilityCalculation(true,null,null,null,"a".repeat(64),
                        List.of(new WarehouseGroup("odesa","Odesa",List.of(5,15),null)),context,null,null),null,ids);
    }
    @Test void storageDoesNotContributeToGroupAvailability() {
        var raw=calculation(List.of(15,15),null);
        var ids=FolioWarehouseUsage.stockOnly(raw,List.of(5,15));
        assertThat(ids).containsExactly(15);
        var availability=FolioWarehouseUsage.availability(raw,List.of(5,15),ids);
        assertThat(availability.warehouseGroups().get(0).warehouseIds()).containsExactly(5);
        assertThat(availability.warehouseGroupsRevision()).isEqualTo("a".repeat(64));
    }
    @Test void unknownAndStockOnlyHistoryContextsAreRejected() {
        assertThatThrownBy(()->FolioWarehouseUsage.stockOnly(calculation(List.of(20),null),List.of(5,15)))
                .isInstanceOf(FolioProductAnalyticsException.class);
        assertThatThrownBy(()->FolioWarehouseUsage.availability(calculation(List.of(15),15),List.of(5,15),List.of(15)))
                .isInstanceOf(FolioProductAnalyticsException.class);
    }
    @Test void oldRequestsKeepFullUsageAndStockOnlyGroupHasNoHistory() {
        assertThat(FolioWarehouseUsage.stockOnly(null,List.of(5,15))).isEmpty();
        assertThat(FolioWarehouseUsage.availability(calculation(List.of(5,15),null),List.of(5,15),List.of(5,15)).warehouseGroups()).isEmpty();
    }
}
