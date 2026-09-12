package org.example.proect.lavka.dao.wp;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Same calculation for page details, server filtering, sorting and exports. */
public final class FolioAvailabilitySql {
    private FolioAvailabilitySql() { }

    public static long mask(LocalDate from, LocalDate to) {
        return ((1L << to.getDayOfMonth()) - 1) & ~((1L << (from.getDayOfMonth() - 1)) - 1);
    }

    public static String summary(FolioProductAnalyticsDao.QuerySpec spec,
                                 List<Integer> members, List<String> skus,
                                 MapSqlParameterSource params) {
        String monthly = monthly(spec, members, skus, params);
        String totals = """
                SELECT sku,CASE
                  WHEN MAX(missing_snapshot)>0 THEN 'SNAPSHOT_NOT_READY'
                  WHEN MAX(missing_card)>0 THEN 'DATA_INCOMPLETE'
                  WHEN MAX(unknown_policy)>0 THEN 'POLICY_NOT_CONFIRMED'
                  WHEN MAX(eligible)=0 THEN 'NOT_APPLICABLE'
                  WHEN MAX(outside_horizon)>0 THEN 'PERIOD_OUTSIDE_HORIZON'
                  WHEN MAX(incomplete)>0 THEN 'DATA_INCOMPLETE'
                  ELSE 'MEASURED' END availability_status,
                  SUM(BIT_COUNT(wanted)) period_days,
                  SUM(BIT_COUNT(available_mask)) available_days,
                  MAX(negative_stock) negative_stock,MAX(minimum_stock) minimum_stock
                FROM (
                """ + monthly + ") months GROUP BY sku";
        return "SELECT t.*,CASE WHEN availability_status='MEASURED' THEN "
                + "ROUND(100.0*available_days/period_days,2) END availability_percent,"
                + "CASE WHEN availability_status='MEASURED' THEN "
                + "ROUND(100.0*(period_days-available_days)/period_days,2) END stockout_percent "
                + "FROM (" + totals + ") t";
    }
    public static String monthly(FolioProductAnalyticsDao.QuerySpec spec,
                                 List<Integer> members, List<String> skus,
                                 MapSqlParameterSource params) {
        params.addValue("avDb", spec.sourceDatabase()).addValue("avScope", spec.warehouseIds());
        params.addValue("avFrom", spec.periodFrom()).addValue("avTo", spec.periodTo());
        String skuFilter = "";
        if (skus != null) {
            params.addValue("avSkus", skus);
            skuFilter = " AND sku IN (:avSkus)";
        }
        List<String> months = new ArrayList<>();
        for (LocalDate month = spec.periodFrom().withDayOfMonth(1);
             !month.isAfter(spec.periodTo()); month = month.plusMonths(1)) {
            LocalDate from = month.isBefore(spec.periodFrom()) ? spec.periodFrom() : month;
            LocalDate end = month.plusMonths(1).minusDays(1);
            LocalDate to = end.isAfter(spec.periodTo()) ? spec.periodTo() : end;
            months.add("SELECT CAST('" + month + "' AS DATE) month_start," + mask(from, to) + " wanted");
        }
        String ids = members.stream().map(id -> "SELECT " + id + " warehouse_id")
                .collect(java.util.stream.Collectors.joining(" UNION ALL "));
        return """
                SELECT s.sku,d.month_start,d.wanted,
                  MAX(g.id IS NULL) missing_snapshot,
                  MAX(c.sku IS NULL) missing_card,
                  MAX(c.sku IS NOT NULL AND (c.minimum_stock IS NULL OR c.minimum_stock<0)) unknown_policy,
                  MAX(COALESCE(c.minimum_stock,0)>0) eligible,
                  MAX(COALESCE(c.minimum_stock,0)>0 AND
                     (:avFrom < DATE_SUB(DATE_SUB(g.as_of_date, INTERVAL DAYOFMONTH(g.as_of_date)-1 DAY),
                                       INTERVAL g.horizon_months-1 MONTH) OR :avTo>g.as_of_date)) outside_horizon,
                  MAX(COALESCE(c.minimum_stock,0)>0 AND
                     (a.sku IS NULL OR a.quality<>'RECONCILED' OR (a.known_mask & d.wanted)<>d.wanted)) incomplete,
                  BIT_OR(CASE WHEN c.minimum_stock>0 THEN COALESCE(a.available_mask,0) & d.wanted ELSE 0 END) available_mask,
                  MAX(c.minimum_stock>0 AND (COALESCE(a.negative_mask,0) & d.wanted)<>0) negative_stock,
                  MAX(c.minimum_stock) minimum_stock
                FROM (SELECT DISTINCT sku FROM folio_product_metric_current
                      WHERE source_database=:avDb AND warehouse_id IN (:avScope)
                """ + skuFilter + ") s CROSS JOIN (" + ids + ") w CROSS JOIN ("
                + String.join(" UNION ALL ", months) + ") d " + """
                LEFT JOIN folio_product_snapshot_generation g ON g.source_database=:avDb
                     AND g.warehouse_id=w.warehouse_id AND g.status='ACTIVE'
                LEFT JOIN folio_product_metric_current c ON c.source_database=:avDb
                     AND c.warehouse_id=w.warehouse_id AND c.sku=s.sku AND c.generation_id=g.id
                LEFT JOIN folio_product_availability_monthly a ON a.source_database=:avDb
                     AND a.warehouse_id=w.warehouse_id AND a.sku=s.sku AND a.generation_id=g.id
                     AND a.month_start=d.month_start
                GROUP BY s.sku,d.month_start,d.wanted
                """;
    }

}
