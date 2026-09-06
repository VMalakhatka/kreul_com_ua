package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse.DictionaryItem;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.AvailabilityCalculation;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse.Availability;
import org.example.proect.lavka.service.folio.FolioAvailabilityOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class FolioProductAnalyticsDao {

    private static final Set<String> CURRENT_DICTIONARIES = Set.of(
            "groupLevel1", "groupLevel2", "groupLevel3", "groupLevel4",
            "groupLevel5", "groupLevel6", "departments", "productTypes",
            "units", "currentSuppliers", "supplierStates");
    private static final Set<String> MOVEMENT_DICTIONARIES = Set.of(
            "operationKinds", "movementClasses", "demandModes", "documentTypes",
            "stockDirections", "paymentTerms", "customerSegments",
            "counterparties", "organizationTypes");
    private static final Map<String, String> CURRENT_COLUMNS = Map.ofEntries(
            Map.entry("groupLevel1", "group_level_1_code"),
            Map.entry("groupLevel2", "group_level_2_code"),
            Map.entry("groupLevel3", "group_level_3_code"),
            Map.entry("groupLevel4", "group_level_4_code"),
            Map.entry("groupLevel5", "group_level_5_code"),
            Map.entry("groupLevel6", "group_level_6_code"),
            Map.entry("departments", "department_code"),
            Map.entry("productTypes", "product_type_code"),
            Map.entry("units", "unit_code"),
            Map.entry("currentSuppliers", "current_supplier"),
            Map.entry("supplierStates", "supplier_state"));
    private static final Map<String, String> CURRENT_NAME_COLUMNS = Map.ofEntries(
            Map.entry("groupLevel1", "group_level_1_name"),
            Map.entry("groupLevel2", "group_level_2_name"),
            Map.entry("groupLevel3", "group_level_3_name"),
            Map.entry("groupLevel4", "group_level_4_name"),
            Map.entry("groupLevel5", "group_level_5_name"),
            Map.entry("groupLevel6", "group_level_6_name"),
            Map.entry("departments", "department_name"),
            Map.entry("productTypes", "product_type_name"),
            Map.entry("units", "unit_name"),
            Map.entry("currentSuppliers", "current_supplier"),
            Map.entry("supplierStates", "supplier_state"));
    private static final Map<String, String> MOVEMENT_COLUMNS = Map.ofEntries(
            Map.entry("operationKinds", "operation_kind"),
            Map.entry("movementClasses", "movement_class"),
            Map.entry("demandModes", "demand_mode"),
            Map.entry("documentTypes", "document_type"),
            Map.entry("stockDirections", "stock_direction"),
            Map.entry("paymentTerms", "payment_terms"),
            Map.entry("customerSegments", "customer_segment"),
            Map.entry("counterparties", "counterparty_short_name"),
            Map.entry("organizationTypes", "organization_type"));

    private final NamedParameterJdbcTemplate named;

    public FolioProductAnalyticsDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<ActiveGeneration> activeGenerations(String sourceDatabase,
                                                     List<Integer> warehouseIds) {
        if (warehouseIds.isEmpty()) return List.of();
        return named.query("""
                SELECT id,source_database,warehouse_id,warehouse_name,horizon_months,
                       analytics_schema_version,as_of_date,completed_at,status
                  FROM folio_product_snapshot_generation
                 WHERE source_database=:db AND warehouse_id IN (:warehouseIds)
                   AND status='ACTIVE'
                 ORDER BY warehouse_id
                """, new MapSqlParameterSource("db", sourceDatabase)
                .addValue("warehouseIds", warehouseIds), (rs, rowNum) -> new ActiveGeneration(
                rs.getLong("id"), rs.getString("source_database"),
                rs.getInt("warehouse_id"), rs.getString("warehouse_name"),
                rs.getInt("horizon_months"), rs.getInt("analytics_schema_version"),
                rs.getObject("as_of_date", LocalDate.class),
                nullableDateTime(rs, "completed_at"), rs.getString("status")));
    }

    public Map<String, List<DictionaryItem>> dictionaries(String sourceDatabase,
                                                           List<Integer> warehouseIds) {
        Map<String, List<DictionaryItem>> result = new LinkedHashMap<>();
        for (String key : CURRENT_DICTIONARIES) {
            result.put(key, currentDictionary(sourceDatabase, warehouseIds, key));
        }
        result.put("productGroups", combinedGroups(sourceDatabase, warehouseIds));
        for (String key : MOVEMENT_DICTIONARIES) {
            result.put(key, movementDictionary(sourceDatabase, warehouseIds, key));
        }
        return Map.copyOf(result);
    }

    private List<DictionaryItem> currentDictionary(String db, List<Integer> warehouses,
                                                    String key) {
        String code = CURRENT_COLUMNS.get(key);
        String name = CURRENT_NAME_COLUMNS.get(key);
        if (code == null || name == null) throw new IllegalArgumentException("Unknown dictionary " + key);
        String sql = "SELECT " + code + " code,MAX(COALESCE(" + name + "," + code + ")) name,"
                + "COUNT(*) item_count FROM folio_product_metric_current "
                + "WHERE source_database=:db AND warehouse_id IN (:warehouseIds) "
                + "AND " + code + " IS NOT NULL AND " + code + "<>'' "
                + "GROUP BY " + code + " ORDER BY name,code";
        return dictionary(sql, db, warehouses);
    }

    private List<DictionaryItem> movementDictionary(String db, List<Integer> warehouses,
                                                     String key) {
        String code = MOVEMENT_COLUMNS.get(key);
        if (code == null) throw new IllegalArgumentException("Unknown dictionary " + key);
        String sql = "SELECT " + code + " code,MAX(" + code + ") name,COUNT(*) item_count "
                + "FROM folio_product_movement_fact WHERE source_database=:db "
                + "AND warehouse_id IN (:warehouseIds) AND " + code + " IS NOT NULL "
                + "AND " + code + "<>'' GROUP BY " + code + " ORDER BY name,code";
        return dictionary(sql, db, warehouses);
    }

    private List<DictionaryItem> combinedGroups(String db, List<Integer> warehouses) {
        StringBuilder union = new StringBuilder();
        for (int level = 1; level <= 6; level++) {
            if (level > 1) union.append(" UNION ALL ");
            union.append("SELECT group_level_").append(level)
                    .append("_code code,group_level_").append(level)
                    .append("_name name FROM folio_product_metric_current ")
                    .append("WHERE source_database=:db AND warehouse_id IN (:warehouseIds)");
        }
        String sql = "SELECT code,MAX(COALESCE(name,code)) name,COUNT(*) item_count FROM ("
                + union + ") groups_v3 WHERE code IS NOT NULL AND code<>'' "
                + "GROUP BY code ORDER BY name,code";
        return dictionary(sql, db, warehouses);
    }

    private List<DictionaryItem> dictionary(String sql, String db, List<Integer> warehouses) {
        return named.query(sql, new MapSqlParameterSource("db", db)
                        .addValue("warehouseIds", warehouses),
                (rs, rowNum) -> new DictionaryItem(rs.getString("code"),
                        rs.getString("name"), rs.getLong("item_count")));
    }

    public QueryResult query(QuerySpec spec) {
        SqlParts parts = sqlParts(spec);
        String base = productAggregateSql(parts);
        if (spec.availability() != null && (spec.availability().filter() != null
                || spec.sort().stream().anyMatch(s -> s.field().startsWith("availability") || s.field().equals("stockoutPercent")))) {
            String availability = FolioAvailabilitySql.summary(spec,
                    FolioAvailabilityOptions.context(spec.availability(), spec.warehouseIds()), null, parts.parameters);
            base = "SELECT p.*,v.availability_percent,v.stockout_percent,v.availability_status FROM ("
                    + base + ") p JOIN (" + availability + ") v ON v.sku=p.sku WHERE "
                    + availabilityPredicate(spec.availability(), parts.parameters);
        }
        TotalRow total = named.queryForObject("SELECT COUNT(*) product_count,"
                        + "COALESCE(SUM(warehouse_row_count),0) warehouse_row_count,"
                        + sums("q") + " FROM (" + base + ") q",
                parts.parameters, (rs, rowNum) -> total(rs));
        String order = sortSql(spec.sort());
        MapSqlParameterSource pageParams = copy(parts.parameters)
                .addValue("limit", spec.pageSize())
                .addValue("offset", spec.offset());
        List<AggregateRow> rows = named.query(base + order + " LIMIT :limit OFFSET :offset",
                pageParams, (rs, rowNum) -> aggregate(rs));
        List<String> pageSkus = rows.stream().map(AggregateRow::sku).toList();
        List<WarehouseRow> warehouses = pageSkus.isEmpty()
                ? List.of() : warehouseBreakdown(spec, parts, pageSkus);
        String basisColumn = switch (spec.abcBasis()) {
            case "REVENUE" -> "sales_revenue";
            case "SOLD_UNITS" -> "sold_units";
            default -> "gross_profit";
        };
        List<BasisRow> basis = named.query("SELECT sku," + basisColumn
                        + " basis_value FROM (" + base + ") abc_rows "
                        + "ORDER BY basis_value DESC,sku",
                parts.parameters, (rs, rowNum) -> new BasisRow(
                        rs.getString("sku"), decimal(rs, "basis_value")));
        return new QueryResult(total, List.copyOf(rows), List.copyOf(warehouses),
                List.copyOf(basis));
    }

    public List<String> existingSkus(String sourceDatabase, List<Integer> warehouseIds,
                                     List<String> requestedSkus) {
        if (requestedSkus == null || requestedSkus.isEmpty()) return List.of();
        return named.queryForList("""
                SELECT DISTINCT sku
                  FROM folio_product_metric_current
                 WHERE source_database=:db
                   AND warehouse_id IN (:warehouseIds)
                   AND sku IN (:skus)
                 ORDER BY sku
                """, new MapSqlParameterSource("db", sourceDatabase)
                .addValue("warehouseIds", warehouseIds)
                .addValue("skus", requestedSkus), String.class);
    }

    private static String availabilityPredicate(AvailabilityCalculation options, MapSqlParameterSource params) {
        var filter = options.filter();
        if (filter == null) return "1=1";
        List<String> clauses = new ArrayList<>();
        BigDecimal[] values = {filter.availabilityPercentFrom(), filter.availabilityPercentTo(),
                filter.stockoutPercentFrom(), filter.stockoutPercentTo()};
        String[] comparisons = {"v.availability_percent>=", "v.availability_percent<=",
                "v.stockout_percent>=", "v.stockout_percent<="};
        for (int i=0; i<values.length; i++) if (values[i] != null) {
            params.addValue("avLimit" + i, values[i]); clauses.add(comparisons[i] + ":avLimit" + i);
        }
        if (filter.availabilityStatus() != null && !filter.availabilityStatus().isEmpty()) {
            params.addValue("avStatuses", filter.availabilityStatus()); clauses.add("v.availability_status IN (:avStatuses)");
        }
        return clauses.isEmpty() ? "1=1" : String.join(" AND ", clauses);
    }

    public Map<String, Availability> availability(QuerySpec spec, List<Integer> members, List<String> skus) {
        if (skus.isEmpty() || members.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = FolioAvailabilitySql.summary(spec, members, skus, params);
        Map<String, Availability> result = new LinkedHashMap<>();
        named.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String status = rs.getString("availability_status");
            boolean measured = "MEASURED".equals(status);
            boolean excluded = "NOT_APPLICABLE".equals(status);
            long days = rs.getLong("period_days");
            long available = rs.getLong("available_days");
            List<String> warnings = new ArrayList<>();
            warnings.add("CURRENT_POLICY_APPLIED_TO_PERIOD");
            if (rs.getBoolean("negative_stock")) warnings.add("NEGATIVE_PHYSICAL_STOCK");
            if ("DATA_INCOMPLETE".equals(status)) warnings.add("INCOMPLETE_DAILY_HISTORY");
            result.put(rs.getString("sku"), new Availability(status, FolioAvailabilityOptions.BASIS,
                    measured ? Boolean.TRUE : excluded ? Boolean.FALSE : null,
                    members.size() == 1 ? rs.getBigDecimal("minimum_stock") : null,
                    days, measured ? Long.valueOf(days) : excluded ? Long.valueOf(0) : null,
                    measured ? available : null, measured ? days-available : null,
                    rs.getBigDecimal("availability_percent"), rs.getBigDecimal("stockout_percent"),
                    List.copyOf(warnings)));
        });
        return Map.copyOf(result);
    }

    public List<String> existingBarcodes(String sourceDatabase, List<Integer> warehouseIds,
                                         List<String> requestedBarcodes) {
        if (requestedBarcodes == null || requestedBarcodes.isEmpty()) return List.of();
        return named.queryForList("""
                SELECT DISTINCT primary_barcode
                  FROM folio_product_metric_current
                 WHERE source_database=:db
                   AND warehouse_id IN (:warehouseIds)
                   AND primary_barcode IN (:barcodes)
                 ORDER BY primary_barcode
                """, new MapSqlParameterSource("db", sourceDatabase)
                .addValue("warehouseIds", warehouseIds)
                .addValue("barcodes", requestedBarcodes), String.class);
    }

    public Map<String, TransitRow> transitRows(
            String sourceDatabase, int warehouseId, long generationId,
            List<String> skus, List<String> supplierOrganizationTypes) {
        if (skus == null || skus.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource("db", sourceDatabase)
                .addValue("warehouseId", warehouseId)
                .addValue("generationId", generationId)
                .addValue("skus", skus)
                .addValue("supplierTypes", supplierOrganizationTypes);
        Map<String, MutableTransit> result = new LinkedHashMap<>();
        named.query("""
                SELECT c.sku,c.physical_quantity,c.reserved_quantity,c.available_quantity,
                       c.physical_quantity-COALESCE(SUM(CASE WHEN m.affects_stock=1
                           THEN m.signed_quantity ELSE 0 END),0) opening_quantity,
                       COALESCE(SUM(CASE WHEN m.affects_stock=1 AND m.signed_quantity>0
                           THEN 1 ELSE 0 END),0) inbound_count,
                       COALESCE(SUM(CASE WHEN m.affects_stock=1 AND m.signed_quantity>0
                            AND m.movement_class='PURCHASE_RECEIPT'
                            AND m.organization_type IN (:supplierTypes)
                            AND NULLIF(TRIM(m.counterparty_short_name),'') IS NOT NULL
                           THEN 1 ELSE 0 END),0) supplier_inbound_count,
                       MAX(CASE WHEN m.affects_stock=1 AND m.signed_quantity>0
                            AND m.movement_class='PURCHASE_RECEIPT'
                            AND m.organization_type IN (:supplierTypes)
                            AND NULLIF(TRIM(m.counterparty_short_name),'') IS NOT NULL
                           THEN m.document_date ELSE NULL END) last_supplier_receipt_date
                  FROM folio_product_metric_current c
                  LEFT JOIN folio_product_movement_fact m
                    ON m.source_database=c.source_database
                   AND m.warehouse_id=c.warehouse_id
                   AND m.sku=c.sku
                   AND m.generation_id=:generationId
                 WHERE c.source_database=:db AND c.warehouse_id=:warehouseId
                   AND c.generation_id=:generationId
                   AND c.sku IN (:skus)
                 GROUP BY c.sku,c.physical_quantity,c.reserved_quantity,c.available_quantity
                """, params, rs -> {
            String sku = rs.getString("sku");
            result.put(sku, new MutableTransit(
                    sku, decimal(rs, "physical_quantity"),
                    decimal(rs, "reserved_quantity"), decimal(rs, "available_quantity"),
                    decimal(rs, "opening_quantity"), rs.getLong("inbound_count"),
                    rs.getLong("supplier_inbound_count"),
                    rs.getObject("last_supplier_receipt_date", LocalDate.class),
                    new ArrayList<>()));
        });
        named.query("""
                SELECT sku,counterparty_short_name,MAX(counterparty_name) counterparty_name,
                       SUM(quantity) receipt_quantity,MAX(document_date) last_receipt_date
                  FROM folio_product_movement_fact
                 WHERE source_database=:db AND warehouse_id=:warehouseId
                   AND generation_id=:generationId AND sku IN (:skus)
                   AND affects_stock=1 AND signed_quantity>0
                   AND movement_class='PURCHASE_RECEIPT'
                   AND organization_type IN (:supplierTypes)
                   AND NULLIF(TRIM(counterparty_short_name),'') IS NOT NULL
                 GROUP BY sku,counterparty_short_name
                 ORDER BY sku,counterparty_short_name
                """, params, rs -> {
            MutableTransit transit = result.get(rs.getString("sku"));
            if (transit != null) {
                transit.suppliers.add(new TransitSupplierRow(
                        rs.getString("counterparty_short_name"),
                        rs.getString("counterparty_name"),
                        decimal(rs, "receipt_quantity"),
                        rs.getObject("last_receipt_date", LocalDate.class)));
            }
        });
        Map<String, TransitRow> immutable = new LinkedHashMap<>();
        result.forEach((sku, value) -> immutable.put(sku, new TransitRow(
                value.sku, value.physicalQuantity, value.reservedQuantity,
                value.availableQuantity, value.openingQuantity, value.inboundCount,
                value.supplierInboundCount, value.lastSupplierReceiptDate,
                List.copyOf(value.suppliers))));
        return Map.copyOf(immutable);
    }

    public Map<String, NetworkPolicyRow> networkPolicies(
            String sourceDatabase, int warehouseId, List<String> skus) {
        if (skus == null || skus.isEmpty()) return Map.of();
        Map<String, NetworkPolicyRow> result = new HashMap<>();
        named.query("""
                SELECT sku,minimum_stock,maximum_stock
                  FROM folio_product_metric_current
                 WHERE source_database=:db
                   AND warehouse_id=:warehouseId
                   AND sku IN (:skus)
                """, new MapSqlParameterSource("db", sourceDatabase)
                .addValue("warehouseId", warehouseId)
                .addValue("skus", skus), rs -> {
            NetworkPolicyRow row = new NetworkPolicyRow(
                    rs.getString("sku"), rs.getBigDecimal("minimum_stock"),
                    rs.getBigDecimal("maximum_stock"));
            result.put(row.sku(), row);
        });
        return Map.copyOf(result);
    }

    private List<WarehouseRow> warehouseBreakdown(QuerySpec spec, SqlParts parts,
                                                   List<String> pageSkus) {
        MapSqlParameterSource params = copy(parts.parameters).addValue("pageSkus", pageSkus);
        String sql = warehouseAggregateSql(parts)
                + " AND c.sku IN (:pageSkus) GROUP BY c.warehouse_id,c.sku "
                + "ORDER BY c.sku,c.warehouse_id";
        return named.query(sql, params, (rs, rowNum) -> warehouse(rs));
    }

    private static String productAggregateSql(SqlParts parts) {
        return "SELECT c.sku,MIN(c.product_name) product_name,"
                + "MIN(c.current_supplier) current_suppliers,"
                + dimensions("c") + ",COUNT(*) warehouse_row_count,"
                + currentSums("c") + "," + flowSums("f") + ","
                + "SUM(COALESCE(i.average_inventory_value,c.inventory_value)) average_inventory_value "
                + "FROM folio_product_metric_current c "
                + "LEFT JOIN (" + parts.flowSql + ") f ON f.source_database=c.source_database "
                + "AND f.warehouse_id=c.warehouse_id AND f.sku=c.sku "
                + "LEFT JOIN (" + parts.inventorySql + ") i ON i.source_database=c.source_database "
                + "AND i.warehouse_id=c.warehouse_id AND i.sku=c.sku "
                + "WHERE " + parts.currentWhere + " GROUP BY c.sku";
    }

    private static String warehouseAggregateSql(SqlParts parts) {
        return "SELECT c.warehouse_id,c.sku,MIN(c.product_name) product_name,"
                + "MIN(c.current_supplier) current_supplier,MIN(c.supplier_state) supplier_state,"
                + "MAX(c.minimum_stock) minimum_stock,MAX(c.maximum_stock) maximum_stock,"
                + "1 warehouse_row_count," + currentSums("c") + "," + flowSums("f") + ","
                + "SUM(COALESCE(i.average_inventory_value,c.inventory_value)) average_inventory_value "
                + "FROM folio_product_metric_current c "
                + "LEFT JOIN (" + parts.flowSql + ") f ON f.source_database=c.source_database "
                + "AND f.warehouse_id=c.warehouse_id AND f.sku=c.sku "
                + "LEFT JOIN (" + parts.inventorySql + ") i ON i.source_database=c.source_database "
                + "AND i.warehouse_id=c.warehouse_id AND i.sku=c.sku "
                + "WHERE " + parts.currentWhere;
    }

    private static String dimensions(String alias) {
        List<String> values = new ArrayList<>();
        for (int level = 1; level <= 6; level++) {
            values.add("MIN(" + alias + ".group_level_" + level + "_code) group_level_" + level + "_code");
            values.add("MIN(" + alias + ".group_level_" + level + "_name) group_level_" + level + "_name");
        }
        values.add("MIN(" + alias + ".department_code) department_code");
        values.add("MIN(" + alias + ".department_name) department_name");
        values.add("MIN(" + alias + ".product_type_code) product_type_code");
        values.add("MIN(" + alias + ".product_type_name) product_type_name");
        values.add("MIN(" + alias + ".unit_code) unit_code");
        values.add("MIN(" + alias + ".unit_name) unit_name");
        values.add("MAX(" + alias + ".package_quantity) package_quantity");
        values.add("MAX(" + alias + ".minimum_order_quantity) minimum_order_quantity");
        values.add("SUM(COALESCE(" + alias + ".minimum_stock,0)) minimum_stock");
        values.add("SUM(COALESCE(" + alias + ".maximum_stock,0)) maximum_stock");
        values.add("MIN(" + alias + ".primary_barcode) primary_barcode");
        values.add("MIN(" + alias + ".brand_code) brand_code");
        values.add("MIN(" + alias + ".brand_name) brand_name");
        return String.join(",", values);
    }

    private static String currentSums(String alias) {
        return "SUM(" + alias + ".physical_quantity) physical_quantity,"
                + "SUM(" + alias + ".reserved_quantity) reserved_quantity,"
                + "SUM(" + alias + ".available_quantity) available_quantity,"
                + "SUM(" + alias + ".inventory_value) inventory_value";
    }

    private static String flowSums(String alias) {
        return "SUM(COALESCE(" + alias + ".sold_units,0)) sold_units,"
                + "SUM(COALESCE(" + alias + ".sales_revenue,0)) sales_revenue,"
                + "SUM(COALESCE(" + alias + ".sales_cogs,0)) sales_cogs,"
                + "SUM(COALESCE(" + alias + ".sales_revenue,0)-COALESCE(" + alias + ".sales_cogs,0)) gross_profit,"
                + "SUM(COALESCE(" + alias + ".return_quantity,0)) return_quantity,"
                + "SUM(COALESCE(" + alias + ".return_revenue,0)) return_revenue,"
                + "SUM(COALESCE(" + alias + ".regular_sold_units,0)) regular_sold_units,"
                + "SUM(COALESCE(" + alias + ".regular_revenue,0)) regular_revenue,"
                + "SUM(COALESCE(" + alias + ".regular_cogs,0)) regular_cogs,"
                + "SUM(COALESCE(" + alias + ".one_off_sold_units,0)) one_off_sold_units,"
                + "SUM(COALESCE(" + alias + ".one_off_revenue,0)) one_off_revenue,"
                + "SUM(COALESCE(" + alias + ".one_off_cogs,0)) one_off_cogs";
    }

    private static String sums(String alias) {
        return "COALESCE(SUM(" + alias + ".physical_quantity),0) physical_quantity,"
                + "COALESCE(SUM(" + alias + ".reserved_quantity),0) reserved_quantity,"
                + "COALESCE(SUM(" + alias + ".available_quantity),0) available_quantity,"
                + "COALESCE(SUM(" + alias + ".inventory_value),0) inventory_value,"
                + "COALESCE(SUM(" + alias + ".sold_units),0) sold_units,"
                + "COALESCE(SUM(" + alias + ".sales_revenue),0) sales_revenue,"
                + "COALESCE(SUM(" + alias + ".sales_cogs),0) sales_cogs,"
                + "COALESCE(SUM(" + alias + ".gross_profit),0) gross_profit,"
                + "COALESCE(SUM(" + alias + ".return_quantity),0) return_quantity,"
                + "COALESCE(SUM(" + alias + ".return_revenue),0) return_revenue,"
                + "COALESCE(SUM(" + alias + ".regular_sold_units),0) regular_sold_units,"
                + "COALESCE(SUM(" + alias + ".regular_revenue),0) regular_revenue,"
                + "COALESCE(SUM(" + alias + ".regular_cogs),0) regular_cogs,"
                + "COALESCE(SUM(" + alias + ".one_off_sold_units),0) one_off_sold_units,"
                + "COALESCE(SUM(" + alias + ".one_off_revenue),0) one_off_revenue,"
                + "COALESCE(SUM(" + alias + ".one_off_cogs),0) one_off_cogs,"
                + "COALESCE(SUM(" + alias + ".average_inventory_value),0) average_inventory_value";
    }

    private static SqlParts sqlParts(QuerySpec spec) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("db", spec.sourceDatabase())
                .addValue("warehouseIds", spec.warehouseIds())
                .addValue("periodFrom", spec.periodFrom())
                .addValue("periodToExclusive", spec.periodTo().plusDays(1))
                .addValue("monthFrom", spec.periodFrom().withDayOfMonth(1))
                .addValue("monthTo", spec.periodTo().withDayOfMonth(1));
        List<String> current = new ArrayList<>(List.of(
                "c.source_database=:db", "c.warehouse_id IN (:warehouseIds)"));
        List<String> movement = new ArrayList<>(List.of(
                "m.source_database=:db", "m.warehouse_id IN (:warehouseIds)",
                "m.document_date>=:periodFrom", "m.document_date<:periodToExclusive"));

        addSelection(current, params, spec.productSelections().get("skus"),
                "skus", List.of("c.sku"));
        if (spec.search() != null) {
            params.addValue("productSearch", "%" + escapeLike(spec.search()) + "%");
            current.add("(c.sku LIKE :productSearch ESCAPE '\\\\' "
                    + "OR c.product_name LIKE :productSearch ESCAPE '\\\\' "
                    + "OR c.primary_barcode LIKE :productSearch ESCAPE '\\\\')");
        }

        addSelection(current, params, spec.productSelections().get("groups"), "productGroups",
                List.of("c.group_level_1_code", "c.group_level_2_code", "c.group_level_3_code",
                        "c.group_level_4_code", "c.group_level_5_code", "c.group_level_6_code"));
        for (int level = 1; level <= 6; level++) {
            addSelection(current, params, spec.productSelections().get("groupLevel" + level),
                    "groupLevel" + level, List.of("c.group_level_" + level + "_code"));
        }
        addSelection(current, params, spec.productSelections().get("departments"),
                "departments", List.of("c.department_code"));
        addSelection(current, params, spec.productSelections().get("productTypes"),
                "productTypes", List.of("c.product_type_code"));
        addSelection(current, params, spec.productSelections().get("units"),
                "units", List.of("c.unit_code"));
        addSelection(current, params, spec.productSelections().get("currentSuppliers"),
                "currentSuppliers", List.of("c.current_supplier"));
        addSelection(current, params, spec.productSelections().get("supplierStates"),
                "supplierStates", List.of("c.supplier_state"));
        addSelection(current, params, spec.productSelections().get("barcodes"),
                "barcodes", List.of("c.primary_barcode"));

        for (Map.Entry<String, Selection> entry : spec.movementSelections().entrySet()) {
            String column = MOVEMENT_COLUMNS.get(entry.getKey());
            if (column != null) addSelection(movement, params, entry.getValue(),
                    entry.getKey(), List.of("m." + column));
        }

        String flow = "SELECT m.source_database,m.warehouse_id,m.sku,"
                + "SUM(CASE WHEN m.affects_financial_sales=1 THEN m.quantity ELSE 0 END) sold_units,"
                + "SUM(CASE WHEN m.affects_financial_sales=1 THEN m.sale_amount ELSE 0 END) sales_revenue,"
                + "SUM(CASE WHEN m.affects_financial_sales=1 THEN m.accounting_value ELSE 0 END) sales_cogs,"
                + "SUM(CASE WHEN m.movement_class='CUSTOMER_RETURN' THEN m.quantity ELSE 0 END) return_quantity,"
                + "SUM(CASE WHEN m.movement_class='CUSTOMER_RETURN' THEN m.sale_amount ELSE 0 END) return_revenue,"
                + "SUM(CASE WHEN m.affects_planning_demand=1 THEN m.quantity ELSE 0 END) regular_sold_units,"
                + "SUM(CASE WHEN m.affects_planning_demand=1 THEN m.sale_amount ELSE 0 END) regular_revenue,"
                + "SUM(CASE WHEN m.affects_planning_demand=1 THEN m.accounting_value ELSE 0 END) regular_cogs,"
                + "SUM(CASE WHEN m.demand_mode='ONE_OFF_ORDER' AND m.affects_financial_sales=1 THEN m.quantity ELSE 0 END) one_off_sold_units,"
                + "SUM(CASE WHEN m.demand_mode='ONE_OFF_ORDER' AND m.affects_financial_sales=1 THEN m.sale_amount ELSE 0 END) one_off_revenue,"
                + "SUM(CASE WHEN m.demand_mode='ONE_OFF_ORDER' AND m.affects_financial_sales=1 THEN m.accounting_value ELSE 0 END) one_off_cogs "
                + "FROM folio_product_movement_fact m WHERE " + String.join(" AND ", movement)
                + " GROUP BY m.source_database,m.warehouse_id,m.sku";
        String inventory = "SELECT source_database,warehouse_id,sku,"
                + "AVG(average_inventory_value) average_inventory_value "
                + "FROM folio_product_metric_monthly WHERE source_database=:db "
                + "AND warehouse_id IN (:warehouseIds) AND month_start>=:monthFrom "
                + "AND month_start<=:monthTo GROUP BY source_database,warehouse_id,sku";
        return new SqlParts(String.join(" AND ", current), flow, inventory, params);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void addSelection(List<String> predicates, MapSqlParameterSource params,
                                     Selection selection, String parameter,
                                     List<String> columns) {
        if (selection == null || "ANY".equals(selection.mode()) || selection.values().isEmpty()) return;
        params.addValue(parameter, selection.values());
        String joined = columns.stream()
                .map(column -> "COALESCE(" + column + ",'') IN (:" + parameter + ")")
                .reduce((a, b) -> a + " OR " + b).orElse("1=0");
        predicates.add(("EXCLUDE".equals(selection.mode()) ? "NOT (" : "(") + joined + ")");
    }

    private static String sortSql(List<SortSpec> sort) {
        Map<String, String> columns = Map.ofEntries(
                Map.entry("sku", "sku"), Map.entry("productName", "product_name"),
                Map.entry("physicalQuantity", "physical_quantity"),
                Map.entry("inventoryValue", "inventory_value"),
                Map.entry("soldUnits", "sold_units"), Map.entry("salesRevenue", "sales_revenue"),
                Map.entry("salesCogs", "sales_cogs"), Map.entry("grossProfit", "gross_profit"),
                Map.entry("averageInventoryValue", "average_inventory_value"),
                Map.entry("availabilityPercent", "availability_percent"),
                Map.entry("stockoutPercent", "stockout_percent"),
                Map.entry("availabilityStatus", "availability_status"));
        List<String> clauses = new ArrayList<>();
        for (SortSpec item : sort) {
            String column = columns.get(item.field());
            if (column != null) {
                if (item.field().equals("availabilityPercent") || item.field().equals("stockoutPercent"))
                    clauses.add(column + " IS NULL ASC");
                clauses.add(column + ("DESC".equals(item.direction()) ? " DESC" : " ASC"));
            }
        }
        if (clauses.isEmpty()) clauses.add("gross_profit DESC");
        clauses.add("sku ASC");
        return " ORDER BY " + String.join(",", clauses);
    }

    private static MapSqlParameterSource copy(MapSqlParameterSource source) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (String name : source.getValues().keySet()) result.addValue(name, source.getValue(name));
        return result;
    }

    private static TotalRow total(ResultSet rs) throws SQLException {
        return new TotalRow(rs.getLong("product_count"), rs.getLong("warehouse_row_count"),
                metrics(rs));
    }

    private static AggregateRow aggregate(ResultSet rs) throws SQLException {
        return new AggregateRow(rs.getString("sku"), rs.getString("product_name"),
                rs.getString("current_suppliers"), dimensions(rs), metrics(rs));
    }

    private static WarehouseRow warehouse(ResultSet rs) throws SQLException {
        return new WarehouseRow(rs.getInt("warehouse_id"), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("current_supplier"),
                rs.getString("supplier_state"), rs.getBigDecimal("minimum_stock"),
                rs.getBigDecimal("maximum_stock"), metrics(rs));
    }

    private static DimensionRow dimensions(ResultSet rs) throws SQLException {
        return new DimensionRow(
                rs.getString("group_level_1_code"), rs.getString("group_level_1_name"),
                rs.getString("group_level_2_code"), rs.getString("group_level_2_name"),
                rs.getString("group_level_3_code"), rs.getString("group_level_3_name"),
                rs.getString("group_level_4_code"), rs.getString("group_level_4_name"),
                rs.getString("group_level_5_code"), rs.getString("group_level_5_name"),
                rs.getString("group_level_6_code"), rs.getString("group_level_6_name"),
                rs.getString("department_code"), rs.getString("department_name"),
                rs.getString("product_type_code"), rs.getString("product_type_name"),
                rs.getString("unit_code"), rs.getString("unit_name"),
                rs.getBigDecimal("package_quantity"), rs.getBigDecimal("minimum_order_quantity"),
                rs.getBigDecimal("minimum_stock"), rs.getBigDecimal("maximum_stock"),
                rs.getString("primary_barcode"),
                rs.getString("brand_code"), rs.getString("brand_name"));
    }

    private static MetricRow metrics(ResultSet rs) throws SQLException {
        return new MetricRow(decimal(rs, "physical_quantity"), decimal(rs, "reserved_quantity"),
                decimal(rs, "available_quantity"), decimal(rs, "inventory_value"),
                decimal(rs, "sold_units"), decimal(rs, "sales_revenue"),
                decimal(rs, "sales_cogs"), decimal(rs, "gross_profit"),
                decimal(rs, "return_quantity"), decimal(rs, "return_revenue"),
                decimal(rs, "regular_sold_units"), decimal(rs, "regular_revenue"),
                decimal(rs, "regular_cogs"), decimal(rs, "one_off_sold_units"),
                decimal(rs, "one_off_revenue"), decimal(rs, "one_off_cogs"),
                decimal(rs, "average_inventory_value"));
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static LocalDateTime nullableDateTime(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record ActiveGeneration(long id, String sourceDatabase, int warehouseId,
                                   String warehouseName, int horizonMonths,
                                   int analyticsSchemaVersion, LocalDate asOfDate,
                                   LocalDateTime completedAt, String status) { }
    public record Selection(String mode, List<String> values) { }
    public record SortSpec(String field, String direction) { }
    public record QuerySpec(String sourceDatabase, List<Integer> warehouseIds,
                            LocalDate periodFrom, LocalDate periodTo,
                            String search,
                            Map<String, Selection> productSelections,
                            Map<String, Selection> movementSelections,
                            int pageSize, int offset, List<SortSpec> sort,
                            String abcBasis, AvailabilityCalculation availability) {
        public QuerySpec(String db, List<Integer> warehouses, LocalDate from, LocalDate to,
                         String search, Map<String, Selection> product, Map<String, Selection> movement,
                         int size, int offset, List<SortSpec> sort, String basis) {
            this(db, warehouses, from, to, search, product, movement, size, offset, sort, basis, null);
        }
    }
    public record QueryResult(TotalRow total, List<AggregateRow> rows,
                              List<WarehouseRow> warehouseRows,
                              List<BasisRow> basisRows) { }
    public record BasisRow(String sku, BigDecimal value) { }
    public record TotalRow(long productCount, long warehouseRowCount, MetricRow metrics) { }
    public record AggregateRow(String sku, String productName, String currentSuppliers,
                               DimensionRow dimensions, MetricRow metrics) { }
    public record WarehouseRow(int warehouseId, String sku, String productName,
                               String currentSupplier, String supplierState,
                               BigDecimal minimumStock, BigDecimal maximumStock,
                               MetricRow metrics) {
        public WarehouseRow(int warehouseId, String sku, String productName,
                            String currentSupplier, String supplierState,
                            MetricRow metrics) {
            this(warehouseId, sku, productName, currentSupplier, supplierState,
                    null, null, metrics);
        }
    }
    public record NetworkPolicyRow(String sku, BigDecimal minimumStock,
                                   BigDecimal maximumStock) { }
    public record TransitSupplierRow(String code, String name,
                                     BigDecimal receiptQuantity,
                                     LocalDate lastReceiptDate) { }
    public record TransitRow(String sku, BigDecimal physicalQuantity,
                             BigDecimal reservedQuantity, BigDecimal availableQuantity,
                             BigDecimal openingQuantity, long inboundCount,
                             long supplierInboundCount, LocalDate lastSupplierReceiptDate,
                             List<TransitSupplierRow> suppliers) { }
    public record DimensionRow(
            String groupLevel1Code, String groupLevel1Name,
            String groupLevel2Code, String groupLevel2Name,
            String groupLevel3Code, String groupLevel3Name,
            String groupLevel4Code, String groupLevel4Name,
            String groupLevel5Code, String groupLevel5Name,
            String groupLevel6Code, String groupLevel6Name,
            String departmentCode, String departmentName,
            String productTypeCode, String productTypeName,
            String unitCode, String unitName,
            BigDecimal packageQuantity, BigDecimal minimumOrderQuantity,
            BigDecimal minimumStock, BigDecimal maximumStock,
            String primaryBarcode,
            String brandCode, String brandName) { }
    public record MetricRow(
            BigDecimal physicalQuantity, BigDecimal reservedQuantity,
            BigDecimal availableQuantity, BigDecimal inventoryValue,
            BigDecimal soldUnits, BigDecimal salesRevenue, BigDecimal salesCogs,
            BigDecimal grossProfit, BigDecimal returnQuantity, BigDecimal returnRevenue,
            BigDecimal regularSoldUnits, BigDecimal regularRevenue, BigDecimal regularCogs,
            BigDecimal oneOffSoldUnits, BigDecimal oneOffRevenue, BigDecimal oneOffCogs,
            BigDecimal averageInventoryValue) { }
    private record SqlParts(String currentWhere, String flowSql, String inventorySql,
                            MapSqlParameterSource parameters) { }
    private static final class MutableTransit {
        private final String sku;
        private final BigDecimal physicalQuantity;
        private final BigDecimal reservedQuantity;
        private final BigDecimal availableQuantity;
        private final BigDecimal openingQuantity;
        private final long inboundCount;
        private final long supplierInboundCount;
        private final LocalDate lastSupplierReceiptDate;
        private final List<TransitSupplierRow> suppliers;

        private MutableTransit(String sku, BigDecimal physicalQuantity,
                               BigDecimal reservedQuantity, BigDecimal availableQuantity,
                               BigDecimal openingQuantity, long inboundCount,
                               long supplierInboundCount, LocalDate lastSupplierReceiptDate,
                               List<TransitSupplierRow> suppliers) {
            this.sku = sku;
            this.physicalQuantity = physicalQuantity;
            this.reservedQuantity = reservedQuantity;
            this.availableQuantity = availableQuantity;
            this.openingQuantity = openingQuantity;
            this.inboundCount = inboundCount;
            this.supplierInboundCount = supplierInboundCount;
            this.lastSupplierReceiptDate = lastSupplierReceiptDate;
            this.suppliers = suppliers;
        }
    }
}
