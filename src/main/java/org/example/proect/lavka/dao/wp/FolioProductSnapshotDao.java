package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MovementFact;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;
import org.example.proect.lavka.service.folio.FolioProductEconomicsCalculator.Alert;
import org.example.proect.lavka.service.folio.FolioProductEconomicsCalculator.CurrentMetric;
import org.example.proect.lavka.service.folio.FolioProductEconomicsCalculator.MonthlyMetric;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class FolioProductSnapshotDao {

    public static final int ANALYTICS_SCHEMA_VERSION = 4;
    private static final int BATCH = 300;
    private final JdbcTemplate jdbc;

    public FolioProductSnapshotDao(@Qualifier("wpJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquireLease(String scopeKey, String ownerId, int leaseSeconds) {
        jdbc.update("""
                INSERT INTO folio_product_snapshot_lock
                    (scope_key, owner_id, locked_until, updated_at)
                VALUES (?, NULL, NULL, NOW(3))
                ON DUPLICATE KEY UPDATE scope_key=VALUES(scope_key)
                """, scopeKey);
        return jdbc.update("""
                UPDATE folio_product_snapshot_lock
                   SET owner_id=?, locked_until=DATE_ADD(NOW(3), INTERVAL ? SECOND),
                       updated_at=NOW(3)
                 WHERE scope_key=?
                   AND (owner_id=? OR locked_until IS NULL OR locked_until<NOW(3))
                """, ownerId, leaseSeconds, scopeKey, ownerId) == 1;
    }

    public void releaseLease(String scopeKey, String ownerId) {
        jdbc.update("""
                UPDATE folio_product_snapshot_lock
                   SET owner_id=NULL, locked_until=NULL, updated_at=NOW(3)
                 WHERE scope_key=? AND owner_id=?
                """, scopeKey, ownerId);
    }

    public boolean renewLease(String scopeKey, String ownerId, int leaseSeconds) {
        return jdbc.update("""
                UPDATE folio_product_snapshot_lock
                   SET locked_until=DATE_ADD(NOW(3), INTERVAL ? SECOND),updated_at=NOW(3)
                 WHERE scope_key=? AND owner_id=? AND locked_until>=NOW(3)
                """, leaseSeconds, scopeKey, ownerId) == 1;
    }

    public void heartbeatGeneration(long generationId, LocalDateTime at) {
        if (jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET last_heartbeat_at=?
                 WHERE id=? AND status='BUILDING'
                """, ts(at), generationId) != 1) {
            throw new IllegalStateException("Product snapshot generation is no longer BUILDING");
        }
    }

    public int failAbandonedGenerations(String sourceDatabase, int warehouseId,
                                        LocalDateTime at) {
        return jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET status='FAILED',completed_at=?,last_heartbeat_at=?,
                       error_message='Snapshot process stopped before completion'
                 WHERE source_database=? AND warehouse_id=? AND status='BUILDING'
                """, ts(at), ts(at), sourceDatabase, warehouseId);
    }

    public void discardStagingForScope(String sourceDatabase, int warehouseId) {
        jdbc.update("""
                DELETE FROM folio_product_movement_fact_stage
                 WHERE source_database=? AND warehouse_id=?
                """, sourceDatabase, warehouseId);
        jdbc.update("""
                DELETE FROM folio_product_metric_monthly_stage
                 WHERE source_database=? AND warehouse_id=?
                """, sourceDatabase, warehouseId);
        jdbc.update("""
                DELETE FROM folio_product_metric_current_stage
                 WHERE source_database=? AND warehouse_id=?
                """, sourceDatabase, warehouseId);
        jdbc.update("""
                DELETE FROM folio_product_metric_alert_stage
                 WHERE source_database=? AND warehouse_id=?
                """, sourceDatabase, warehouseId);
    }

    public long createGeneration(String sourceDatabase, int warehouseId,
                                 int horizonMonths, String trigger,
                                 LocalDateTime startedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO folio_product_snapshot_generation
                        (source_database,warehouse_id,horizon_months,analytics_schema_version,
                         status,trigger_source,
                         started_at,last_heartbeat_at)
                    VALUES (?,?,?,?,'BUILDING',?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceDatabase);
            ps.setInt(2, warehouseId);
            ps.setInt(3, horizonMonths);
            ps.setInt(4, ANALYTICS_SCHEMA_VERSION);
            ps.setString(5, trigger);
            ps.setTimestamp(6, Timestamp.valueOf(startedAt));
            ps.setTimestamp(7, Timestamp.valueOf(startedAt));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Cannot read product snapshot generation id");
        return key.longValue();
    }

    public Map<String, ExistingItem> findExisting(String sourceDatabase, int warehouseId) {
        Map<String, ExistingItem> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sku,product_name,current_supplier,supplier_state,
                       observed_digest,applied_digest,verification_state,
                       present_in_folio,movement_count,min_movement_recno,max_movement_recno,
                       first_movement_date,last_movement_date,price_rule_count,
                       first_seen_at,applied_at,last_error
                  FROM folio_product_snapshot_item
                 WHERE source_database=? AND warehouse_id=?
                 ORDER BY sku
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString("sku"), new ExistingItem(
                rs.getString("sku"), rs.getString("product_name"),
                rs.getString("current_supplier"), rs.getString("supplier_state"),
                rs.getString("observed_digest"),
                rs.getString("applied_digest"), rs.getString("verification_state"),
                rs.getBoolean("present_in_folio"),
                rs.getLong("movement_count"),
                nullableLong(rs, "min_movement_recno"),
                nullableLong(rs, "max_movement_recno"),
                rs.getObject("first_movement_date", LocalDate.class),
                rs.getObject("last_movement_date", LocalDate.class),
                rs.getInt("price_rule_count"),
                rs.getTimestamp("first_seen_at").toLocalDateTime(),
                rs.getTimestamp("applied_at") == null
                        ? null : rs.getTimestamp("applied_at").toLocalDateTime(),
                rs.getString("last_error")
        )), sourceDatabase, warehouseId);
        return result;
    }

    /**
     * Records a fingerprint captured inside the successful MSSQL
     * recalculation transaction. The current observation is not rewritten:
     * the next warehouse snapshot independently observes the source and turns
     * the row VERIFIED when both digests match.
     */
    public int confirmApplied(String sourceDatabase, int warehouseId, String sku,
                              String appliedDigest, LocalDateTime appliedAt) {
        return jdbc.update("""
                UPDATE folio_product_snapshot_item
                   SET applied_digest=?, applied_at=?, last_error=NULL,
                       verification_state=CASE
                           WHEN observed_digest=? THEN 'VERIFIED'
                           ELSE verification_state
                       END
                 WHERE source_database=? AND warehouse_id=? AND sku=?
                   AND present_in_folio=1
                """, appliedDigest, ts(appliedAt), appliedDigest,
                sourceDatabase, warehouseId, sku);
    }

    @Transactional(transactionManager = "wpTransactionManager")
    public int[] confirmAppliedBatch(List<ProductFingerprint> fingerprints,
                                     LocalDateTime appliedAt) {
        if (fingerprints.isEmpty()) return new int[0];
        int[][] batches = jdbc.batchUpdate("""
                UPDATE folio_product_snapshot_item
                   SET applied_digest=?, applied_at=?, last_error=NULL,
                       verification_state=CASE
                           WHEN observed_digest=? THEN 'VERIFIED'
                           ELSE verification_state
                       END
                 WHERE source_database=? AND warehouse_id=? AND sku=?
                   AND present_in_folio=1
                """, fingerprints, BATCH, (ps, fingerprint) -> {
            ps.setString(1, fingerprint.sourceDigest());
            ps.setTimestamp(2, ts(appliedAt));
            ps.setString(3, fingerprint.sourceDigest());
            ps.setString(4, fingerprint.sourceDatabase());
            ps.setInt(5, fingerprint.warehouseId());
            ps.setString(6, fingerprint.sku());
        });
        return java.util.Arrays.stream(batches)
                .flatMapToInt(java.util.Arrays::stream)
                .toArray();
    }

    public int markRecalculationFailed(String sourceDatabase, int warehouseId,
                                       String sku, String error) {
        return jdbc.update("""
                UPDATE folio_product_snapshot_item
                   SET verification_state='FAILED', last_error=?
                 WHERE source_database=? AND warehouse_id=? AND sku=?
                   AND present_in_folio=1
                   AND (applied_digest IS NULL OR observed_digest IS NULL
                        OR applied_digest<>observed_digest)
                """, truncate(error, 1000), sourceDatabase, warehouseId, sku);
    }

    @Transactional(transactionManager = "wpTransactionManager")
    public void publish(Publish publish) {
        saveItems(publish.items());
        saveChanges(publish.changes());
        jdbc.update("""
                DELETE FROM folio_product_movement_fact
                 WHERE source_database=? AND warehouse_id=?
                """, publish.sourceDatabase(), publish.warehouseId());
        jdbc.update("""
                INSERT INTO folio_product_movement_fact
                SELECT * FROM folio_product_movement_fact_stage
                 WHERE generation_id=?
                """, publish.generationId());
        jdbc.update("""
                DELETE FROM folio_product_metric_monthly
                 WHERE source_database=? AND warehouse_id=?
                """, publish.sourceDatabase(), publish.warehouseId());
        jdbc.update("""
                INSERT INTO folio_product_metric_monthly
                SELECT * FROM folio_product_metric_monthly_stage
                 WHERE generation_id=?
                """, publish.generationId());
        jdbc.update("""
                DELETE FROM folio_product_metric_current
                 WHERE source_database=? AND warehouse_id=?
                """, publish.sourceDatabase(), publish.warehouseId());
        jdbc.update("""
                INSERT INTO folio_product_metric_current
                SELECT * FROM folio_product_metric_current_stage
                 WHERE generation_id=?
                """, publish.generationId());
        publishStagedAlerts(publish);

        int updated = jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET status='ACTIVE',analytics_schema_version=?,as_of_date=?,
                       warehouse_name=?,
                       completed_at=?,last_heartbeat_at=?,
                       total_products=?,movement_rows=?,movement_fact_rows=?,monthly_metric_rows=?,
                       unverified_products=?,dirty_products=?,new_products=?,
                       removed_products=?,warehouse_digest=?,error_message=NULL
                 WHERE id=? AND status='BUILDING'
                """, ANALYTICS_SCHEMA_VERSION, publish.asOfDate(), publish.warehouseName(),
                ts(publish.calculatedAt()),
                ts(publish.calculatedAt()),
                publish.items().stream().filter(Item::present).count(),
                publish.movementRows(), publish.movementFactRows(),
                publish.monthlyMetricRows(),
                publish.unverified(), publish.dirty(), publish.created(),
                publish.removed(), publish.warehouseDigest(), publish.generationId());
        if (updated != 1) throw new IllegalStateException("Product snapshot generation is not publishable");
        jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET status='SUPERSEDED'
                 WHERE source_database=? AND warehouse_id=? AND status='ACTIVE' AND id<>?
                """, publish.sourceDatabase(), publish.warehouseId(), publish.generationId());
        discardStaging(publish.generationId());
    }

    private void publishStagedAlerts(Publish publish) {
        jdbc.update("""
                UPDATE folio_product_metric_alert
                   SET status='RESOLVED',resolved_at=?,last_seen_at=?,generation_id=?
                 WHERE source_database=? AND warehouse_id=? AND status='ACTIVE'
                """, ts(publish.calculatedAt()), ts(publish.calculatedAt()),
                publish.generationId(), publish.sourceDatabase(), publish.warehouseId());
        jdbc.update("""
                INSERT INTO folio_product_metric_alert
                    (source_database,warehouse_id,sku,alert_code,status,severity,
                     first_seen_at,last_seen_at,resolved_at,details,generation_id)
                SELECT source_database,warehouse_id,sku,alert_code,status,severity,
                       first_seen_at,last_seen_at,resolved_at,details,generation_id
                  FROM folio_product_metric_alert_stage
                 WHERE generation_id=?
                ON DUPLICATE KEY UPDATE status='ACTIVE',severity=VALUES(severity),
                    last_seen_at=VALUES(last_seen_at),resolved_at=NULL,
                    details=VALUES(details),generation_id=VALUES(generation_id)
                """, publish.generationId());
    }

    private void saveItems(List<Item> rows) {
        jdbc.batchUpdate("""
                INSERT INTO folio_product_snapshot_item
                    (source_database,warehouse_id,sku,product_name,current_supplier,
                     supplier_state,observed_digest,
                     applied_digest,verification_state,present_in_folio,movement_count,
                     min_movement_recno,max_movement_recno,first_movement_date,
                     last_movement_date,price_rule_count,first_seen_at,last_seen_at,
                     last_observed_at,applied_at,last_generation_id,last_error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    product_name=VALUES(product_name),
                    current_supplier=VALUES(current_supplier),
                    supplier_state=VALUES(supplier_state),
                    observed_digest=VALUES(observed_digest),
                    verification_state=CASE
                        WHEN folio_product_snapshot_item.applied_at IS NOT NULL
                         AND (VALUES(applied_at) IS NULL
                              OR folio_product_snapshot_item.applied_at>VALUES(applied_at))
                         AND folio_product_snapshot_item.applied_digest=VALUES(observed_digest)
                        THEN 'VERIFIED'
                        ELSE VALUES(verification_state)
                    END,
                    applied_digest=CASE
                        WHEN folio_product_snapshot_item.applied_at IS NOT NULL
                         AND (VALUES(applied_at) IS NULL
                              OR folio_product_snapshot_item.applied_at>VALUES(applied_at))
                        THEN folio_product_snapshot_item.applied_digest
                        ELSE VALUES(applied_digest)
                    END,
                    present_in_folio=VALUES(present_in_folio),movement_count=VALUES(movement_count),
                    min_movement_recno=VALUES(min_movement_recno),
                    max_movement_recno=VALUES(max_movement_recno),
                    first_movement_date=VALUES(first_movement_date),
                    last_movement_date=VALUES(last_movement_date),
                    price_rule_count=VALUES(price_rule_count),last_seen_at=VALUES(last_seen_at),
                    last_observed_at=VALUES(last_observed_at),
                    last_error=CASE
                        WHEN folio_product_snapshot_item.applied_at IS NOT NULL
                         AND (VALUES(applied_at) IS NULL
                              OR folio_product_snapshot_item.applied_at>VALUES(applied_at))
                        THEN NULL
                        ELSE VALUES(last_error)
                    END,
                    applied_at=CASE
                        WHEN folio_product_snapshot_item.applied_at IS NOT NULL
                         AND (VALUES(applied_at) IS NULL
                              OR folio_product_snapshot_item.applied_at>VALUES(applied_at))
                        THEN folio_product_snapshot_item.applied_at
                        ELSE VALUES(applied_at)
                    END,
                    last_generation_id=VALUES(last_generation_id)
                """, rows, BATCH, (ps, row) -> {
            int p = 1;
            ps.setString(p++, row.sourceDatabase()); ps.setInt(p++, row.warehouseId());
            ps.setString(p++, row.sku()); ps.setString(p++, row.productName());
            ps.setString(p++, row.currentSupplier()); ps.setString(p++, row.supplierState());
            ps.setString(p++, row.observedDigest()); ps.setString(p++, row.appliedDigest());
            ps.setString(p++, row.state()); ps.setBoolean(p++, row.present());
            ps.setLong(p++, row.movementCount()); nullableLong(ps, p++, row.minRecno());
            nullableLong(ps, p++, row.maxRecno()); nullableDate(ps, p++, row.firstMovementDate());
            nullableDate(ps, p++, row.lastMovementDate()); ps.setInt(p++, row.priceRuleCount());
            ps.setTimestamp(p++, ts(row.firstSeenAt())); ps.setTimestamp(p++, ts(row.lastSeenAt()));
            ps.setTimestamp(p++, ts(row.observedAt())); nullableTimestamp(ps, p++, row.appliedAt());
            ps.setLong(p++, row.generationId()); ps.setString(p, row.lastError());
        });
    }

    private void saveChanges(List<Change> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO folio_product_snapshot_change
                    (generation_id,source_database,warehouse_id,sku,change_type,
                     before_digest,after_digest,detected_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, rows, BATCH, (ps, row) -> {
            ps.setLong(1,row.generationId()); ps.setString(2,row.sourceDatabase());
            ps.setInt(3,row.warehouseId()); ps.setString(4,row.sku());
            ps.setString(5,row.type()); ps.setString(6,row.beforeDigest());
            ps.setString(7,row.afterDigest()); ps.setTimestamp(8,ts(row.detectedAt()));
        });
    }

    public void stageMovements(long generationId, String db, int warehouseId,
                               LocalDateTime at, List<MovementFact> rows) {
        saveMovements("folio_product_movement_fact_stage", generationId, db,
                warehouseId, at, rows);
    }

    private void saveMovements(String table, long generationId, String db, int warehouseId,
                               LocalDateTime at, List<MovementFact> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO %s
                    (source_database,warehouse_id,movement_recno,generation_id,
                     document_id,document_number,document_date,sku,quantity,signed_quantity,
                     sale_amount,accounting_value,signed_accounting_value,movement_type,
                     document_type,operation_kind,accounted,return_flag,movement_class,
                     stock_direction,demand_mode,payment_terms,customer_segment,
                     counterparty_short_name,counterparty_name,organization_type,
                     current_supplier,supplier_state,affects_stock,affects_financial_sales,
                     affects_planning_demand,captured_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.formatted(table), rows, BATCH, (ps, row) -> {
            int p = 1;
            ps.setString(p++, db); ps.setInt(p++, warehouseId);
            ps.setLong(p++, row.movementRecno()); ps.setLong(p++, generationId);
            nullableLong(ps, p++, row.documentId());
            nullableDecimal(ps, p++, row.documentNumber(), 4);
            nullableDate(ps, p++, row.documentDate()); ps.setString(p++, row.sku());
            decimal(ps, p++, row.quantity(), 4); decimal(ps, p++, row.signedQuantity(), 4);
            decimal(ps, p++, row.saleAmount(), 4); decimal(ps, p++, row.accountingValue(), 4);
            decimal(ps, p++, row.signedAccountingValue(), 4);
            ps.setString(p++, row.movementType()); ps.setString(p++, row.documentType());
            ps.setString(p++, row.operationKind()); ps.setBoolean(p++, row.accounted());
            ps.setBoolean(p++, row.returnFlag()); ps.setString(p++, row.movementClass());
            ps.setString(p++, row.stockDirection()); ps.setString(p++, row.demandMode());
            ps.setString(p++, row.paymentTerms()); ps.setString(p++, row.customerSegment());
            ps.setString(p++, row.counterpartyShortName());
            ps.setString(p++, row.counterpartyName());
            ps.setString(p++, row.organizationType()); ps.setString(p++, row.currentSupplier());
            ps.setString(p++, row.supplierState()); ps.setBoolean(p++, row.affectsStock());
            ps.setBoolean(p++, row.affectsFinancialSales());
            ps.setBoolean(p++, row.affectsPlanningDemand()); ps.setTimestamp(p, ts(at));
        });
    }

    public void stageMonthly(long generationId, String db, int warehouseId,
                             LocalDateTime at, List<MonthlyMetric> rows) {
        saveMonthly("folio_product_metric_monthly_stage", generationId, db,
                warehouseId, at, rows);
    }

    private void saveMonthly(String table, long generationId, String db, int warehouseId,
                             LocalDateTime at, List<MonthlyMetric> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO %s
                    (source_database,warehouse_id,sku,month_start,opening_quantity,
                     closing_quantity,opening_inventory_value,closing_inventory_value,
                     receipt_quantity,receipt_cost,sales_quantity,sales_revenue,sales_cogs,
                     gross_profit,regular_sales_quantity,regular_sales_revenue,
                     regular_sales_cogs,regular_gross_profit,one_off_sales_quantity,
                     one_off_sales_revenue,one_off_sales_cogs,one_off_gross_profit,
                     return_quantity,return_revenue,average_inventory_value,
                     inventory_turns,gmroi,sell_through_percent,generation_id,calculated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.formatted(table), rows, BATCH, (ps, row) -> {
            int p=1; ps.setString(p++,db); ps.setInt(p++,warehouseId); ps.setString(p++,row.sku());
            ps.setObject(p++,row.monthStart()); decimal(ps,p++,row.openingQuantity(),4);
            decimal(ps,p++,row.closingQuantity(),4); decimal(ps,p++,row.openingInventoryValue(),4);
            decimal(ps,p++,row.closingInventoryValue(),4); decimal(ps,p++,row.receiptQuantity(),4);
            decimal(ps,p++,row.receiptCost(),4); decimal(ps,p++,row.salesQuantity(),4);
            decimal(ps,p++,row.salesRevenue(),4); decimal(ps,p++,row.salesCogs(),4);
            decimal(ps,p++,row.grossProfit(),4);
            decimal(ps,p++,row.regularSalesQuantity(),4);
            decimal(ps,p++,row.regularSalesRevenue(),4);
            decimal(ps,p++,row.regularSalesCogs(),4);
            decimal(ps,p++,row.regularGrossProfit(),4);
            decimal(ps,p++,row.oneOffSalesQuantity(),4);
            decimal(ps,p++,row.oneOffSalesRevenue(),4);
            decimal(ps,p++,row.oneOffSalesCogs(),4);
            decimal(ps,p++,row.oneOffGrossProfit(),4);
            decimal(ps,p++,row.returnQuantity(),4);
            decimal(ps,p++,row.returnRevenue(),4); decimal(ps,p++,row.averageInventoryValue(),4);
            nullableDecimal(ps,p++,row.inventoryTurns(),6); nullableDecimal(ps,p++,row.gmroi(),6);
            nullableDecimal(ps,p++,row.sellThroughPercent(),4); ps.setLong(p++,generationId);
            ps.setTimestamp(p,ts(at));
        });
    }

    public void stageCurrent(long generationId, String db, int warehouseId,
                             LocalDateTime at, List<CurrentMetric> rows) {
        saveCurrent("folio_product_metric_current_stage", generationId, db,
                warehouseId, at, rows);
    }

    private void saveCurrent(String table, long generationId, String db, int warehouseId,
                             LocalDateTime at, List<CurrentMetric> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO %s
                    (source_database,warehouse_id,sku,product_name,current_supplier,
                     supplier_state,analytics_digest,
                     group_level_1_code,group_level_1_name,
                     group_level_2_code,group_level_2_name,
                     group_level_3_code,group_level_3_name,
                     group_level_4_code,group_level_4_name,
                     group_level_5_code,group_level_5_name,
                     group_level_6_code,group_level_6_name,
                     department_code,department_name,product_type_code,product_type_name,
                     unit_code,unit_name,package_quantity,minimum_order_quantity,
                     minimum_stock,maximum_stock,primary_barcode,brand_code,brand_name,
                     physical_quantity,reserved_quantity,available_quantity,
                     accounting_price,inventory_value,last_receipt_date,last_sale_date,
                     last_regular_sale_date,sold_units_30d,sold_units_90d,sold_units_365d,
                     sold_units_730d,regular_sold_units_30d,regular_sold_units_90d,
                     regular_sold_units_365d,regular_sold_units_730d,one_off_sold_units_30d,
                     one_off_sold_units_90d,one_off_sold_units_365d,one_off_sold_units_730d,
                     revenue_90d,revenue_365d,regular_revenue_90d,regular_revenue_365d,
                     one_off_revenue_90d,one_off_revenue_365d,gross_profit_90d,
                     gross_profit_365d,regular_gross_profit_90d,regular_gross_profit_365d,
                     one_off_gross_profit_90d,one_off_gross_profit_365d,
                     average_inventory_90d,average_inventory_365d,inventory_turns_365d,
                     gmroi_365d,coverage_days,health_status,generation_id,calculated_at)
                VALUES (%s)
                ON DUPLICATE KEY UPDATE product_name=VALUES(product_name),
                    current_supplier=VALUES(current_supplier),supplier_state=VALUES(supplier_state),
                    analytics_digest=VALUES(analytics_digest),
                    group_level_1_code=VALUES(group_level_1_code),
                    group_level_1_name=VALUES(group_level_1_name),
                    group_level_2_code=VALUES(group_level_2_code),
                    group_level_2_name=VALUES(group_level_2_name),
                    group_level_3_code=VALUES(group_level_3_code),
                    group_level_3_name=VALUES(group_level_3_name),
                    group_level_4_code=VALUES(group_level_4_code),
                    group_level_4_name=VALUES(group_level_4_name),
                    group_level_5_code=VALUES(group_level_5_code),
                    group_level_5_name=VALUES(group_level_5_name),
                    group_level_6_code=VALUES(group_level_6_code),
                    group_level_6_name=VALUES(group_level_6_name),
                    department_code=VALUES(department_code),
                    department_name=VALUES(department_name),
                    product_type_code=VALUES(product_type_code),
                    product_type_name=VALUES(product_type_name),
                    unit_code=VALUES(unit_code),unit_name=VALUES(unit_name),
                    package_quantity=VALUES(package_quantity),
                    minimum_order_quantity=VALUES(minimum_order_quantity),
                    minimum_stock=VALUES(minimum_stock),maximum_stock=VALUES(maximum_stock),
                    primary_barcode=VALUES(primary_barcode),
                    brand_code=VALUES(brand_code),brand_name=VALUES(brand_name),
                    physical_quantity=VALUES(physical_quantity),reserved_quantity=VALUES(reserved_quantity),
                    available_quantity=VALUES(available_quantity),accounting_price=VALUES(accounting_price),
                    inventory_value=VALUES(inventory_value),last_receipt_date=VALUES(last_receipt_date),
                    last_sale_date=VALUES(last_sale_date),
                    last_regular_sale_date=VALUES(last_regular_sale_date),
                    sold_units_30d=VALUES(sold_units_30d),
                    sold_units_90d=VALUES(sold_units_90d),sold_units_365d=VALUES(sold_units_365d),
                    sold_units_730d=VALUES(sold_units_730d),
                    regular_sold_units_30d=VALUES(regular_sold_units_30d),
                    regular_sold_units_90d=VALUES(regular_sold_units_90d),
                    regular_sold_units_365d=VALUES(regular_sold_units_365d),
                    regular_sold_units_730d=VALUES(regular_sold_units_730d),
                    one_off_sold_units_30d=VALUES(one_off_sold_units_30d),
                    one_off_sold_units_90d=VALUES(one_off_sold_units_90d),
                    one_off_sold_units_365d=VALUES(one_off_sold_units_365d),
                    one_off_sold_units_730d=VALUES(one_off_sold_units_730d),
                    revenue_90d=VALUES(revenue_90d),
                    revenue_365d=VALUES(revenue_365d),gross_profit_90d=VALUES(gross_profit_90d),
                    gross_profit_365d=VALUES(gross_profit_365d),
                    regular_revenue_90d=VALUES(regular_revenue_90d),
                    regular_revenue_365d=VALUES(regular_revenue_365d),
                    one_off_revenue_90d=VALUES(one_off_revenue_90d),
                    one_off_revenue_365d=VALUES(one_off_revenue_365d),
                    regular_gross_profit_90d=VALUES(regular_gross_profit_90d),
                    regular_gross_profit_365d=VALUES(regular_gross_profit_365d),
                    one_off_gross_profit_90d=VALUES(one_off_gross_profit_90d),
                    one_off_gross_profit_365d=VALUES(one_off_gross_profit_365d),
                    average_inventory_90d=VALUES(average_inventory_90d),
                    average_inventory_365d=VALUES(average_inventory_365d),
                    inventory_turns_365d=VALUES(inventory_turns_365d),gmroi_365d=VALUES(gmroi_365d),
                    coverage_days=VALUES(coverage_days),health_status=VALUES(health_status),
                    generation_id=VALUES(generation_id),calculated_at=VALUES(calculated_at)
                """.formatted(table, placeholders(72)), rows, BATCH, (ps,row)->{
            int p=1; ps.setString(p++,db); ps.setInt(p++,warehouseId); ps.setString(p++,row.sku());
            ps.setString(p++,row.productName()); ps.setString(p++,row.currentSupplier());
            ps.setString(p++,row.supplierState());
            var dimensions = row.dimensions();
            ps.setString(p++, dimensions.analyticsDigest());
            ps.setString(p++, dimensions.groupLevel1Code());
            ps.setString(p++, dimensions.groupLevel1Name());
            ps.setString(p++, dimensions.groupLevel2Code());
            ps.setString(p++, dimensions.groupLevel2Name());
            ps.setString(p++, dimensions.groupLevel3Code());
            ps.setString(p++, dimensions.groupLevel3Name());
            ps.setString(p++, dimensions.groupLevel4Code());
            ps.setString(p++, dimensions.groupLevel4Name());
            ps.setString(p++, dimensions.groupLevel5Code());
            ps.setString(p++, dimensions.groupLevel5Name());
            ps.setString(p++, dimensions.groupLevel6Code());
            ps.setString(p++, dimensions.groupLevel6Name());
            ps.setString(p++, dimensions.departmentCode());
            ps.setString(p++, dimensions.departmentName());
            ps.setString(p++, dimensions.productTypeCode());
            ps.setString(p++, dimensions.productTypeName());
            ps.setString(p++, dimensions.unitCode());
            ps.setString(p++, dimensions.unitName());
            nullableDecimal(ps,p++,dimensions.packageQuantity(),4);
            nullableDecimal(ps,p++,dimensions.minimumOrderQuantity(),4);
            nullableDecimal(ps,p++,dimensions.minimumStock(),4);
            nullableDecimal(ps,p++,dimensions.maximumStock(),4);
            ps.setString(p++, dimensions.primaryBarcode());
            ps.setString(p++, dimensions.brandCode());
            ps.setString(p++, dimensions.brandName());
            decimal(ps,p++,row.physicalQuantity(),4);
            decimal(ps,p++,row.reservedQuantity(),4); decimal(ps,p++,row.availableQuantity(),4);
            decimal(ps,p++,row.accountingPrice(),6); decimal(ps,p++,row.inventoryValue(),4);
            nullableDate(ps,p++,row.lastReceiptDate()); nullableDate(ps,p++,row.lastSaleDate());
            nullableDate(ps,p++,row.lastRegularSaleDate());
            decimal(ps,p++,row.soldUnits30d(),4); decimal(ps,p++,row.soldUnits90d(),4);
            decimal(ps,p++,row.soldUnits365d(),4); decimal(ps,p++,row.soldUnits730d(),4);
            decimal(ps,p++,row.regularSoldUnits30d(),4);
            decimal(ps,p++,row.regularSoldUnits90d(),4);
            decimal(ps,p++,row.regularSoldUnits365d(),4);
            decimal(ps,p++,row.regularSoldUnits730d(),4);
            decimal(ps,p++,row.oneOffSoldUnits30d(),4);
            decimal(ps,p++,row.oneOffSoldUnits90d(),4);
            decimal(ps,p++,row.oneOffSoldUnits365d(),4);
            decimal(ps,p++,row.oneOffSoldUnits730d(),4);
            decimal(ps,p++,row.revenue90d(),4); decimal(ps,p++,row.revenue365d(),4);
            decimal(ps,p++,row.regularRevenue90d(),4);
            decimal(ps,p++,row.regularRevenue365d(),4);
            decimal(ps,p++,row.oneOffRevenue90d(),4);
            decimal(ps,p++,row.oneOffRevenue365d(),4);
            decimal(ps,p++,row.grossProfit90d(),4); decimal(ps,p++,row.grossProfit365d(),4);
            decimal(ps,p++,row.regularGrossProfit90d(),4);
            decimal(ps,p++,row.regularGrossProfit365d(),4);
            decimal(ps,p++,row.oneOffGrossProfit90d(),4);
            decimal(ps,p++,row.oneOffGrossProfit365d(),4);
            decimal(ps,p++,row.averageInventory90d(),4); decimal(ps,p++,row.averageInventory365d(),4);
            nullableDecimal(ps,p++,row.inventoryTurns365d(),6); nullableDecimal(ps,p++,row.gmroi365d(),6);
            nullableDecimal(ps,p++,row.coverageDays(),2); ps.setString(p++,row.healthStatus());
            ps.setLong(p++,generationId); ps.setTimestamp(p,ts(at));
        });
    }

    public void stageAlerts(long generationId, String db, int warehouseId,
                            LocalDateTime at, List<Alert> rows) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO folio_product_metric_alert_stage
                    (source_database,warehouse_id,sku,alert_code,status,severity,
                     first_seen_at,last_seen_at,resolved_at,details,generation_id)
                VALUES (?,?,?,?,'ACTIVE',?,?,?,NULL,?,?)
                ON DUPLICATE KEY UPDATE status='ACTIVE',severity=VALUES(severity),
                    last_seen_at=VALUES(last_seen_at),resolved_at=NULL,
                    details=VALUES(details),generation_id=VALUES(generation_id)
                """, rows, BATCH, (ps,row)->{
            ps.setString(1,db); ps.setInt(2,warehouseId); ps.setString(3,row.sku());
            ps.setString(4,row.code()); ps.setString(5,row.severity());
            ps.setTimestamp(6,ts(at)); ps.setTimestamp(7,ts(at));
            ps.setString(8,row.details()); ps.setLong(9,generationId);
        });
    }

    public void failGeneration(long generationId, String error, LocalDateTime at) {
        jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET status='FAILED',completed_at=?,last_heartbeat_at=?,error_message=?
                 WHERE id=? AND status='BUILDING'
                """, ts(at),ts(at),truncate(error,1000),generationId);
    }

    public void discardStaging(long generationId) {
        jdbc.update("DELETE FROM folio_product_movement_fact_stage WHERE generation_id=?",
                generationId);
        jdbc.update("DELETE FROM folio_product_metric_monthly_stage WHERE generation_id=?",
                generationId);
        jdbc.update("DELETE FROM folio_product_metric_current_stage WHERE generation_id=?",
                generationId);
        jdbc.update("DELETE FROM folio_product_metric_alert_stage WHERE generation_id=?",
                generationId);
    }

    public Optional<Generation> latest() {
        List<Generation> rows=jdbc.query("""
                SELECT * FROM folio_product_snapshot_generation ORDER BY id DESC LIMIT 1
                """,(rs,n)->new Generation(rs.getLong("id"),rs.getString("source_database"),
                rs.getInt("warehouse_id"),rs.getString("warehouse_name"),
                rs.getInt("horizon_months"),
                rs.getInt("analytics_schema_version"),
                rs.getObject("as_of_date", LocalDate.class), rs.getString("status"),
                rs.getString("trigger_source"),rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getInt("total_products"),rs.getLong("movement_rows"),
                rs.getLong("movement_fact_rows"),
                rs.getInt("monthly_metric_rows"),rs.getInt("unverified_products"),
                rs.getInt("dirty_products"),rs.getInt("new_products"),rs.getInt("removed_products"),
                rs.getString("warehouse_digest"),rs.getString("error_message")));
        return rows.stream().findFirst();
    }

    private static Timestamp ts(LocalDateTime value){return Timestamp.valueOf(value);}
    private static void nullableLong(PreparedStatement ps,int p,Long v)throws java.sql.SQLException{
        if(v==null)ps.setNull(p,Types.BIGINT);else ps.setLong(p,v);
    }
    private static Long nullableLong(java.sql.ResultSet rs,String column)throws java.sql.SQLException{
        long value=rs.getLong(column); return rs.wasNull()?null:value;
    }
    private static void nullableDate(PreparedStatement ps,int p,LocalDate v)throws java.sql.SQLException{
        if(v==null)ps.setNull(p,Types.DATE);else ps.setObject(p,v);
    }
    private static void nullableTimestamp(PreparedStatement ps,int p,LocalDateTime v)throws java.sql.SQLException{
        if(v==null)ps.setNull(p,Types.TIMESTAMP);else ps.setTimestamp(p,ts(v));
    }
    private static void decimal(PreparedStatement ps,int p,BigDecimal v,int scale)
            throws java.sql.SQLException {
        ps.setBigDecimal(p, scaled(v, scale));
    }
    private static void nullableDecimal(PreparedStatement ps,int p,BigDecimal v,int scale)
            throws java.sql.SQLException {
        if(v==null)ps.setNull(p,Types.DECIMAL);else ps.setBigDecimal(p,scaled(v,scale));
    }
    static BigDecimal scaled(BigDecimal value,int scale){
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
    private static String truncate(String value,int max){
        if(value==null)return null; return value.length()<=max?value:value.substring(0,max);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    public record ExistingItem(String sku,String productName,
                               String currentSupplier,String supplierState,
                               String observedDigest,
                               String appliedDigest,String state,boolean present,
                               long movementCount,Long minRecno,Long maxRecno,
                               LocalDate firstMovementDate,LocalDate lastMovementDate,
                               int priceRuleCount,LocalDateTime firstSeenAt,
                               LocalDateTime appliedAt,String lastError){ }
    public record Item(String sourceDatabase,int warehouseId,String sku,String productName,
                       String currentSupplier,String supplierState,
                       String observedDigest,String appliedDigest,String state,boolean present,
                       long movementCount,Long minRecno,Long maxRecno,LocalDate firstMovementDate,
                       LocalDate lastMovementDate,int priceRuleCount,LocalDateTime firstSeenAt,
                       LocalDateTime lastSeenAt,LocalDateTime observedAt,LocalDateTime appliedAt,
                       long generationId,String lastError){ }
    public record Change(long generationId,String sourceDatabase,int warehouseId,String sku,
                         String type,String beforeDigest,String afterDigest,LocalDateTime detectedAt){ }
    public record Publish(long generationId,String sourceDatabase,int warehouseId,
                          String warehouseName,
                          String warehouseDigest,long movementRows,List<Item> items,
                          List<Change> changes,long movementFactRows,
                          int monthlyMetricRows,
                          int unverified,int dirty,int created,int removed,
                          LocalDate asOfDate,LocalDateTime calculatedAt){ }
    public record Generation(long id,String sourceDatabase,int warehouseId,String warehouseName,
                             int horizonMonths,
                             int analyticsSchemaVersion,
                             LocalDate asOfDate,
                             String status,String trigger,LocalDateTime startedAt,
                             LocalDateTime completedAt,int totalProducts,long movementRows,
                             long movementFactRows,int monthlyMetricRows,
                             int unverified,int dirty,int created,int removed,
                             String warehouseDigest,String error){
        public Generation(long id,String sourceDatabase,int warehouseId,int horizonMonths,
                          int analyticsSchemaVersion,String status,String trigger,
                          LocalDateTime startedAt,LocalDateTime completedAt,int totalProducts,
                          long movementRows,long movementFactRows,int monthlyMetricRows,
                          int unverified,int dirty,int created,int removed,
                          String warehouseDigest,String error) {
            this(id,sourceDatabase,warehouseId,null,horizonMonths,analyticsSchemaVersion,
                    null,status,trigger,startedAt,completedAt,totalProducts,movementRows,
                    movementFactRows,monthlyMetricRows,unverified,dirty,created,removed,
                    warehouseDigest,error);
        }
    }
}
