package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.ActiveGeneration;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.AggregateRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.BasisRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.DimensionRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.MetricRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.NetworkPolicyRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.QueryResult;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.TotalRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.TransitRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.TransitSupplierRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.WarehouseRow;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesRequest;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.DictionaryItem;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioProductAnalyticsServiceTest {

    @Test
    void freeStockFixRequiresRebuiltSnapshotsEvenForSchemaFive() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5))).thenReturn(generations(5));
        assertThatThrownBy(() -> new FolioProductAnalyticsService(dao).query(request(null, null)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        error -> assertThat(error.code()).isEqualTo("ANALYTICS_SCHEMA_TOO_OLD"));
    }

    @Test
    void capabilitiesExposeOnlyConfirmedSchemaV6Fields() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        when(dao.activeGenerations("Paint_Ua", List.of(7)))
                .thenReturn(List.of(generation(107, 7, "Киев ОПТ", 6)));
        when(dao.activeGenerations("Paint_Ua", List.of(9)))
                .thenReturn(List.of(generation(109, 9, "Транспорт", 6)));
        when(dao.dictionaries("Paint_Ua", List.of(1, 5))).thenReturn(Map.of());

        var response = new FolioProductAnalyticsService(dao).capabilities(
                new FolioProductAnalyticsCapabilitiesRequest("Paint_Ua", List.of(1, 5)));

        assertThat(response.compatibleGeneration()).isTrue();
        assertThat(response.filters().get("productGroups").supported()).isTrue();
        assertThat(response.filters().get("supplierStates").supported()).isTrue();
        assertThat(response.filters().get("brands").supported()).isFalse();
        assertThat(response.filters().get("brands").reason()).isEqualTo("SOURCE_NOT_CONFIRMED");
        assertThat(response.filters().get("barcodes").supported()).isTrue();
        assertThat(response.filters().get("dailyStockout").supported()).isTrue();
        assertThat(response.features()).containsKey("availability");
        assertThat(response.purchasePolicy().networkPolicyReady()).isTrue();
        assertThat(response.purchasePolicy().networkPolicyWarehouseId()).isEqualTo(7);
        assertThat(response.purchasePolicy().unlimitedMaximumThreshold())
                .isEqualByComparingTo("9999");
        assertThat(response.transit().ready()).isTrue();
        assertThat(response.transit().warehouseId()).isEqualTo(9);
    }

    @Test
    void capabilitiesExplainMissingWarehouseSnapshot() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(List.of(generation(101, 1, "Kyiv", 6)));

        var response = new FolioProductAnalyticsService(dao).capabilities(
                new FolioProductAnalyticsCapabilitiesRequest("Paint_Ua", List.of(1, 5)));

        assertThat(response.compatibleGeneration()).isFalse();
        assertThat(response.filters().get("productGroups").reason())
                .isEqualTo("SNAPSHOT_NOT_READY");
        assertThat(response.warnings()).extracting("code")
                .contains("SNAPSHOT_NOT_READY", "NETWORK_ORDER_POLICY_NOT_READY");
    }

    @Test
    void queryRecalculatesMultiWarehouseRatiosFromAggregatedSums() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        when(dao.dictionaries("Paint_Ua", List.of(1, 5))).thenReturn(Map.of());
        when(dao.activeGenerations("Paint_Ua", List.of(9)))
                .thenReturn(List.of(generation(109, 9, "Транспорт", 6)));
        MetricRow metrics = metrics("12", "100", "50", "200", "120", "80", "40");
        when(dao.query(any())).thenReturn(new QueryResult(
                new TotalRow(1, 2, metrics),
                List.of(new AggregateRow("SKU-1", "Product", "SUP", dimensions(), metrics)),
                List.of(
                        new WarehouseRow(1, "SKU-1", "Product", "SUP", "CURRENT", metrics),
                        new WarehouseRow(5, "SKU-1", "Product", "SUP", "CURRENT", metrics)),
                List.of(new BasisRow("SKU-1", new BigDecimal("80")))));
        when(dao.transitRows("Paint_Ua", 9, 109, List.of("SKU-1"), List.of("Т", "I")))
                .thenReturn(Map.of("SKU-1", new TransitRow(
                        "SKU-1", new BigDecimal("12"), new BigDecimal("2"),
                        new BigDecimal("10"), BigDecimal.ZERO, 2, 2,
                        LocalDate.of(2026, 8, 30), List.of(new TransitSupplierRow(
                        "SUP-1", "Supplier", new BigDecimal("12"),
                        LocalDate.of(2026, 8, 30))))));

        var response = new FolioProductAnalyticsService(dao).query(request(null, null));

        assertThat(response.totals().productCount()).isEqualTo(1);
        assertThat(response.rows()).hasSize(1);
        assertThat(response.rows().get(0).warehouseBreakdown()).hasSize(2);
        assertThat(response.rows().get(0).abcClass()).isEqualTo("A");
        assertThat(response.rows().get(0).dimensions().primaryBarcode())
                .isEqualTo("4000798123456");
        assertThat(response.rows().get(0).inTransitStock().status())
                .isEqualTo("NETWORK_SNAPSHOT_CONSISTENCY_UNCONFIRMED");
        assertThat(response.rows().get(0).inTransitStock().availableForPlanningQuantity())
                .isNull();
        assertThat(response.rows().get(0).inTransitStock().sources().get(0).supplierInTransitAvailableQuantity())
                .isEqualByComparingTo("10");
        assertThat(response.totals().metrics().grossMarginPercent())
                .isEqualByComparingTo("40.000000");
        assertThat(response.totals().metrics().inventoryTurns())
                .isEqualByComparingTo("3.000000");
        assertThat(response.totals().metrics().gmroi())
                .isEqualByComparingTo("2.000000");
    }

    @Test
    void minMaxStockProduceWarehouseAndNetworkOrderPolicies() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        when(dao.activeGenerations("Paint_Ua", List.of(7)))
                .thenReturn(List.of(generation(107, 7, "Киев ОПТ", 6)));
        when(dao.dictionaries("Paint_Ua", List.of(1, 5))).thenReturn(Map.of());
        MetricRow metrics = metrics("12", "100", "50", "200", "120", "80", "40");
        when(dao.query(any())).thenReturn(new QueryResult(
                new TotalRow(2, 3, metrics),
                List.of(
                        new AggregateRow("SKU-1", "Product", "SUP", dimensions(), metrics),
                        new AggregateRow("SKU-2", "Product 2", "SUP", dimensions(), metrics)),
                List.of(
                        new WarehouseRow(1, "SKU-1", "Product", "SUP", "CURRENT",
                                BigDecimal.ZERO, new BigDecimal("9999"), metrics),
                        new WarehouseRow(5, "SKU-1", "Product", "SUP", "CURRENT",
                                new BigDecimal("5"), new BigDecimal("20"), metrics),
                        new WarehouseRow(1, "SKU-2", "Product 2", "SUP", "CURRENT",
                                BigDecimal.ONE, new BigDecimal("15"), metrics)),
                List.of(new BasisRow("SKU-1", new BigDecimal("80")),
                        new BasisRow("SKU-2", new BigDecimal("20")))));
        when(dao.networkPolicies("Paint_Ua", 7, List.of("SKU-1", "SKU-2")))
                .thenReturn(Map.of(
                        "SKU-1", new NetworkPolicyRow(
                                "SKU-1", BigDecimal.ZERO, new BigDecimal("9999")),
                        "SKU-2", new NetworkPolicyRow(
                                "SKU-2", BigDecimal.ONE, new BigDecimal("9999"))));

        var response = new FolioProductAnalyticsService(dao).query(request(null, null));

        var row = response.rows().get(0);
        assertThat(row.networkOrderPolicy().status()).isEqualTo("BLOCKED_BY_KYIV_OPT");
        assertThat(row.networkOrderPolicy().orderAllowed()).isFalse();
        assertThat(row.warehouseBreakdown().get(0).orderPolicy().replenishmentMode())
                .isEqualTo("DO_NOT_ORDER");
        assertThat(row.warehouseBreakdown().get(0).orderPolicy().maximumStockLimited())
                .isFalse();
        assertThat(row.warehouseBreakdown().get(1).orderPolicy().replenishmentMode())
                .isEqualTo("FORECAST_PLUS_MINIMUM_STOCK");
        assertThat(row.warehouseBreakdown().get(1).orderPolicy().reserveAboveForecast())
                .isEqualByComparingTo("5");
        assertThat(row.warehouseBreakdown().get(1).orderPolicy().maximumStockLimit())
                .isEqualByComparingTo("20");
        var forecastOnly = response.rows().get(1);
        assertThat(forecastOnly.networkOrderPolicy().status()).isEqualTo("ALLOWED");
        assertThat(forecastOnly.warehouseBreakdown().get(0).orderPolicy().replenishmentMode())
                .isEqualTo("FORECAST_ONLY");
        assertThat(forecastOnly.warehouseBreakdown().get(0).orderPolicy().reserveAboveForecast())
                .isEqualByComparingTo("0");
    }

    @Test
    void contradictoryNetworkLimitsNeverBecomeOrderPermission() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1)))
                .thenReturn(List.of(generation(101, 1, "Kyiv", 6)));
        when(dao.activeGenerations("Paint_Ua", List.of(7)))
                .thenReturn(List.of(generation(107, 7, "Киев ОПТ", 6)));
        when(dao.dictionaries("Paint_Ua", List.of(1))).thenReturn(Map.of());
        MetricRow metrics = metrics("1", "1", "1", "1", "1", "1", "1");
        when(dao.query(any())).thenReturn(new QueryResult(
                new TotalRow(1, 1, metrics),
                List.of(new AggregateRow("SKU-1", "Product", "SUP", dimensions(), metrics)),
                List.of(new WarehouseRow(1, "SKU-1", "Product", "SUP", "CURRENT",
                        new BigDecimal("10"), new BigDecimal("5"), metrics)),
                List.of(new BasisRow("SKU-1", BigDecimal.ONE))));
        when(dao.networkPolicies("Paint_Ua", 7, List.of("SKU-1")))
                .thenReturn(Map.of("SKU-1", new NetworkPolicyRow(
                        "SKU-1", new BigDecimal("10"), new BigDecimal("5"))));

        var request = new FolioProductAnalyticsQueryRequest(
                "Paint_Ua", List.of(1),
                new FolioProductAnalyticsQueryRequest.Period(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
                null, null, null,
                new FolioProductAnalyticsQueryRequest.Page(50, null), List.of());
        var response = new FolioProductAnalyticsService(dao).query(request);

        assertThat(response.rows().get(0).networkOrderPolicy().status())
                .isEqualTo("DATA_ISSUE");
        assertThat(response.rows().get(0).networkOrderPolicy().orderAllowed()).isNull();
        assertThat(response.rows().get(0).warehouseBreakdown().get(0)
                .orderPolicy().validationState()).isEqualTo("MINIMUM_EXCEEDS_MAXIMUM");
        assertThat(response.rows().get(0).warehouseBreakdown().get(0)
                .orderPolicy().orderAllowed()).isNull();
    }

    @Test
    void unsupportedBrandIsRejectedInsteadOfSilentlyIgnored() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        var brand = new FolioProductAnalyticsQueryRequest.Selection("INCLUDE", List.of("KREUL"));
        var product = new FolioProductAnalyticsQueryRequest.ProductFilters(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, brand, null);

        assertThatThrownBy(() -> new FolioProductAnalyticsService(dao)
                .query(request(product, null)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_FIELD_NOT_CONFIRMED"));
    }

    @Test
    void purchasePlanningInputsAreRejectedUntilSourceDataExists() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        var calculation = new FolioProductAnalyticsQueryRequest.Calculation(
                "GROSS_PROFIT", true, 95, null);

        assertThatThrownBy(() -> new FolioProductAnalyticsService(dao)
                .query(request(null, calculation)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_FIELD_NOT_CONFIRMED"));
    }

    @Test
    void oldSnapshotSchemaCannotBeQueried() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(2));

        assertThatThrownBy(() -> new FolioProductAnalyticsService(dao)
                .query(request(null, null)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        error -> assertThat(error.code()).isEqualTo("ANALYTICS_SCHEMA_TOO_OLD"));
    }

    @Test
    void unknownSelectedSkuIsRejected() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        when(dao.dictionaries("Paint_Ua", List.of(1, 5))).thenReturn(Map.of());
        when(dao.existingSkus("Paint_Ua", List.of(1, 5), List.of("MISSING")))
                .thenReturn(List.of());
        var skus = new FolioProductAnalyticsQueryRequest.Selection(
                "INCLUDE", List.of("MISSING"));
        var product = new FolioProductAnalyticsQueryRequest.ProductFilters(
                null, skus, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new FolioProductAnalyticsService(dao)
                .query(request(product, null)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        error -> assertThat(error.code()).isEqualTo("UNSUPPORTED_FILTER_VALUE"));
    }

    @Test
    void primaryBarcodeFilterIsValidatedAndApplied() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        when(dao.dictionaries("Paint_Ua", List.of(1, 5))).thenReturn(Map.of());
        when(dao.existingBarcodes("Paint_Ua", List.of(1, 5),
                List.of("4000798123456"))).thenReturn(List.of("4000798123456"));
        when(dao.query(any())).thenReturn(emptyResult());
        var barcodes = new FolioProductAnalyticsQueryRequest.Selection(
                "INCLUDE", List.of("4000798123456"));
        var product = new FolioProductAnalyticsQueryRequest.ProductFilters(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, barcodes);

        new FolioProductAnalyticsService(dao).query(request(product, null));

        ArgumentCaptor<FolioProductAnalyticsDao.QuerySpec> captor =
                ArgumentCaptor.forClass(FolioProductAnalyticsDao.QuerySpec.class);
        verify(dao).query(captor.capture());
        assertThat(captor.getValue().productSelections().get("barcodes").values())
                .containsExactly("4000798123456");
    }

    @Test
    void threeWarehouseGroupExclusionIsAppliedServerSide() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        List<ActiveGeneration> generations = List.of(
                generation(101, 1, "Kyiv", 6),
                generation(102, 5, "Odesa", 6),
                generation(103, 7, "Wholesale", 6));
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5, 7)))
                .thenReturn(generations);
        when(dao.dictionaries("Paint_Ua", List.of(1, 5, 7))).thenReturn(Map.of(
                "productGroups", List.of(new DictionaryItem("SERVICE", "Service", 10))));
        when(dao.query(any())).thenReturn(emptyResult());
        var groups = new FolioProductAnalyticsQueryRequest.Selection(
                "EXCLUDE", List.of("SERVICE"));
        var product = new FolioProductAnalyticsQueryRequest.ProductFilters(
                null, null, groups, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        var request = new FolioProductAnalyticsQueryRequest(
                "Paint_Ua", List.of(7, 1, 5),
                new FolioProductAnalyticsQueryRequest.Period(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
                product, null, null,
                new FolioProductAnalyticsQueryRequest.Page(50, null), List.of());

        new FolioProductAnalyticsService(dao).query(request);

        ArgumentCaptor<FolioProductAnalyticsDao.QuerySpec> captor =
                ArgumentCaptor.forClass(FolioProductAnalyticsDao.QuerySpec.class);
        verify(dao).query(captor.capture());
        assertThat(captor.getValue().warehouseIds()).containsExactly(1, 5, 7);
        assertThat(captor.getValue().productSelections().get("groups").mode())
                .isEqualTo("EXCLUDE");
        assertThat(captor.getValue().productSelections().get("groups").values())
                .containsExactly("SERVICE");
    }

    @Test
    void mixedSnapshotSchemasAreRejected() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(List.of(
                        generation(101, 1, "Kyiv", 6),
                        generation(102, 5, "Odesa", 2)));

        assertThatThrownBy(() -> new FolioProductAnalyticsService(dao)
                .query(request(null, null)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        error -> assertThat(error.code()).isEqualTo("INCOMPATIBLE_GENERATIONS"));
    }

    @Test
    void responsesSerializeDatesAsIsoStrings() throws Exception {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5)))
                .thenReturn(generations(6));
        when(dao.dictionaries("Paint_Ua", List.of(1, 5))).thenReturn(Map.of());
        when(dao.query(any())).thenReturn(emptyResult());

        var response = new FolioProductAnalyticsService(dao).query(request(null, null));
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(response);

        assertThat(json).contains("\"asOf\":\"2026-08-31\"");
        assertThat(json).contains("\"periodFrom\":\"2026-08-01\"");
        assertThat(json).contains("\"from\":\"2026-08-01\"");
    }

    @Test
    void configurableTransitDoesNotChangeDemandScopeAndBindsEverySourceGenerationToCursor() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua",List.of(1,5))).thenReturn(generations(6));
        when(dao.activeGenerations("Paint_Ua",List.of(9,10))).thenReturn(List.of(
                generation(109,9,"Transit A",6),generation(110,10,"Transit B",6)));
        MetricRow metric = metrics("30","100","90","200","120","80","40");
        when(dao.query(any())).thenReturn(new QueryResult(new TotalRow(2,2,metric),
                List.of(new AggregateRow("SKU-1","Product","SUP",dimensions(),metric)),
                List.of(),List.of(new BasisRow("SKU-1",BigDecimal.TEN))));
        when(dao.transitRows("Paint_Ua",9,109,List.of("SKU-1"),List.of("Т","I")))
                .thenReturn(Map.of("SKU-1",FolioTransitAnalyticsTest.row("14","2","12",0,2,2)));
        when(dao.transitRows("Paint_Ua",10,110,List.of("SKU-1"),List.of("Т","I")))
                .thenReturn(Map.of("SKU-1",FolioTransitAnalyticsTest.row("8","0","8",0,1,1)));
        var service = new FolioProductAnalyticsService(dao);
        var response = service.query(transitRequest(List.of(10,9,9),null));
        assertThat(response.rows().get(0).inTransitStock().availableForPlanningQuantity()).isNull();
        assertThat(response.rows().get(0).inTransitStock().availableQuantity()).isEqualByComparingTo("20");
        assertThat(response.rows().get(0).inTransitStock().availableForNetworkPlanningQuantity()).isNull();
        assertThat(response.context().transit().networkSnapshotConsistency().salesSources())
                .extracting("generationId").containsExactly(101L,102L);
        assertThat(response.rows().get(0).metrics().regularSoldUnits()).isEqualByComparingTo("90");
        assertThat(response.totals().metrics().availableQuantity()).isEqualByComparingTo("30");
        assertThat(response.context().warehouses()).extracting("id").containsExactly(1,5);
        assertThat(response.context().transit().warehouseIds()).containsExactly(9,10);
        var spec = ArgumentCaptor.forClass(FolioProductAnalyticsDao.QuerySpec.class);
        verify(dao).query(spec.capture());
        assertThat(spec.getValue().warehouseIds()).containsExactly(1,5);
        service.query(transitRequest(List.of(9,10),response.nextCursor()));
        when(dao.activeGenerations("Paint_Ua",List.of(9,10))).thenReturn(List.of(
                generation(109,9,"Transit A",6),generation(210,10,"Transit B",6)));
        assertThatThrownBy(() -> service.query(transitRequest(List.of(9,10),response.nextCursor())))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        e -> assertThat(e.code()).isEqualTo("ANALYTICS_CURSOR_EXPIRED"));
        assertThatThrownBy(() -> service.query(transitRequest(List.of(9),response.nextCursor())))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        e -> assertThat(e.code()).isEqualTo("ANALYTICS_CURSOR_EXPIRED"));
    }

    @Test
    void capabilitiesDescribeMissingTransitSourcesIndividually() {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua",List.of(1,5))).thenReturn(generations(6));
        when(dao.activeGenerations("Paint_Ua",List.of(9,10)))
                .thenReturn(List.of(generation(109,9,"Transit A",6)));
        var service = new FolioProductAnalyticsService(dao);
        var response = service.capabilities(new FolioProductAnalyticsCapabilitiesRequest("Paint_Ua",List.of(1,5),
                FolioTransitAnalyticsTest.calculation(FolioTransitAnalyticsTest.config(9,10))));
        assertThat(response.compatibleGeneration()).isTrue();
        assertThat(response.transit().configurable()).isTrue();
        assertThat(response.transit().ready()).isFalse();
        assertThat(response.transit().sources().get(0).ready()).isTrue();
        assertThat(response.transit().sources().get(1).unavailableReason()).isEqualTo("SNAPSHOT_NOT_READY");
    }

    private static FolioProductAnalyticsQueryRequest transitRequest(List<Integer> ids,String cursor) {
        var base = request(null,FolioTransitAnalyticsTest.calculation(
                new FolioProductAnalyticsQueryRequest.TransitCalculation(ids,null)));
        return new FolioProductAnalyticsQueryRequest(base.sourceDatabase(),base.warehouseIds(),base.period(),
                base.productFilters(),base.movementFilters(),base.calculation(),
                new FolioProductAnalyticsQueryRequest.Page(1,cursor),base.sort());
    }

    @Test
    void availabilityHasPhysicalDetailsAndDoesNotInventAnOverallUnion() throws Exception {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5))).thenReturn(generations(6));
        MetricRow metric = metrics("1", "10", "1", "20", "10", "10", "10");
        when(dao.query(any())).thenReturn(new QueryResult(new TotalRow(1, 1, metric),
                List.of(new AggregateRow("SKU-1", "Product", "SUP", dimensions(), metric)),
                List.of(new WarehouseRow(1, "SKU-1", "Product", "SUP", "CURRENT", metric)),
                List.of(new BasisRow("SKU-1", BigDecimal.TEN))));
        var response = new FolioProductAnalyticsService(dao).query(availabilityRequest("a".repeat(64), null));
        var row = response.rows().get(0);
        assertThat(row.availability().status()).isEqualTo("CONTEXT_REQUIRED");
        assertThat(row.availability().availabilityPercent()).isNull();
        assertThat(row.warehouseBreakdown()).hasSize(2);
        assertThat(row.warehouseBreakdown().get(1).warehouseId()).isEqualTo(5);
        assertThat(row.warehouseBreakdown().get(1).metrics()).isNull();
        assertThat(row.warehouseBreakdown().get(1).availability().status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(row.warehouseGroupBreakdown()).hasSize(1);
        assertThat(row.metrics().coverageDays()).isEqualByComparingTo("31");
    }

    @Test
    void cursorRejectsChangedGroupRevisionOrSnapshotInsteadOfMixingExportRows() throws Exception {
        FolioProductAnalyticsDao dao = mock(FolioProductAnalyticsDao.class);
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5))).thenReturn(generations(6));
        MetricRow metric = metrics("1", "10", "1", "20", "10", "10", "10");
        when(dao.query(any())).thenReturn(new QueryResult(new TotalRow(2, 2, metric),
                List.of(new AggregateRow("SKU-1", "Product", "SUP", dimensions(), metric)),
                List.of(), List.of(new BasisRow("SKU-1", BigDecimal.TEN))));
        var service = new FolioProductAnalyticsService(dao);
        String cursor = service.query(availabilityRequest("a".repeat(64), null)).nextCursor();
        assertThat(cursor).isNotBlank();
        service.query(availabilityRequest("a".repeat(64), cursor));
        assertThatThrownBy(() -> service.query(availabilityRequest("b".repeat(64), cursor)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        e -> assertThat(e.code()).isEqualTo("ANALYTICS_CURSOR_EXPIRED"));
        when(dao.activeGenerations("Paint_Ua", List.of(1, 5))).thenReturn(List.of(
                generation(201, 1, "Kyiv", 6), generation(102, 5, "Odesa", 6)));
        assertThatThrownBy(() -> service.query(availabilityRequest("a".repeat(64), cursor)))
                .isInstanceOfSatisfying(FolioProductAnalyticsException.class,
                        e -> assertThat(e.code()).isEqualTo("ANALYTICS_CURSOR_EXPIRED"));
    }

    private static FolioProductAnalyticsQueryRequest availabilityRequest(String revision, String cursor) throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var request = mapper.readValue("""
                {"sourceDatabase":"Paint_Ua","warehouseIds":[1,5],
                 "period":{"from":"2026-08-01","to":"2026-08-31"},
                 "calculation":{"availability":{"enabled":true,
                    "warehouseGroupsRevision":"%s",
                    "warehouseGroups":[{"code":"TEST","warehouseIds":[1,5]}]}},
                 "page":{"size":1}}
                """.formatted(revision), FolioProductAnalyticsQueryRequest.class);
        return new FolioProductAnalyticsQueryRequest(request.sourceDatabase(), request.warehouseIds(), request.period(),
                request.productFilters(), request.movementFilters(), request.calculation(),
                new FolioProductAnalyticsQueryRequest.Page(1, cursor), request.sort());
    }

    private static FolioProductAnalyticsQueryRequest request(
            FolioProductAnalyticsQueryRequest.ProductFilters product,
            FolioProductAnalyticsQueryRequest.Calculation calculation) {
        return new FolioProductAnalyticsQueryRequest(
                "Paint_Ua", List.of(1, 5),
                new FolioProductAnalyticsQueryRequest.Period(
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
                product, null, calculation,
                new FolioProductAnalyticsQueryRequest.Page(50, null), List.of());
    }

    private static List<ActiveGeneration> generations(int schema) {
        return List.of(
                generation(101, 1, "Kyiv", schema),
                generation(102, 5, "Odesa", schema));
    }

    private static ActiveGeneration generation(long id, int warehouseId,
                                                String name, int schema) {
        return new ActiveGeneration(id, "Paint_Ua", warehouseId, name, 24, schema,
                LocalDate.of(2026, 8, 31),
                LocalDateTime.of(2026, 8, 31, 1, warehouseId), "ACTIVE");
    }

    private static QueryResult emptyResult() {
        MetricRow zero = metrics("0", "0", "0", "0", "0", "0", "0");
        return new QueryResult(new TotalRow(0, 0, zero), List.of(), List.of(), List.of());
    }

    private static MetricRow metrics(String available, String inventory, String sold,
                                     String revenue, String cogs, String profit,
                                     String averageInventory) {
        return new MetricRow(
                bd(available), BigDecimal.ZERO, bd(available), bd(inventory), bd(sold),
                bd(revenue), bd(cogs), bd(profit), BigDecimal.ZERO, BigDecimal.ZERO,
                bd(sold), bd(revenue), bd(cogs), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, bd(averageInventory));
    }

    private static DimensionRow dimensions() {
        return new DimensionRow(
                "G1", "G1", null, null, null, null, null, null,
                null, null, null, null, "D", "D", "T", "T", "шт", "шт",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "4000798123456", null, null);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
