package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.*;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FolioTransitAnalyticsTest {
    @Test void absentInheritsNineButEmptyDisablesAndIdsAreCanonical() {
        assertThat(FolioTransitAnalytics.normalize(null)).isEqualTo(config(9));
        assertThat(config(10,9,9).warehouseIds()).containsExactly(9,10);
        assertThat(config(10,9,9).configurationRevision())
                .isEqualTo("11e07e7bb591992307e25655831247578120a67b9436b172b61a4df9ba743557");
        assertThat(config().warehouseIds()).isEmpty();
        var disabled = FolioTransitAnalytics.stock(FolioTransitAnalytics.capability(config(),List.of(),List.of(1),5),Map.of(),"SKU");
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.warehouseId()).isNull();
        assertThat(disabled.availableForPlanningQuantity()).isEqualByComparingTo("0");
    }

    @Test void invalidRevisionIdsAndSourceLimitFailClosed() {
        assertThatThrownBy(() -> config(-1)).isInstanceOf(FolioProductAnalyticsException.class);
        assertThatThrownBy(() -> config(java.util.stream.IntStream.rangeClosed(1,17).boxed().toArray(Integer[]::new)))
                .isInstanceOf(FolioProductAnalyticsException.class);
        assertThatThrownBy(() -> FolioTransitAnalytics.normalize(calculation(new TransitCalculation(List.of(9),"bad"))))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        e -> assertThat(e.code()).isEqualTo("TRANSIT_CONFIGURATION_REVISION_MISMATCH"));
        var canonical = config(10,9);
        assertThat(FolioTransitAnalytics.normalize(calculation(new TransitCalculation(List.of(10,9,9),
                canonical.configurationRevision().toUpperCase(Locale.ROOT))))).isEqualTo(canonical);
    }

    @Test void sumsAvailableNotHistoricalReceiptsAndPreservesLegacySingleton() {
        var a = row("14","2","12",0,2,2);
        var b = row("8","0","8",0,1,1);
        var data = Map.of(9,Map.of("SKU",a),10,Map.of("SKU",b));
        var stock = FolioTransitAnalytics.stock(capability(9,10),data,"SKU");
        assertThat(stock.availableForPlanningQuantity()).isEqualByComparingTo("20");
        assertThat(stock.knownAvailableForPlanningQuantity()).isEqualByComparingTo("20");
        assertThat(stock.sources()).hasSize(2);
        assertThat(stock.ready()).isTrue();
        assertThat(stock.warehouseId()).isNull();
        assertThat(stock.sources().get(0).suppliers().get(0).receiptQuantityInHorizon()).isEqualByComparingTo("1000");
        var legacy = FolioTransitAnalytics.stock(capability(9),data,"SKU");
        assertThat(legacy.warehouseId()).isEqualTo(9);
        assertThat(legacy.generationId()).isEqualTo(109);
        assertThat(legacy.status()).isEqualTo("CONFIRMED_SUPPLIER_ORIGIN");
        assertThat(legacy.availableForPlanningQuantity()).isEqualByComparingTo("12");
    }

    @Test void unknownMissingMixedOrNegativeSourceNeverCreatesCompleteTotal() {
        List<TransitRow> invalid = List.of(row("8","0","8",0,2,1),row("8","0","8",1,2,2),
                row("8","0","8",-1,2,2),row("8","9","-1",0,2,2),row("8","0","9",0,2,2));
        for (var bad : invalid) {
            var stock = FolioTransitAnalytics.stock(capability(9,10),Map.of(
                    9,Map.of("SKU",row("12","0","12",0,1,1)),10,Map.of("SKU",bad)),"SKU");
            assertThat(stock.status()).isEqualTo("INCOMPLETE_TRANSIT_DATA");
            assertThat(stock.availableForPlanningQuantity()).isNull();
            assertThat(stock.knownAvailableForPlanningQuantity()).isEqualByComparingTo("12");
        }
        var absent = FolioTransitAnalytics.stock(capability(9),Map.of(),"SKU");
        assertThat(absent.status()).isEqualTo("SKU_NOT_PRESENT");
        assertThat(absent.availableForPlanningQuantity()).isNull();
        var missing = FolioTransitAnalytics.capability(config(9,10),List.of(generation(9)),List.of(1),5);
        assertThat(missing.ready()).isFalse();
        assertThat(missing.sources().get(1).unavailableReason()).isEqualTo("SNAPSHOT_NOT_READY");
    }

    @Test void zeroIsConfirmedOnlyFromAnExistingNonnegativeBalancedCard() {
        var stock = FolioTransitAnalytics.stock(capability(9),Map.of(9,Map.of("SKU",row("0","0","0",0,0,0))),"SKU");
        assertThat(stock.status()).isEqualTo("NO_IN_TRANSIT_STOCK");
        assertThat(stock.availableForPlanningQuantity()).isEqualByComparingTo("0");
    }

    @Test void overlapWithAnalysisStockBlocksDeductionRatherThanDoubleCounting() {
        var cap = FolioTransitAnalytics.capability(config(9),List.of(generation(9)),List.of(1,9),5);
        var stock = FolioTransitAnalytics.stock(cap,Map.of(9,Map.of("SKU",row("12","0","12",0,1,1))),"SKU");
        assertThat(stock.status()).isEqualTo("TRANSIT_SCOPE_OVERLAP");
        assertThat(stock.availableForPlanningQuantity()).isNull();
        assertThat(stock.sources().get(0).availableForPlanningQuantity()).isEqualByComparingTo("12");
    }

    @Test void oldSchemaAndIncompleteMetadataAreNotReady() {
        var old = new ActiveGeneration(109,"Fixture",9,"Transit",36,4,LocalDate.of(2026,9,1),
                LocalDateTime.of(2026,9,1,0,0),"ACTIVE");
        assertThat(FolioTransitAnalytics.capability(config(9),List.of(old),List.of(1),5).unavailableReason())
                .isEqualTo("ANALYTICS_SCHEMA_TOO_OLD");
        var undated = new ActiveGeneration(109,"Fixture",9,"Transit",36,5,null,null,"ACTIVE");
        assertThat(FolioTransitAnalytics.capability(config(9),List.of(undated),List.of(1),5).unavailableReason())
                .isEqualTo("INCOMPLETE_SNAPSHOT_METADATA");
    }

    static TransitCalculation config(Integer... ids) {
        return FolioTransitAnalytics.normalize(calculation(new TransitCalculation(List.of(ids),null)));
    }
    static Calculation calculation(TransitCalculation config) { return new Calculation(null,null,null,null,null,config); }
    static ActiveGeneration generation(int id) {
        return new ActiveGeneration(100+id,"Fixture",id,"Warehouse "+id,36,5,
                LocalDate.of(2026,9,1),LocalDateTime.of(2026,9,1,0,0),"ACTIVE");
    }
    private static org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.TransitCapability capability(Integer... ids) {
        return FolioTransitAnalytics.capability(config(ids),Arrays.stream(ids).map(FolioTransitAnalyticsTest::generation).toList(),List.of(1),5);
    }
    static TransitRow row(String physical,String reserve,String available,int opening,int inbound,int supplierInbound) {
        return new TransitRow("SKU",new BigDecimal(physical),new BigDecimal(reserve),new BigDecimal(available),
                BigDecimal.valueOf(opening),inbound,supplierInbound,LocalDate.of(2026,8,31),
                List.of(new TransitSupplierRow("SUP","Supplier",new BigDecimal("1000"),LocalDate.of(2026,8,31))));
    }
}
