package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.ActiveGeneration;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.AggregateRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.DimensionRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.MetricRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.NetworkPolicyRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.QuerySpec;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.Selection;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.SortSpec;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.TransitRow;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.WarehouseRow;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesRequest;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.AnalyticsWarning;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.DictionaryItem;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.FilterCapability;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.PurchasePolicyCapability;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.TransitCapability;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.WarehouseGeneration;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Context;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Dimensions;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Metrics;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.InTransitStock;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.NetworkOrderPolicy;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Row;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Totals;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.TransitSupplier;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.WarehouseBreakdown;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.WarehouseOrderPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.ANALYTICS_SCHEMA_VERSION;

@Service
public class FolioProductAnalyticsService {

    public static final int SCHEMA_VERSION = ANALYTICS_SCHEMA_VERSION;
    private static final int NETWORK_POLICY_WAREHOUSE_ID = 7;
    private static final String NETWORK_POLICY_WAREHOUSE_NAME = "Киев ОПТ";
    private static final int TRANSIT_WAREHOUSE_ID = 9;
    private static final String TRANSIT_WAREHOUSE_NAME = "Транспорт";
    private static final List<String> SUPPLIER_ORGANIZATION_TYPES = List.of("Т", "I");
    private static final BigDecimal UNLIMITED_MAXIMUM_THRESHOLD = new BigDecimal("9999");
    private static final List<String> MODES = List.of("ANY", "INCLUDE", "EXCLUDE");
    private static final Set<String> ABC_BASES = Set.of("REVENUE", "GROSS_PROFIT", "SOLD_UNITS");
    private static final Set<String> SORT_FIELDS = Set.of(
            "sku", "productName", "physicalQuantity", "inventoryValue", "soldUnits",
            "salesRevenue", "salesCogs", "grossProfit", "averageInventoryValue");
    private static final Set<String> SUPPORTED_PRODUCT_FILTERS = Set.of(
            "groups", "groupLevel1", "groupLevel2", "groupLevel3", "groupLevel4",
            "groupLevel5", "groupLevel6", "departments", "productTypes", "units",
            "currentSuppliers", "supplierStates", "skus", "barcodes");
    private static final Set<String> SUPPORTED_MOVEMENT_FILTERS = Set.of(
            "operationKinds", "movementClasses", "demandModes", "documentTypes",
            "stockDirections", "paymentTerms", "customerSegments", "counterparties",
            "organizationTypes");

    private final FolioProductAnalyticsDao dao;

    public FolioProductAnalyticsService(FolioProductAnalyticsDao dao) {
        this.dao = dao;
    }

    @Transactional(transactionManager = "wpTransactionManager", readOnly = true)
    public FolioProductAnalyticsCapabilitiesResponse capabilities(
            FolioProductAnalyticsCapabilitiesRequest request) {
        Scope scope = scope(request.sourceDatabase(), request.warehouseIds(), false);
        boolean compatible = compatible(scope.generations(), scope.warehouseIds());
        List<AnalyticsWarning> warnings = new ArrayList<>();
        String unavailableReason = null;
        if (scope.generations().size() != scope.warehouseIds().size()) {
            unavailableReason = "SNAPSHOT_NOT_READY";
            warnings.add(new AnalyticsWarning("SNAPSHOT_NOT_READY",
                    "At least one requested warehouse has no active product snapshot"));
        } else if (scope.generations().stream()
                .map(ActiveGeneration::analyticsSchemaVersion).distinct().count() > 1) {
            unavailableReason = "INCOMPATIBLE_GENERATIONS";
            warnings.add(new AnalyticsWarning("INCOMPATIBLE_GENERATIONS",
                    "Selected warehouses use different analytics schemas"));
        } else if (!compatible) {
            unavailableReason = "ANALYTICS_SCHEMA_TOO_OLD";
            warnings.add(new AnalyticsWarning("ANALYTICS_SCHEMA_TOO_OLD",
                    "Refresh every selected warehouse with snapshot schema v4"));
        }
        Map<String, List<DictionaryItem>> dictionaries = compatible
                ? dao.dictionaries(scope.sourceDatabase(), scope.warehouseIds()) : Map.of();
        ActiveGeneration networkGeneration = networkPolicyGeneration(scope);
        if (networkGeneration == null
                || networkGeneration.analyticsSchemaVersion() != SCHEMA_VERSION) {
            warnings.add(new AnalyticsWarning("NETWORK_ORDER_POLICY_NOT_READY",
                    "Refresh warehouse 7 (Киев ОПТ) with snapshot schema v4 before purchase planning"));
        }
        ActiveGeneration transitGeneration = referenceGeneration(scope, TRANSIT_WAREHOUSE_ID);
        if (transitGeneration == null
                || transitGeneration.analyticsSchemaVersion() != SCHEMA_VERSION) {
            warnings.add(new AnalyticsWarning("IN_TRANSIT_STOCK_NOT_READY",
                    "Refresh warehouse 9 (Транспорт) with snapshot schema v4 before using in-transit stock"));
        }
        return new FolioProductAnalyticsCapabilitiesResponse(
                true, SCHEMA_VERSION, compatible,
                warehouses(scope.warehouseIds(), scope.generations()),
                capabilitiesMap(compatible, unavailableReason),
                dictionaries, purchasePolicyCapability(networkGeneration),
                transitCapability(transitGeneration),
                List.copyOf(warnings));
    }

    @Transactional(transactionManager = "wpTransactionManager", readOnly = true)
    public FolioProductAnalyticsQueryResponse query(FolioProductAnalyticsQueryRequest request) {
        Scope scope = scope(request.sourceDatabase(), request.warehouseIds(), true);
        Period period = period(request.period(), scope.generations());
        Map<String, Selection> product = productSelections(request.productFilters());
        Map<String, Selection> movement = movementSelections(request.movementFilters());
        assertUnsupportedSelections(request.productFilters(), request.movementFilters());
        String search = search(request.productFilters());
        assertSupportedCalculation(request.calculation());
        String abcBasis = abcBasis(request.calculation());
        boolean includeReturns = request.calculation() == null
                || !Boolean.FALSE.equals(request.calculation().includeReturns());
        int pageSize = pageSize(request.page());
        int offset = decodeOffset(request.page() == null ? null : request.page().cursor());
        List<SortSpec> sort = sort(request.sort());

        Map<String, List<DictionaryItem>> dictionaries = dao.dictionaries(
                scope.sourceDatabase(), scope.warehouseIds());
        validateSelections(scope, product, movement, dictionaries);
        QuerySpec spec = new QuerySpec(scope.sourceDatabase(), scope.warehouseIds(),
                period.from(), period.to(), search, product, movement,
                pageSize, offset, sort, abcBasis);
        var result = dao.query(spec);
        Map<String, String> abc = abcClasses(result.basisRows());
        List<String> pageSkus = result.rows().stream().map(AggregateRow::sku).toList();
        ActiveGeneration networkGeneration = networkPolicyGeneration(scope);
        boolean networkPolicyReady = networkGeneration != null
                && networkGeneration.analyticsSchemaVersion() == SCHEMA_VERSION;
        Map<String, NetworkPolicyRow> networkPolicies = networkPolicyReady
                ? dao.networkPolicies(scope.sourceDatabase(), NETWORK_POLICY_WAREHOUSE_ID, pageSkus)
                : Map.of();
        ActiveGeneration transitGeneration = referenceGeneration(scope, TRANSIT_WAREHOUSE_ID);
        boolean transitReady = transitGeneration != null
                && transitGeneration.analyticsSchemaVersion() == SCHEMA_VERSION;
        Map<String, TransitRow> transitRows = transitReady
                ? dao.transitRows(scope.sourceDatabase(), TRANSIT_WAREHOUSE_ID,
                transitGeneration.id(), pageSkus, SUPPLIER_ORGANIZATION_TYPES)
                : Map.of();
        Map<Integer, String> warehouseNames = scope.generations().stream()
                .collect(Collectors.toMap(ActiveGeneration::warehouseId,
                        value -> label(value.warehouseName(), value.warehouseId())));
        Map<String, List<WarehouseBreakdown>> breakdown = result.warehouseRows().stream()
                .collect(Collectors.groupingBy(WarehouseRow::sku, LinkedHashMap::new,
                        Collectors.mapping(value -> new WarehouseBreakdown(
                                value.warehouseId(), warehouseNames.get(value.warehouseId()),
                                value.currentSupplier(), value.supplierState(),
                                warehouseOrderPolicy(
                                        value.minimumStock(), value.maximumStock()),
                                metrics(value.metrics(), period.days(), includeReturns)),
                                Collectors.toList())));
        List<Row> rows = result.rows().stream().map(value -> {
            List<WarehouseBreakdown> warehouseRows = List.copyOf(
                    breakdown.getOrDefault(value.sku(), List.of()));
            return new Row(
                    value.sku(), value.productName(), abc.getOrDefault(value.sku(), "C"),
                    dimensions(value, warehouseRows),
                    metrics(value.metrics(), period.days(), includeReturns),
                    inTransitStock(transitGeneration, transitRows.get(value.sku())),
                    networkOrderPolicy(networkGeneration,
                            networkPolicies.get(value.sku())),
                    warehouseRows);
        }).toList();
        String nextCursor = offset + rows.size() < result.total().productCount()
                ? encodeOffset(offset + rows.size()) : null;
        List<AnalyticsWarning> warnings = new ArrayList<>();
        warnings.add(new AnalyticsWarning("FACETS_SCOPE_SELECTED_WAREHOUSES",
                "Facet counts describe the selected warehouse snapshots before report filters"));
        warnings.add(new AnalyticsWarning("MONTHLY_AVERAGE_INVENTORY_APPROXIMATION",
                "Average inventory uses monthly snapshot buckets intersecting the requested period"));
        if (includeReturns) {
            warnings.add(new AnalyticsWarning("RETURN_COGS_NOT_AVAILABLE",
                    "Returns are shown separately; gross profit is not net of return COGS"));
        }
        if (!networkPolicyReady) {
            warnings.add(new AnalyticsWarning("NETWORK_ORDER_POLICY_NOT_READY",
                    "Warehouse 7 (Киев ОПТ) has no active snapshot schema v4; network order permission is unknown"));
        }
        if (!transitReady) {
            warnings.add(new AnalyticsWarning("IN_TRANSIT_STOCK_NOT_READY",
                    "Warehouse 9 (Транспорт) has no active snapshot schema v4; in-transit quantity is unknown"));
        }
        Object applied = new AppliedFilters(scope.sourceDatabase(), scope.warehouseIds(),
                new AppliedPeriod(period.from(), period.to()), search, product, movement,
                new AppliedCalculation(abcBasis, includeReturns));
        return new FolioProductAnalyticsQueryResponse(
                true,
                new Context(SCHEMA_VERSION, warehouses(scope.warehouseIds(), scope.generations()),
                        period.from(), period.to()),
                applied,
                new Totals(result.total().productCount(), result.total().warehouseRowCount(),
                        metrics(result.total().metrics(), period.days(), includeReturns)),
                rows, dictionaries, nextCursor, List.copyOf(warnings), List.of());
    }

    private Scope scope(String sourceDatabase, List<Integer> requested, boolean strict) {
        String db = sourceDatabase == null ? "" : sourceDatabase.trim();
        if (db.isEmpty()) throw error("UNSUPPORTED_FILTER_VALUE", "sourceDatabase is required");
        List<Integer> warehouseIds = requested == null ? List.of() : requested.stream()
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (warehouseIds.isEmpty() || warehouseIds.size() > 20
                || warehouseIds.stream().anyMatch(value -> value <= 0)) {
            throw error("UNSUPPORTED_FILTER_VALUE",
                    "warehouseIds must contain 1 to 20 distinct positive values");
        }
        List<ActiveGeneration> generations = dao.activeGenerations(db, warehouseIds);
        if (strict) {
            if (generations.size() != warehouseIds.size()) {
                throw new FolioProductAnalyticsException("SNAPSHOT_NOT_READY",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Every requested warehouse requires an active product snapshot");
            }
            Set<Integer> versions = generations.stream()
                    .map(ActiveGeneration::analyticsSchemaVersion).collect(Collectors.toSet());
            if (versions.size() != 1) {
                throw new FolioProductAnalyticsException("INCOMPATIBLE_GENERATIONS",
                        HttpStatus.CONFLICT, "Selected warehouses use different analytics schemas");
            }
            if (!versions.contains(SCHEMA_VERSION)) {
                throw new FolioProductAnalyticsException("ANALYTICS_SCHEMA_TOO_OLD",
                        HttpStatus.CONFLICT,
                        "Refresh every selected warehouse with product snapshot schema v4");
            }
        }
        return new Scope(db, warehouseIds, generations);
    }

    private static boolean compatible(List<ActiveGeneration> generations, List<Integer> requested) {
        return generations.size() == requested.size()
                && generations.stream().allMatch(value -> value.analyticsSchemaVersion() == SCHEMA_VERSION);
    }

    private ActiveGeneration networkPolicyGeneration(Scope scope) {
        return referenceGeneration(scope, NETWORK_POLICY_WAREHOUSE_ID);
    }

    private ActiveGeneration referenceGeneration(Scope scope, int warehouseId) {
        return scope.generations().stream()
                .filter(value -> value.warehouseId() == warehouseId)
                .findFirst()
                .orElseGet(() -> dao.activeGenerations(
                                scope.sourceDatabase(), List.of(warehouseId))
                        .stream().findFirst().orElse(null));
    }

    private static TransitCapability transitCapability(ActiveGeneration generation) {
        boolean ready = generation != null
                && generation.analyticsSchemaVersion() == SCHEMA_VERSION;
        return new TransitCapability(
                TRANSIT_WAREHOUSE_ID,
                generation == null ? TRANSIT_WAREHOUSE_NAME
                        : label(generation.warehouseName(), TRANSIT_WAREHOUSE_ID),
                ready, generation == null ? null : generation.id(),
                generation == null ? "SNAPSHOT_NOT_READY"
                        : ready ? null : "ANALYTICS_SCHEMA_TOO_OLD",
                SUPPLIER_ORGANIZATION_TYPES,
                "WAREHOUSE_BALANCE_WITH_CONFIRMED_SUPPLIER_ORIGIN");
    }

    private static PurchasePolicyCapability purchasePolicyCapability(
            ActiveGeneration generation) {
        boolean ready = generation != null
                && generation.analyticsSchemaVersion() == SCHEMA_VERSION;
        String reason = generation == null ? "SNAPSHOT_NOT_READY"
                : ready ? null : "ANALYTICS_SCHEMA_TOO_OLD";
        return new PurchasePolicyCapability(
                NETWORK_POLICY_WAREHOUSE_ID,
                generation == null
                        ? NETWORK_POLICY_WAREHOUSE_NAME
                        : label(generation.warehouseName(), NETWORK_POLICY_WAREHOUSE_ID),
                ready, generation == null ? null : generation.id(), reason,
                UNLIMITED_MAXIMUM_THRESHOLD,
                List.of("DO_NOT_ORDER", "FORECAST_ONLY",
                        "FORECAST_PLUS_MINIMUM_STOCK", "UNKNOWN"));
    }

    private static WarehouseOrderPolicy warehouseOrderPolicy(
            BigDecimal minimumStock, BigDecimal maximumStock) {
        String validation = purchasePolicyValidation(minimumStock, maximumStock);
        Boolean allowed;
        String mode;
        BigDecimal reserve;
        if (minimumStock == null || minimumStock.signum() < 0) {
            allowed = null;
            mode = "UNKNOWN";
            reserve = null;
        } else if (minimumStock.signum() == 0) {
            allowed = false;
            mode = "DO_NOT_ORDER";
            reserve = BigDecimal.ZERO;
        } else if (minimumStock.compareTo(BigDecimal.ONE) == 0) {
            allowed = true;
            mode = "FORECAST_ONLY";
            reserve = BigDecimal.ZERO;
        } else {
            allowed = true;
            mode = "FORECAST_PLUS_MINIMUM_STOCK";
            reserve = minimumStock;
        }
        if (Set.of("INVALID_NEGATIVE_MAXIMUM", "MINIMUM_EXCEEDS_MAXIMUM")
                .contains(validation)) {
            allowed = null;
        }
        Boolean maximumLimited = maximumStock == null || maximumStock.signum() < 0
                ? null
                : maximumStock.compareTo(UNLIMITED_MAXIMUM_THRESHOLD) < 0;
        BigDecimal maximumLimit = Boolean.TRUE.equals(maximumLimited)
                ? maximumStock : null;
        return new WarehouseOrderPolicy(
                minimumStock, maximumStock, mode, allowed, reserve,
                maximumLimited, maximumLimit, validation);
    }

    private static String purchasePolicyValidation(
            BigDecimal minimumStock, BigDecimal maximumStock) {
        if (minimumStock == null) return "MINIMUM_STOCK_NOT_SET";
        if (minimumStock.signum() < 0) return "INVALID_NEGATIVE_MINIMUM";
        if (maximumStock == null) return "MAXIMUM_STOCK_NOT_SET";
        if (maximumStock.signum() < 0) return "INVALID_NEGATIVE_MAXIMUM";
        if (minimumStock.signum() > 0
                && maximumStock.compareTo(UNLIMITED_MAXIMUM_THRESHOLD) < 0
                && minimumStock.compareTo(maximumStock) > 0) {
            return "MINIMUM_EXCEEDS_MAXIMUM";
        }
        return "VALID";
    }

    private static NetworkOrderPolicy networkOrderPolicy(
            ActiveGeneration generation, NetworkPolicyRow row) {
        String warehouseName = generation == null
                ? NETWORK_POLICY_WAREHOUSE_NAME
                : label(generation.warehouseName(), NETWORK_POLICY_WAREHOUSE_ID);
        if (generation == null) {
            return new NetworkOrderPolicy(
                    NETWORK_POLICY_WAREHOUSE_ID, warehouseName, null,
                    "SNAPSHOT_NOT_READY", null, null);
        }
        if (generation.analyticsSchemaVersion() != SCHEMA_VERSION) {
            return new NetworkOrderPolicy(
                    NETWORK_POLICY_WAREHOUSE_ID, warehouseName, generation.id(),
                    "ANALYTICS_SCHEMA_TOO_OLD", null, null);
        }
        if (row == null) {
            return new NetworkOrderPolicy(
                    NETWORK_POLICY_WAREHOUSE_ID, warehouseName, generation.id(),
                    "SKU_NOT_PRESENT", null, null);
        }
        WarehouseOrderPolicy policy = warehouseOrderPolicy(
                row.minimumStock(), row.maximumStock());
        String status;
        if (policy.orderAllowed() == null) {
            status = "DATA_ISSUE";
        } else if (!policy.orderAllowed()) {
            status = "BLOCKED_BY_KYIV_OPT";
        } else if ("MAXIMUM_STOCK_NOT_SET".equals(policy.validationState())) {
            status = "ALLOWED_WITH_UNKNOWN_MAXIMUM";
        } else {
            status = "ALLOWED";
        }
        return new NetworkOrderPolicy(
                NETWORK_POLICY_WAREHOUSE_ID, warehouseName, generation.id(),
                status, policy.orderAllowed(), policy);
    }

    private static InTransitStock inTransitStock(
            ActiveGeneration generation, TransitRow row) {
        String warehouseName = generation == null ? TRANSIT_WAREHOUSE_NAME
                : label(generation.warehouseName(), TRANSIT_WAREHOUSE_ID);
        if (generation == null) {
            return new InTransitStock(TRANSIT_WAREHOUSE_ID, warehouseName, null,
                    "SNAPSHOT_NOT_READY", null, null, null, null, null,
                    null, null, List.of());
        }
        if (generation.analyticsSchemaVersion() != SCHEMA_VERSION) {
            return new InTransitStock(TRANSIT_WAREHOUSE_ID, warehouseName, generation.id(),
                    "ANALYTICS_SCHEMA_TOO_OLD", null, null, null, null, null,
                    null, null, List.of());
        }
        if (row == null) {
            return new InTransitStock(TRANSIT_WAREHOUSE_ID, warehouseName, generation.id(),
                    "SKU_NOT_PRESENT", false, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, List.of());
        }
        String status;
        Boolean confirmed;
        BigDecimal availableForPlanning = null;
        if (row.physicalQuantity().signum() < 0) {
            status = "NEGATIVE_TRANSIT_STOCK";
            confirmed = false;
        } else if (row.physicalQuantity().signum() == 0) {
            status = "NO_IN_TRANSIT_STOCK";
            confirmed = true;
            availableForPlanning = BigDecimal.ZERO;
        } else if (row.openingQuantity().signum() > 0) {
            status = "OPENING_BALANCE_UNATTRIBUTED";
            confirmed = false;
        } else if (row.inboundCount() == 0) {
            status = "NO_CONFIRMED_INBOUND";
            confirmed = false;
        } else if (row.supplierInboundCount() != row.inboundCount()) {
            status = "MIXED_ORIGIN";
            confirmed = false;
        } else {
            status = "CONFIRMED_SUPPLIER_ORIGIN";
            confirmed = true;
            availableForPlanning = row.availableQuantity().max(BigDecimal.ZERO);
        }
        List<TransitSupplier> suppliers = row.suppliers().stream()
                .map(value -> new TransitSupplier(value.code(), value.name(),
                        value.receiptQuantity(), value.lastReceiptDate()))
                .toList();
        return new InTransitStock(TRANSIT_WAREHOUSE_ID, warehouseName, generation.id(),
                status, confirmed, row.physicalQuantity(), row.reservedQuantity(),
                row.availableQuantity(), availableForPlanning, row.openingQuantity(),
                row.lastSupplierReceiptDate(), suppliers);
    }

    private static Period period(FolioProductAnalyticsQueryRequest.Period requested,
                                 List<ActiveGeneration> generations) {
        if (requested == null || requested.from() == null || requested.to() == null
                || requested.from().isAfter(requested.to())) {
            throw error("INVALID_PERIOD", "period.from and period.to must define a valid range");
        }
        LocalDate latestAllowed = generations.stream().map(ActiveGeneration::asOfDate)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDate earliestAllowed = generations.stream()
                .filter(value -> value.asOfDate() != null)
                .map(value -> value.asOfDate().minusMonths(value.horizonMonths() - 1L)
                        .withDayOfMonth(1))
                .max(Comparator.naturalOrder()).orElse(null);
        if (latestAllowed == null || earliestAllowed == null
                || requested.from().isBefore(earliestAllowed)
                || requested.to().isAfter(latestAllowed)) {
            throw new FolioProductAnalyticsException("INVALID_PERIOD", HttpStatus.BAD_REQUEST,
                    "Requested period is outside at least one active snapshot horizon",
                    Map.of("earliestAllowed", String.valueOf(earliestAllowed),
                            "latestAllowed", String.valueOf(latestAllowed)));
        }
        return new Period(requested.from(), requested.to(),
                Math.max(1, ChronoUnit.DAYS.between(requested.from(), requested.to()) + 1));
    }

    private static Map<String, Selection> productSelections(
            FolioProductAnalyticsQueryRequest.ProductFilters filters) {
        Map<String, Selection> result = new LinkedHashMap<>();
        if (filters == null) return Map.of();
        put(result, "skus", filters.skus());
        put(result, "groups", filters.groups());
        put(result, "groupLevel1", filters.groupLevel1());
        put(result, "groupLevel2", filters.groupLevel2());
        put(result, "groupLevel3", filters.groupLevel3());
        put(result, "groupLevel4", filters.groupLevel4());
        put(result, "groupLevel5", filters.groupLevel5());
        put(result, "groupLevel6", filters.groupLevel6());
        put(result, "departments", filters.departments());
        put(result, "productTypes", filters.productTypes());
        put(result, "units", filters.units());
        put(result, "currentSuppliers", filters.currentSuppliers());
        put(result, "supplierStates", filters.supplierStates());
        put(result, "barcodes", filters.barcodes());
        return Map.copyOf(result);
    }

    private static String search(FolioProductAnalyticsQueryRequest.ProductFilters filters) {
        if (filters == null || filters.search() == null) return null;
        String value = filters.search().trim();
        if (value.isEmpty()) return null;
        if (value.length() > 200) {
            throw error("UNSUPPORTED_FILTER_VALUE", "productFilters.search is limited to 200 characters");
        }
        return value;
    }

    private static Map<String, Selection> movementSelections(
            FolioProductAnalyticsQueryRequest.MovementFilters filters) {
        Map<String, Selection> result = new LinkedHashMap<>();
        if (filters == null) return Map.of();
        put(result, "operationKinds", filters.operationKinds());
        put(result, "movementClasses", filters.movementClasses());
        put(result, "demandModes", filters.demandModes());
        put(result, "documentTypes", filters.documentTypes());
        put(result, "stockDirections", filters.stockDirections());
        put(result, "paymentTerms", filters.paymentTerms());
        put(result, "customerSegments", filters.customerSegments());
        put(result, "counterparties", filters.counterparties());
        put(result, "organizationTypes", filters.organizationTypes());
        return Map.copyOf(result);
    }

    private static void put(Map<String, Selection> target, String key,
                            FolioProductAnalyticsQueryRequest.Selection raw) {
        Selection value = selection(raw);
        if (!"ANY".equals(value.mode())) target.put(key, value);
    }

    private static Selection selection(FolioProductAnalyticsQueryRequest.Selection raw) {
        if (raw == null) return new Selection("ANY", List.of());
        String mode = raw.mode() == null ? "ANY" : raw.mode().trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw error("INVALID_FILTER_MODE", "Unsupported filter mode: " + mode);
        List<String> values = raw.values() == null ? List.of() : raw.values().stream()
                .filter(java.util.Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
        if (values.isEmpty()) return new Selection("ANY", List.of());
        if ("ANY".equals(mode)) return new Selection("ANY", List.of());
        if (values.size() > 500) throw error("UNSUPPORTED_FILTER_VALUE",
                "A filter cannot contain more than 500 values");
        return new Selection(mode, values);
    }

    private static void assertUnsupportedSelections(
            FolioProductAnalyticsQueryRequest.ProductFilters product,
            FolioProductAnalyticsQueryRequest.MovementFilters movement) {
        if (product != null) {
            rejectUnsupported("brands", product.brands(), "SOURCE_NOT_CONFIRMED");
        }
        if (movement != null) {
            rejectUnsupported("salesManagerCodes", movement.salesManagerCodes(), "SOURCE_NOT_CONFIRMED");
            rejectUnsupported("sourceWarehouseIds", movement.sourceWarehouseIds(), "SOURCE_NOT_CONFIRMED");
            rejectUnsupported("destinationWarehouseIds", movement.destinationWarehouseIds(), "SOURCE_NOT_CONFIRMED");
        }
    }

    private static void rejectUnsupported(String field,
                                          FolioProductAnalyticsQueryRequest.Selection selection,
                                          String reason) {
        Selection normalized = selection(selection);
        if (!"ANY".equals(normalized.mode())) {
            throw new FolioProductAnalyticsException("SOURCE_FIELD_NOT_CONFIRMED",
                    HttpStatus.BAD_REQUEST, "Filter is not supported: " + field,
                    Map.of("filter", field, "reason", reason));
        }
    }

    private void validateSelections(Scope scope, Map<String, Selection> product,
                                    Map<String, Selection> movement,
                                    Map<String, List<DictionaryItem>> dictionaries) {
        for (Map.Entry<String, Selection> entry : product.entrySet()) {
            if (!SUPPORTED_PRODUCT_FILTERS.contains(entry.getKey())) {
                throw error("UNSUPPORTED_FILTER", "Unsupported product filter: " + entry.getKey());
            }
            if ("skus".equals(entry.getKey())) {
                validateSkus(scope, entry.getValue());
            } else if ("barcodes".equals(entry.getKey())) {
                validateBarcodes(scope, entry.getValue());
            } else {
                String dictionary = "groups".equals(entry.getKey()) ? "productGroups" : entry.getKey();
                validateValues(entry.getKey(), entry.getValue(), dictionaries.get(dictionary));
            }
        }
        for (Map.Entry<String, Selection> entry : movement.entrySet()) {
            if (!SUPPORTED_MOVEMENT_FILTERS.contains(entry.getKey())) {
                throw error("UNSUPPORTED_FILTER", "Unsupported movement filter: " + entry.getKey());
            }
            validateValues(entry.getKey(), entry.getValue(), dictionaries.get(entry.getKey()));
        }
    }

    private void validateSkus(Scope scope, Selection selection) {
        Set<String> available = Set.copyOf(dao.existingSkus(
                scope.sourceDatabase(), scope.warehouseIds(), selection.values()));
        List<String> unsupported = selection.values().stream()
                .filter(value -> !available.contains(value)).toList();
        if (!unsupported.isEmpty()) {
            throw new FolioProductAnalyticsException("UNSUPPORTED_FILTER_VALUE",
                    HttpStatus.BAD_REQUEST, "Unknown SKU in selected active snapshots",
                    Map.of("filter", "skus", "values", unsupported));
        }
    }

    private void validateBarcodes(Scope scope, Selection selection) {
        Set<String> available = Set.copyOf(dao.existingBarcodes(
                scope.sourceDatabase(), scope.warehouseIds(), selection.values()));
        List<String> unsupported = selection.values().stream()
                .filter(value -> !available.contains(value)).toList();
        if (!unsupported.isEmpty()) {
            throw new FolioProductAnalyticsException("UNSUPPORTED_FILTER_VALUE",
                    HttpStatus.BAD_REQUEST, "Unknown primary barcode in selected active snapshots",
                    Map.of("filter", "barcodes", "values", unsupported));
        }
    }

    private static void assertSupportedCalculation(
            FolioProductAnalyticsQueryRequest.Calculation calculation) {
        if (calculation == null) return;
        if (calculation.serviceLevelPercent() != null) {
            throw new FolioProductAnalyticsException("SOURCE_FIELD_NOT_CONFIRMED",
                    HttpStatus.BAD_REQUEST,
                    "serviceLevelPercent requires confirmed supplier lead time and replenishment inputs",
                    Map.of("field", "calculation.serviceLevelPercent",
                            "reason", "PURCHASE_PLANNING_NOT_READY"));
        }
        if (calculation.demandHorizonDays() != null) {
            throw new FolioProductAnalyticsException("SOURCE_FIELD_NOT_CONFIRMED",
                    HttpStatus.BAD_REQUEST,
                    "demandHorizonDays requires the future purchase-planning calculation contract",
                    Map.of("field", "calculation.demandHorizonDays",
                            "reason", "PURCHASE_PLANNING_NOT_READY"));
        }
    }

    private static void validateValues(String field, Selection selection,
                                       List<DictionaryItem> dictionary) {
        Set<String> available = dictionary == null ? Set.of() : dictionary.stream()
                .map(DictionaryItem::code).collect(Collectors.toSet());
        List<String> unsupported = selection.values().stream()
                .filter(value -> !available.contains(value)).toList();
        if (!unsupported.isEmpty()) {
            throw new FolioProductAnalyticsException("UNSUPPORTED_FILTER_VALUE",
                    HttpStatus.BAD_REQUEST, "Unsupported values for filter " + field,
                    Map.of("filter", field, "values", unsupported));
        }
    }

    private static String abcBasis(FolioProductAnalyticsQueryRequest.Calculation calculation) {
        String value = calculation == null || calculation.abcBasis() == null
                ? "GROSS_PROFIT" : calculation.abcBasis().trim().toUpperCase(Locale.ROOT);
        if (!ABC_BASES.contains(value)) throw error("UNSUPPORTED_FILTER_VALUE",
                "abcBasis must be REVENUE, GROSS_PROFIT or SOLD_UNITS");
        return value;
    }

    private static int pageSize(FolioProductAnalyticsQueryRequest.Page page) {
        int size = page == null || page.size() == null ? 50 : page.size();
        if (size < 1 || size > 500) throw error("UNSUPPORTED_FILTER_VALUE",
                "page.size must be between 1 and 500");
        return size;
    }

    private static List<SortSpec> sort(List<FolioProductAnalyticsQueryRequest.Sort> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > 3) throw error("UNSUPPORTED_FILTER_VALUE",
                "At most three sort fields are supported");
        List<SortSpec> result = new ArrayList<>();
        for (var item : requested) {
            if (item == null || !SORT_FIELDS.contains(item.field())) {
                throw new FolioProductAnalyticsException("UNSUPPORTED_FILTER",
                        HttpStatus.BAD_REQUEST, "Unsupported sort field",
                        Map.of("field", "sort.field",
                                "value", item == null ? "null" : String.valueOf(item.field()),
                                "supportedValues", SORT_FIELDS));
            }
            String direction = item.direction() == null
                    ? "ASC" : item.direction().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ASC", "DESC").contains(direction)) {
                throw error("UNSUPPORTED_FILTER_VALUE", "Sort direction must be ASC or DESC");
            }
            result.add(new SortSpec(item.field(), direction));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> abcClasses(List<FolioProductAnalyticsDao.BasisRow> rows) {
        BigDecimal total = rows.stream().map(FolioProductAnalyticsDao.BasisRow::value)
                .map(value -> value.max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, String> result = new LinkedHashMap<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (var row : rows) {
            BigDecimal shareBefore = total.signum() == 0 ? BigDecimal.ONE
                    : cumulative.divide(total, 8, RoundingMode.HALF_UP);
            result.put(row.sku(), shareBefore.compareTo(new BigDecimal("0.80")) < 0 ? "A"
                    : shareBefore.compareTo(new BigDecimal("0.95")) < 0 ? "B" : "C");
            cumulative = cumulative.add(row.value().max(BigDecimal.ZERO));
        }
        return Map.copyOf(result);
    }

    private static Dimensions dimensions(AggregateRow value,
                                         List<WarehouseBreakdown> warehouseRows) {
        DimensionRow d = value.dimensions();
        List<String> suppliers = warehouseRows.stream()
                .map(WarehouseBreakdown::currentSupplier)
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(supplier -> !supplier.isEmpty())
                .distinct().sorted().toList();
        return new Dimensions(d.groupLevel1Code(), d.groupLevel1Name(),
                d.groupLevel2Code(), d.groupLevel2Name(), d.groupLevel3Code(),
                d.groupLevel3Name(), d.groupLevel4Code(), d.groupLevel4Name(),
                d.groupLevel5Code(), d.groupLevel5Name(), d.groupLevel6Code(),
                d.groupLevel6Name(), d.departmentCode(), d.departmentName(),
                d.productTypeCode(), d.productTypeName(), d.unitCode(), d.unitName(),
                d.packageQuantity(), d.minimumOrderQuantity(), d.minimumStock(),
                d.maximumStock(), d.primaryBarcode(), d.brandCode(), d.brandName(), suppliers);
    }

    private static Metrics metrics(MetricRow row, long periodDays, boolean includeReturns) {
        BigDecimal inventoryTurns = ratio(row.salesCogs(), row.averageInventoryValue());
        BigDecimal gmroi = ratio(row.grossProfit(), row.averageInventoryValue());
        BigDecimal margin = ratio(row.grossProfit(), row.salesRevenue());
        if (margin != null) margin = margin.multiply(BigDecimal.valueOf(100));
        BigDecimal coverage = row.regularSoldUnits().signum() > 0
                ? row.availableQuantity().max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(periodDays))
                .divide(row.regularSoldUnits(), 2, RoundingMode.HALF_UP) : null;
        return new Metrics(row.physicalQuantity(), row.reservedQuantity(),
                row.availableQuantity(), row.inventoryValue(), row.soldUnits(),
                row.salesRevenue(), row.salesCogs(), row.grossProfit(),
                includeReturns ? row.returnQuantity() : BigDecimal.ZERO,
                includeReturns ? row.returnRevenue() : BigDecimal.ZERO,
                row.regularSoldUnits(), row.regularRevenue(), row.regularCogs(),
                row.oneOffSoldUnits(), row.oneOffRevenue(), row.oneOffCogs(),
                row.averageInventoryValue(), inventoryTurns, gmroi, margin, coverage);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() <= 0) return null;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private static List<WarehouseGeneration> warehouses(List<Integer> requested,
                                                        List<ActiveGeneration> values) {
        Map<Integer, ActiveGeneration> byId = values.stream()
                .collect(Collectors.toMap(ActiveGeneration::warehouseId, Function.identity()));
        return requested.stream().map(id -> {
            ActiveGeneration value = byId.get(id);
            return value == null
                    ? new WarehouseGeneration(id, label(null, id), null, null, null,
                    null, null, "NOT_READY")
                    : new WarehouseGeneration(id, label(value.warehouseName(), id), value.id(),
                    value.asOfDate(), value.completedAt(), value.horizonMonths(),
                    value.analyticsSchemaVersion(), value.status());
        }).toList();
    }

    private static Map<String, FilterCapability> capabilitiesMap(
            boolean compatible, String unavailableReason) {
        Map<String, FilterCapability> result = new LinkedHashMap<>();
        List<String> supported = List.of("productGroups", "groupLevel1", "groupLevel2",
                "groupLevel3", "groupLevel4", "groupLevel5", "groupLevel6",
                "departments", "productTypes", "units", "skus",
                "currentSuppliers", "supplierStates", "barcodes",
                "operationKinds", "movementClasses", "demandModes", "documentTypes",
                "stockDirections", "paymentTerms", "customerSegments", "counterparties",
                "organizationTypes");
        for (String key : supported) {
            result.put(key, new FilterCapability(compatible, true,
                    compatible ? MODES : List.of(), compatible ? null : unavailableReason));
        }
        for (String key : List.of("skuSearch", "abc")) {
            result.put(key, new FilterCapability(compatible, false, List.of(),
                    compatible ? null : unavailableReason));
        }
        result.put("multiWarehouse", new FilterCapability(
                compatible, true, List.of(), compatible ? null : unavailableReason));
        for (String key : List.of("brands", "salesManagerCodes",
                "sourceWarehouseIds", "destinationWarehouseIds", "scmSupplierTerms",
                "openSupplierOrders", "xyz", "dailyStockout")) {
            result.put(key, new FilterCapability(false, true, List.of(),
                    "SOURCE_NOT_CONFIRMED"));
        }
        return Map.copyOf(result);
    }

    private static String label(String name, int id) {
        return name == null || name.isBlank() ? "Warehouse " + id : name;
    }

    private static int decodeOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith("offset:")) throw new IllegalArgumentException();
            int offset = Integer.parseInt(decoded.substring(7));
            if (offset < 0) throw new IllegalArgumentException();
            return offset;
        } catch (RuntimeException error) {
            throw error("UNSUPPORTED_FILTER_VALUE", "Invalid page cursor");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("offset:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static FolioProductAnalyticsException error(String code, String message) {
        return new FolioProductAnalyticsException(code, HttpStatus.BAD_REQUEST, message);
    }

    private record Scope(String sourceDatabase, List<Integer> warehouseIds,
                         List<ActiveGeneration> generations) { }
    private record Period(LocalDate from, LocalDate to, long days) { }
    private record AppliedPeriod(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate from,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate to) { }
    private record AppliedCalculation(String abcBasis, boolean includeReturns) { }
    private record AppliedFilters(String sourceDatabase, List<Integer> warehouseIds,
                                  AppliedPeriod period,
                                  String search,
                                  Map<String, Selection> productFilters,
                                  Map<String, Selection> movementFilters,
                                  AppliedCalculation calculation) { }
}
