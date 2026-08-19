package org.example.proect.lavka.dao.wp;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class FolioProductSnapshotDao {

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

    public long createGeneration(String sourceDatabase, int warehouseId,
                                 int horizonMonths, String trigger,
                                 LocalDateTime startedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO folio_product_snapshot_generation
                        (source_database,warehouse_id,horizon_months,status,trigger_source,
                         started_at,last_heartbeat_at)
                    VALUES (?,?,?,'BUILDING',?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceDatabase);
            ps.setInt(2, warehouseId);
            ps.setInt(3, horizonMonths);
            ps.setString(4, trigger);
            ps.setTimestamp(5, Timestamp.valueOf(startedAt));
            ps.setTimestamp(6, Timestamp.valueOf(startedAt));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Cannot read product snapshot generation id");
        return key.longValue();
    }

    public Map<String, ExistingItem> findExisting(String sourceDatabase, int warehouseId) {
        Map<String, ExistingItem> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sku,product_name,observed_digest,applied_digest,verification_state,
                       present_in_folio,movement_count,min_movement_recno,max_movement_recno,
                       first_movement_date,last_movement_date,price_rule_count,
                       first_seen_at,applied_at,last_error
                  FROM folio_product_snapshot_item
                 WHERE source_database=? AND warehouse_id=?
                 ORDER BY sku
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString("sku"), new ExistingItem(
                rs.getString("sku"), rs.getString("product_name"),
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

    @Transactional(transactionManager = "wpTransactionManager")
    public void publish(Publish publish) {
        saveItems(publish.items());
        saveChanges(publish.changes());
        jdbc.update("""
                DELETE FROM folio_product_metric_monthly
                 WHERE source_database=? AND warehouse_id=?
                """, publish.sourceDatabase(), publish.warehouseId());
        saveMonthly(publish.generationId(), publish.sourceDatabase(),
                publish.warehouseId(), publish.calculatedAt(), publish.monthly());
        saveCurrent(publish.generationId(), publish.sourceDatabase(),
                publish.warehouseId(), publish.calculatedAt(), publish.current());
        jdbc.update("""
                DELETE c FROM folio_product_metric_current c
                LEFT JOIN folio_product_snapshot_item i
                  ON i.source_database=c.source_database
                 AND i.warehouse_id=c.warehouse_id AND i.sku=c.sku
                 WHERE c.source_database=? AND c.warehouse_id=?
                   AND (i.sku IS NULL OR i.present_in_folio=0)
                """, publish.sourceDatabase(), publish.warehouseId());
        saveAlerts(publish.generationId(), publish.sourceDatabase(),
                publish.warehouseId(), publish.calculatedAt(), publish.alerts());

        int updated = jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET status='ACTIVE',completed_at=?,last_heartbeat_at=?,
                       total_products=?,movement_rows=?,monthly_metric_rows=?,
                       unverified_products=?,dirty_products=?,new_products=?,
                       removed_products=?,warehouse_digest=?,error_message=NULL
                 WHERE id=? AND status='BUILDING'
                """, ts(publish.calculatedAt()), ts(publish.calculatedAt()),
                publish.items().stream().filter(Item::present).count(),
                publish.movementRows(), publish.monthly().size(),
                publish.unverified(), publish.dirty(), publish.created(),
                publish.removed(), publish.warehouseDigest(), publish.generationId());
        if (updated != 1) throw new IllegalStateException("Product snapshot generation is not publishable");
        jdbc.update("""
                UPDATE folio_product_snapshot_generation
                   SET status='SUPERSEDED'
                 WHERE source_database=? AND warehouse_id=? AND status='ACTIVE' AND id<>?
                """, publish.sourceDatabase(), publish.warehouseId(), publish.generationId());
    }

    private void saveItems(List<Item> rows) {
        jdbc.batchUpdate("""
                INSERT INTO folio_product_snapshot_item
                    (source_database,warehouse_id,sku,product_name,observed_digest,
                     applied_digest,verification_state,present_in_folio,movement_count,
                     min_movement_recno,max_movement_recno,first_movement_date,
                     last_movement_date,price_rule_count,first_seen_at,last_seen_at,
                     last_observed_at,applied_at,last_generation_id,last_error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    product_name=VALUES(product_name),observed_digest=VALUES(observed_digest),
                    applied_digest=VALUES(applied_digest),verification_state=VALUES(verification_state),
                    present_in_folio=VALUES(present_in_folio),movement_count=VALUES(movement_count),
                    min_movement_recno=VALUES(min_movement_recno),
                    max_movement_recno=VALUES(max_movement_recno),
                    first_movement_date=VALUES(first_movement_date),
                    last_movement_date=VALUES(last_movement_date),
                    price_rule_count=VALUES(price_rule_count),last_seen_at=VALUES(last_seen_at),
                    last_observed_at=VALUES(last_observed_at),applied_at=VALUES(applied_at),
                    last_generation_id=VALUES(last_generation_id),last_error=VALUES(last_error)
                """, rows, BATCH, (ps, row) -> {
            int p = 1;
            ps.setString(p++, row.sourceDatabase()); ps.setInt(p++, row.warehouseId());
            ps.setString(p++, row.sku()); ps.setString(p++, row.productName());
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

    private void saveMonthly(long generationId, String db, int warehouseId,
                             LocalDateTime at, List<MonthlyMetric> rows) {
        jdbc.batchUpdate("""
                INSERT INTO folio_product_metric_monthly
                    (source_database,warehouse_id,sku,month_start,opening_quantity,
                     closing_quantity,opening_inventory_value,closing_inventory_value,
                     receipt_quantity,receipt_cost,sales_quantity,sales_revenue,sales_cogs,
                     gross_profit,return_quantity,return_revenue,average_inventory_value,
                     inventory_turns,gmroi,sell_through_percent,generation_id,calculated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, rows, BATCH, (ps, row) -> {
            int p=1; ps.setString(p++,db); ps.setInt(p++,warehouseId); ps.setString(p++,row.sku());
            ps.setObject(p++,row.monthStart()); ps.setBigDecimal(p++,row.openingQuantity());
            ps.setBigDecimal(p++,row.closingQuantity()); ps.setBigDecimal(p++,row.openingInventoryValue());
            ps.setBigDecimal(p++,row.closingInventoryValue()); ps.setBigDecimal(p++,row.receiptQuantity());
            ps.setBigDecimal(p++,row.receiptCost()); ps.setBigDecimal(p++,row.salesQuantity());
            ps.setBigDecimal(p++,row.salesRevenue()); ps.setBigDecimal(p++,row.salesCogs());
            ps.setBigDecimal(p++,row.grossProfit()); ps.setBigDecimal(p++,row.returnQuantity());
            ps.setBigDecimal(p++,row.returnRevenue()); ps.setBigDecimal(p++,row.averageInventoryValue());
            nullableDecimal(ps,p++,row.inventoryTurns()); nullableDecimal(ps,p++,row.gmroi());
            nullableDecimal(ps,p++,row.sellThroughPercent()); ps.setLong(p++,generationId);
            ps.setTimestamp(p,ts(at));
        });
    }

    private void saveCurrent(long generationId, String db, int warehouseId,
                             LocalDateTime at, List<CurrentMetric> rows) {
        jdbc.batchUpdate("""
                INSERT INTO folio_product_metric_current
                    (source_database,warehouse_id,sku,product_name,physical_quantity,
                     reserved_quantity,available_quantity,accounting_price,inventory_value,
                     last_receipt_date,last_sale_date,sold_units_30d,sold_units_90d,
                     sold_units_365d,sold_units_730d,revenue_90d,revenue_365d,
                     gross_profit_90d,gross_profit_365d,average_inventory_90d,
                     average_inventory_365d,inventory_turns_365d,gmroi_365d,coverage_days,
                     health_status,generation_id,calculated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE product_name=VALUES(product_name),
                    physical_quantity=VALUES(physical_quantity),reserved_quantity=VALUES(reserved_quantity),
                    available_quantity=VALUES(available_quantity),accounting_price=VALUES(accounting_price),
                    inventory_value=VALUES(inventory_value),last_receipt_date=VALUES(last_receipt_date),
                    last_sale_date=VALUES(last_sale_date),sold_units_30d=VALUES(sold_units_30d),
                    sold_units_90d=VALUES(sold_units_90d),sold_units_365d=VALUES(sold_units_365d),
                    sold_units_730d=VALUES(sold_units_730d),revenue_90d=VALUES(revenue_90d),
                    revenue_365d=VALUES(revenue_365d),gross_profit_90d=VALUES(gross_profit_90d),
                    gross_profit_365d=VALUES(gross_profit_365d),
                    average_inventory_90d=VALUES(average_inventory_90d),
                    average_inventory_365d=VALUES(average_inventory_365d),
                    inventory_turns_365d=VALUES(inventory_turns_365d),gmroi_365d=VALUES(gmroi_365d),
                    coverage_days=VALUES(coverage_days),health_status=VALUES(health_status),
                    generation_id=VALUES(generation_id),calculated_at=VALUES(calculated_at)
                """, rows, BATCH, (ps,row)->{
            int p=1; ps.setString(p++,db); ps.setInt(p++,warehouseId); ps.setString(p++,row.sku());
            ps.setString(p++,row.productName()); ps.setBigDecimal(p++,row.physicalQuantity());
            ps.setBigDecimal(p++,row.reservedQuantity()); ps.setBigDecimal(p++,row.availableQuantity());
            ps.setBigDecimal(p++,row.accountingPrice()); ps.setBigDecimal(p++,row.inventoryValue());
            nullableDate(ps,p++,row.lastReceiptDate()); nullableDate(ps,p++,row.lastSaleDate());
            ps.setBigDecimal(p++,row.soldUnits30d()); ps.setBigDecimal(p++,row.soldUnits90d());
            ps.setBigDecimal(p++,row.soldUnits365d()); ps.setBigDecimal(p++,row.soldUnits730d());
            ps.setBigDecimal(p++,row.revenue90d()); ps.setBigDecimal(p++,row.revenue365d());
            ps.setBigDecimal(p++,row.grossProfit90d()); ps.setBigDecimal(p++,row.grossProfit365d());
            ps.setBigDecimal(p++,row.averageInventory90d()); ps.setBigDecimal(p++,row.averageInventory365d());
            nullableDecimal(ps,p++,row.inventoryTurns365d()); nullableDecimal(ps,p++,row.gmroi365d());
            nullableDecimal(ps,p++,row.coverageDays()); ps.setString(p++,row.healthStatus());
            ps.setLong(p++,generationId); ps.setTimestamp(p,ts(at));
        });
    }

    private void saveAlerts(long generationId, String db, int warehouseId,
                            LocalDateTime at, List<Alert> rows) {
        jdbc.update("""
                UPDATE folio_product_metric_alert
                   SET status='RESOLVED',resolved_at=?,last_seen_at=?,generation_id=?
                 WHERE source_database=? AND warehouse_id=? AND status='ACTIVE'
                """, ts(at),ts(at),generationId,db,warehouseId);
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO folio_product_metric_alert
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

    public Optional<Generation> latest() {
        List<Generation> rows=jdbc.query("""
                SELECT * FROM folio_product_snapshot_generation ORDER BY id DESC LIMIT 1
                """,(rs,n)->new Generation(rs.getLong("id"),rs.getString("source_database"),
                rs.getInt("warehouse_id"),rs.getInt("horizon_months"),rs.getString("status"),
                rs.getString("trigger_source"),rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("completed_at")==null?null:rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getInt("total_products"),rs.getLong("movement_rows"),
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
    private static void nullableDecimal(PreparedStatement ps,int p,java.math.BigDecimal v)throws java.sql.SQLException{
        if(v==null)ps.setNull(p,Types.DECIMAL);else ps.setBigDecimal(p,v);
    }
    private static String truncate(String value,int max){
        if(value==null)return null; return value.length()<=max?value:value.substring(0,max);
    }

    public record ExistingItem(String sku,String productName,String observedDigest,
                               String appliedDigest,String state,boolean present,
                               long movementCount,Long minRecno,Long maxRecno,
                               LocalDate firstMovementDate,LocalDate lastMovementDate,
                               int priceRuleCount,LocalDateTime firstSeenAt,
                               LocalDateTime appliedAt,String lastError){ }
    public record Item(String sourceDatabase,int warehouseId,String sku,String productName,
                       String observedDigest,String appliedDigest,String state,boolean present,
                       long movementCount,Long minRecno,Long maxRecno,LocalDate firstMovementDate,
                       LocalDate lastMovementDate,int priceRuleCount,LocalDateTime firstSeenAt,
                       LocalDateTime lastSeenAt,LocalDateTime observedAt,LocalDateTime appliedAt,
                       long generationId,String lastError){ }
    public record Change(long generationId,String sourceDatabase,int warehouseId,String sku,
                         String type,String beforeDigest,String afterDigest,LocalDateTime detectedAt){ }
    public record Publish(long generationId,String sourceDatabase,int warehouseId,
                          String warehouseDigest,long movementRows,List<Item> items,
                          List<Change> changes,List<MonthlyMetric> monthly,
                          List<CurrentMetric> current,List<Alert> alerts,
                          int unverified,int dirty,int created,int removed,
                          LocalDateTime calculatedAt){ }
    public record Generation(long id,String sourceDatabase,int warehouseId,int horizonMonths,
                             String status,String trigger,LocalDateTime startedAt,
                             LocalDateTime completedAt,int totalProducts,long movementRows,
                             int monthlyMetricRows,int unverified,int dirty,int created,int removed,
                             String warehouseDigest,String error){ }
}
