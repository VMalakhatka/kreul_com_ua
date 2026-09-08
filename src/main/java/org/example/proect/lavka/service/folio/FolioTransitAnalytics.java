package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.ActiveGeneration;
import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.TransitRow;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.Calculation;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.TransitCalculation;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.*;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.*;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only transit context. It never allocates stock or changes the demand scope. */
public final class FolioTransitAnalytics {
    public static final int VERSION = 3;
    public static final int MAX_WAREHOUSES = 16;
    public static final List<String> SUPPLIER_TYPES = List.of("Т", "I");
    private FolioTransitAnalytics() { }

    public static TransitCalculation normalize(Calculation calculation) {
        TransitCalculation raw = calculation == null ? null : calculation.transit();
        List<Integer> ids = raw == null || raw.warehouseIds() == null ? List.of(9) : raw.warehouseIds();
        if (ids.size() > 256 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw invalid("INVALID_TRANSIT_CONFIGURATION", "Transit warehouseIds must be positive integers");
        ids = ids.stream().distinct().sorted().toList();
        if (ids.size() > MAX_WAREHOUSES)
            throw invalid("INVALID_TRANSIT_CONFIGURATION", "At most 16 transit warehouses are supported");
        String revision = revision(ids);
        if (raw != null && raw.configurationRevision() != null
                && !revision.equalsIgnoreCase(raw.configurationRevision()))
            throw invalid("TRANSIT_CONFIGURATION_REVISION_MISMATCH",
                    "configurationRevision must be SHA-256 of the sorted unique compact JSON warehouse ID array");
        return new TransitCalculation(ids, revision);
    }

    public static String revision(List<Integer> canonicalIds) {
        String json = canonicalIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static TransitCapability capability(TransitCalculation config,
            List<ActiveGeneration> generations, List<Integer> analysisWarehouses, int schema) {
        return capability(config, generations, analysisWarehouses, List.of(), schema);
    }

    public static TransitCapability capability(TransitCalculation config,
            List<ActiveGeneration> generations, List<Integer> analysisWarehouses,
            List<ActiveGeneration> analysisGenerations, int schema) {
        Map<Integer, ActiveGeneration> byId = new HashMap<>();
        generations.forEach(g -> byId.put(g.warehouseId(), g));
        List<TransitSourceCapability> sources = config.warehouseIds().stream().map(id -> {
            ActiveGeneration g = byId.get(id);
            String reason = g == null ? "SNAPSHOT_NOT_READY"
                    : g.analyticsSchemaVersion() != schema ? "ANALYTICS_SCHEMA_TOO_OLD"
                    : g.asOfDate() == null || g.completedAt() == null ? "INCOMPLETE_SNAPSHOT_METADATA" : null;
            return new TransitSourceCapability(id, name(id, g), g == null ? null : g.id(),
                    g == null ? null : g.asOfDate(), g == null ? null : g.completedAt(),
                    g == null ? null : g.analyticsSchemaVersion(), reason == null, reason);
        }).toList();
        boolean overlap = config.warehouseIds().stream().anyMatch(analysisWarehouses::contains);
        boolean ready = !overlap && sources.stream().allMatch(TransitSourceCapability::ready);
        TransitSourceCapability single = sources.size() == 1 ? sources.get(0) : null;
        String reason = overlap ? "TRANSIT_SCOPE_OVERLAP" : ready ? null
                : single != null ? single.unavailableReason() : "INCOMPLETE_TRANSIT_SNAPSHOTS";
        Map<Integer, ActiveGeneration> salesById = new HashMap<>();
        analysisGenerations.forEach(g -> salesById.put(g.warehouseId(), g));
        List<TransitSourceCapability> salesSources = analysisWarehouses.stream().distinct().sorted().map(id -> {
            ActiveGeneration g = salesById.get(id);
            boolean valid = g != null && g.analyticsSchemaVersion() == schema
                    && g.asOfDate() != null && g.completedAt() != null;
            return new TransitSourceCapability(id, name(id, g), g == null ? null : g.id(),
                    g == null ? null : g.asOfDate(), g == null ? null : g.completedAt(),
                    g == null ? null : g.analyticsSchemaVersion(), valid, valid ? null : "SNAPSHOT_NOT_READY");
        }).toList();
        // Per-warehouse publication and repeatable-read in MariaDB do not prove a common Folio cut.
        // Neither identical dates nor a small timestamp skew exclude an intervening transfer.
        // No source-side cross-warehouse capture certificate is currently persisted.
        String consistencyStatus = sources.isEmpty() ? "DISABLED" : overlap ? "TRANSIT_SCOPE_OVERLAP"
                : !ready || salesSources.stream().anyMatch(s -> !s.ready()) ? "INCOMPLETE_NETWORK_SNAPSHOTS"
                : "NETWORK_SNAPSHOT_CONSISTENCY_UNCONFIRMED";
        NetworkSnapshotConsistency consistency = new NetworkSnapshotConsistency(consistencyStatus,
                sources.isEmpty(), salesSources, sources, sources.isEmpty() ? null
                : "Do not sum independent sales/transit snapshots for purchase planning. A verified common "
                  + "source cut or transfer reconciliation is required; refreshing sequentially is not proof.");
        return new TransitCapability(single == null ? null : single.warehouseId(),
                single == null ? null : single.warehouseName(), ready,
                single == null ? null : single.generationId(), reason, SUPPLIER_TYPES,
                "NETWORK_PHYSICAL_AVAILABLE_STOCK", true, VERSION, MAX_WAREHOUSES,
                !sources.isEmpty(), config.warehouseIds(), config.configurationRevision(), sources,
                consistency,
                overlap ? List.of("TRANSIT_ALREADY_INCLUDED_IN_ANALYSIS_STOCK") : List.of());
    }

    public static InTransitStock stock(TransitCapability capability,
            Map<Integer, Map<String, TransitRow>> rowsByWarehouse, String sku) {
        List<TransitSourceStock> sources = capability.sources().stream().map(source ->
                source(source, rowsByWarehouse.getOrDefault(source.warehouseId(), Map.of()).get(sku))).toList();
        BigDecimal known = sources.stream().map(TransitSourceStock::availableForPlanningQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean balancesReady = capability.ready()
                && sources.stream().allMatch(s -> s.availableForNetworkPlanningQuantity() != null);
        boolean ready = balancesReady && capability.networkSnapshotConsistency().confirmed();
        BigDecimal networkQuantity = ready ? sources.stream().map(TransitSourceStock::availableForNetworkPlanningQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        TransitSourceStock single = sources.size() == 1 ? sources.get(0) : null;
        String status = !capability.enabled() ? "DISABLED"
                : "TRANSIT_SCOPE_OVERLAP".equals(capability.unavailableReason()) ? "TRANSIT_SCOPE_OVERLAP"
                : !balancesReady ? single == null ? "INCOMPLETE_TRANSIT_DATA" : single.status()
                : !ready ? capability.networkSnapshotConsistency().status()
                : networkQuantity.signum() == 0 ? "NO_IN_TRANSIT_STOCK" : "AVAILABLE_NETWORK_STOCK";
        Boolean originConfirmed = capability.enabled() ? sources.stream()
                .allMatch(s -> Boolean.TRUE.equals(s.supplierOriginConfirmed())) : null;
        List<String> warnings = new ArrayList<>(capability.warnings());
        if (capability.enabled()) {
            warnings.add("DESTINATION_AND_ETA_NOT_CONFIRMED");
            warnings.add("OPEN_ORDER_DEDUPLICATION_REQUIRED");
        }
        if (!ready) warnings.add("TRANSIT_NOT_USABLE_FOR_PURCHASE_PLANNING");
        if (capability.enabled() && !capability.networkSnapshotConsistency().confirmed())
            warnings.add(capability.networkSnapshotConsistency().status());
        sources.stream().flatMap(s -> s.warnings().stream()).forEach(warnings::add);
        return new InTransitStock(single == null ? null : single.warehouseId(),
                single == null ? null : single.warehouseName(), single == null ? null : single.generationId(),
                status, originConfirmed,
                sum(sources, TransitSourceStock::physicalQuantity), sum(sources, TransitSourceStock::reservedQuantity),
                sum(sources, TransitSourceStock::availableQuantity), ready && (!capability.enabled() || Boolean.TRUE.equals(originConfirmed)) ? known : null,
                single == null ? null : single.openingQuantityAtHorizon(),
                single == null ? null : single.lastSupplierReceiptDate(), single == null ? List.of() : single.suppliers(),
                VERSION, capability.enabled(), ready, capability.warehouseIds(), capability.configurationRevision(),
                known, sources, networkQuantity,
                ready && (!capability.enabled() || Boolean.TRUE.equals(originConfirmed)) ? known : null,
                ready, status, capability.networkSnapshotConsistency(),
                warnings.stream().distinct().toList());
    }

    private static TransitSourceStock source(TransitSourceCapability cap, TransitRow row) {
        String status = cap.ready() ? row == null ? "SKU_NOT_PRESENT" : null : cap.unavailableReason();
        BigDecimal planning = null;
        BigDecimal networkPlanning = null;
        String originStatus = "SUPPLIER_ORIGIN_UNKNOWN";
        if (status == null) {
            if (row.physicalQuantity() == null || row.reservedQuantity() == null || row.availableQuantity() == null)
                status = "INCOMPLETE_TRANSIT_DATA";
            else if (row.physicalQuantity().signum() < 0 || row.reservedQuantity().signum() < 0
                    || row.availableQuantity().signum() < 0) status = "NEGATIVE_TRANSIT_STOCK";
            else if (row.physicalQuantity().subtract(row.reservedQuantity()).compareTo(row.availableQuantity()) != 0)
                status = "INCONSISTENT_TRANSIT_BALANCE";
            else {
                networkPlanning = row.physicalQuantity().subtract(row.reservedQuantity());
                status = networkPlanning.signum() == 0 ? "NO_AVAILABLE_TRANSIT_STOCK" : "AVAILABLE_PHYSICAL_STOCK";
                if (row.physicalQuantity().signum() == 0) { originStatus = "NO_IN_TRANSIT_STOCK"; planning = BigDecimal.ZERO; }
                else if (row.openingQuantity() == null) originStatus = "SUPPLIER_ORIGIN_UNKNOWN";
                else if (row.openingQuantity().signum() != 0) originStatus = "OPENING_BALANCE_UNATTRIBUTED";
                else if (row.inboundCount() == 0) originStatus = "NO_CONFIRMED_INBOUND";
                else if (row.supplierInboundCount() != row.inboundCount()) originStatus = "MIXED_ORIGIN";
                else { originStatus = "CONFIRMED_SUPPLIER_ORIGIN"; planning = row.availableQuantity(); }
            }
        }
        List<TransitSupplier> suppliers = row == null ? List.of() : row.suppliers().stream()
                .map(s -> new TransitSupplier(s.code(), s.name(), s.receiptQuantity(), s.lastReceiptDate())).toList();
        return new TransitSourceStock(cap.warehouseId(), cap.warehouseName(), cap.generationId(), status,
                planning != null, row == null ? null : row.physicalQuantity(), row == null ? null : row.reservedQuantity(),
                row == null ? null : row.availableQuantity(), planning, row == null ? null : row.openingQuantity(),
                row == null ? null : row.lastSupplierReceiptDate(), suppliers, cap.asOf(), cap.completedAt(),
                networkPlanning, planning, originStatus,
                networkPlanning == null ? List.of(status) : planning == null ? List.of(originStatus) : List.of());
    }

    private static BigDecimal sum(List<TransitSourceStock> sources, Function<TransitSourceStock, BigDecimal> field) {
        if (sources.isEmpty() || sources.stream().anyMatch(s -> field.apply(s) == null)) return null;
        return sources.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private static String name(int id, ActiveGeneration generation) {
        return generation == null || generation.warehouseName() == null || generation.warehouseName().isBlank()
                ? id == 9 ? "Транспорт" : "Warehouse " + id : generation.warehouseName();
    }
    private static FolioProductAnalyticsException invalid(String code, String message) {
        return new FolioProductAnalyticsException(code, HttpStatus.BAD_REQUEST, message);
    }
}
