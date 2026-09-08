package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dao.wp.FolioProductAnalyticsDao.*;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.*;
import org.example.proect.lavka.service.folio.FolioProductAvailabilityHistory.Month;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Run only against a disposable loopback MariaDB named availability_test. */
@EnabledIfEnvironmentVariable(named="FOLIO_AVAILABILITY_TEST_PORT", matches="[0-9]+")
class FolioAvailabilityMariaDbTest {
    static DriverManagerDataSource source;
    static JdbcTemplate jdbc;
    static FolioProductAnalyticsDao dao;
    static FolioProductSnapshotDao snapshots;
    static final LocalDate START = LocalDate.of(2026, 6, 1);
    static final long ALL = (1L << 30) - 1;
    static final long FIRST20 = (1L << 20) - 1;

    @BeforeAll static void createSchema() throws Exception {
        source = new DriverManagerDataSource("jdbc:mariadb://127.0.0.1:"
                + System.getenv("FOLIO_AVAILABILITY_TEST_PORT") + "/availability_test", "root", "");
        jdbc = new JdbcTemplate(source); dao = new FolioProductAnalyticsDao(jdbc);
        snapshots = new FolioProductSnapshotDao(jdbc);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("availability_test");
        try (var connection = source.getConnection()) {
            for (String name : List.of("V8__folio_product_source_and_economic_snapshots.sql",
                    "V9__folio_product_movement_snapshot.sql", "V10__folio_product_snapshot_bounded_staging.sql",
                    "V11__folio_product_analytics_schema_v3.sql", "V12__folio_product_analytics_schema_v4.sql",
                    "V13__folio_product_availability_history.sql", "V14__folio_accounting_price_diagnostic.sql"))
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/wp/migration/" + name));
        }
    }

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM folio_accounting_price_diagnostic");
        jdbc.update("DELETE FROM folio_product_movement_fact");
        for (String table : List.of("folio_product_availability_monthly", "folio_product_availability_monthly_stage",
                "folio_product_metric_current", "folio_product_metric_current_stage",
                "folio_product_snapshot_change", "folio_product_snapshot_item", "folio_product_metric_alert",
                "folio_product_snapshot_generation")) jdbc.update("DELETE FROM " + table);
        for (int warehouse : List.of(1,7)) {
            jdbc.update("""
                INSERT INTO folio_product_snapshot_generation
                (id,source_database,warehouse_id,horizon_months,analytics_schema_version,status,
                 trigger_source,started_at,last_heartbeat_at,as_of_date)
                VALUES (?,'Fixture',?,24,5,'ACTIVE','TEST',NOW(),NOW(),'2026-07-01')
                """, warehouse, warehouse);
            for (int i=0; i<10; i++) {
                String sku = "SKU-"+i;
                BigDecimal min = switch(i) {
                    case 2 -> BigDecimal.ZERO;
                    case 3 -> null;
                    case 1 -> warehouse == 7 ? BigDecimal.ZERO : BigDecimal.ONE;
                    default -> BigDecimal.ONE;
                };
                jdbc.update("""
                    INSERT INTO folio_product_metric_current
                    (source_database,warehouse_id,sku,minimum_stock,health_status,generation_id,calculated_at)
                    VALUES ('Fixture',?,?,?,'OK',?,NOW())
                    """, warehouse, sku, min, warehouse);
                long mask = warehouse == 1 ? FIRST20 : ALL ^ FIRST20;
                if (i==1 || i==2) mask = 0;
                if (i==4) mask = warehouse == 7 ? ALL : (1L<<18)-1;
                List<Month> rows = List.of(new Month(sku, START, ALL, mask, i==5 ? 1 : 0,
                        i==6 ? "DATA_INCOMPLETE" : "RECONCILED", BigDecimal.ZERO));
                snapshots.stageAvailability(warehouse, "Fixture", warehouse, rows);
            }
        }
        jdbc.update("INSERT INTO folio_product_availability_monthly SELECT * FROM folio_product_availability_monthly_stage");
        jdbc.update("DELETE FROM folio_product_availability_monthly_stage");
        jdbc.update("DELETE FROM folio_product_availability_monthly WHERE sku='SKU-7' AND warehouse_id=7");
        jdbc.update("DELETE FROM folio_product_metric_current WHERE sku='SKU-8' AND warehouse_id=7");
        jdbc.update("UPDATE folio_product_availability_monthly SET known_mask=1 WHERE sku='SKU-9' AND warehouse_id=7");
    }

    @Test void tenProductsAcrossTwoWarehousesPreservePhysicalDetailsAndGroupUnion() {
        var spec = spec(null, List.of());
        var skus = java.util.stream.IntStream.range(0,10).mapToObj(i -> "SKU-"+i).toList();
        var group = dao.availability(spec, List.of(1,7), skus);
        var kyiv = dao.availability(spec, List.of(1), skus);
        var wholesale = dao.availability(spec, List.of(7), skus);
        assertThat(group).hasSize(10);
        assertThat(kyiv.get("SKU-0").availableDays()).isEqualTo(20);
        assertThat(wholesale.get("SKU-0").availableDays()).isEqualTo(10);
        assertThat(group.get("SKU-0").availabilityPercent()).isEqualByComparingTo("100");
        assertThat(group.get("SKU-1").stockoutDays()).isEqualTo(30);
        assertThat(wholesale.get("SKU-1").status()).isEqualTo("NOT_APPLICABLE");
        assertThat(group.get("SKU-2").status()).isEqualTo("NOT_APPLICABLE");
        assertThat(group.get("SKU-2").availabilityPercent()).isNull();
        assertThat(group.get("SKU-3").status()).isEqualTo("POLICY_NOT_CONFIRMED");
        assertThat(kyiv.get("SKU-4").availabilityPercent()).isEqualByComparingTo("60");
        assertThat(group.get("SKU-4").availabilityPercent()).isEqualByComparingTo("100");
        assertThat(group.get("SKU-5").warnings()).contains("NEGATIVE_PHYSICAL_STOCK");
        for (int i : List.of(6,7,8,9)) {
            assertThat(group.get("SKU-"+i).status()).isEqualTo("DATA_INCOMPLETE");
            assertThat(group.get("SKU-"+i).stockoutDays()).isNull();
        }
    }

    @Test void availabilityFiltersAndSortApplyBeforePaginationAndTotals() {
        var options = new AvailabilityCalculation(true,"PHYSICAL_END_OF_DAY","CURRENT_POLICY_GT_ZERO",
                "WAREHOUSES_AND_GROUPS","a".repeat(64),
                List.of(new WarehouseGroup("CITY","City",List.of(1,7),"ANY_ELIGIBLE_MEMBER")),
                null,"CITY",new AvailabilityFilter(new BigDecimal("99"),null,null,null,List.of("MEASURED")));
        var result = dao.query(spec(options,List.of(new SortSpec("availabilityPercent","DESC"))));
        assertThat(result.total().productCount()).isEqualTo(3);
        assertThat(result.rows()).extracting(AggregateRow::sku).containsExactly("SKU-0","SKU-4","SKU-5");
        assertThat(result.basisRows()).hasSize(3);
        var page = new QuerySpec("Fixture",List.of(1,7),START,START.plusDays(29),null,Map.of(),Map.of(),
                1,1,List.of(new SortSpec("availabilityPercent","DESC")),"GROSS_PROFIT",options);
        var second = dao.query(page);
        assertThat(second.total().productCount()).isEqualTo(3);
        assertThat(second.rows()).extracting(AggregateRow::sku).containsExactly("SKU-4");
    }

    @Test void partialMonthDoesNotCountUnselectedDays() {
        var base = spec(null,List.of());
        var part = new QuerySpec(base.sourceDatabase(),base.warehouseIds(),START.plusDays(15),START.plusDays(24),
                null,Map.of(),Map.of(),50,0,List.of(),"GROSS_PROFIT");
        var result = dao.availability(part,List.of(1),List.of("SKU-0")).get("SKU-0");
        assertThat(result.periodDays()).isEqualTo(10);
        assertThat(result.availableDays()).isEqualTo(5);
        assertThat(result.stockoutPercent()).isEqualByComparingTo("50");
    }

    @Test void failedPublicationRollsBackNewMasksAndKeepsOldActiveHistory() {
        long id = snapshots.createGeneration("Fixture",1,24,"TEST",LocalDateTime.now());
        snapshots.stageAvailability(id,"Fixture",1,List.of(new Month("SKU-0",START,ALL,0,0,"RECONCILED",BigDecimal.ZERO)));
        jdbc.update("UPDATE folio_product_snapshot_generation SET status='FAILED' WHERE id=?",id);
        var publish = new FolioProductSnapshotDao.Publish(id,"Fixture",1,"Fixture","digest",0,List.of(),List.of(),
                0,0,0,0,0,0,LocalDate.of(2026,7,1),LocalDateTime.now());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> snapshots.publish(publish)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT available_mask FROM folio_product_availability_monthly WHERE warehouse_id=1 AND sku='SKU-0'",Long.class))
                .isEqualTo(FIRST20);
        assertThat(jdbc.queryForObject("SELECT status FROM folio_product_snapshot_generation WHERE id=1",String.class)).isEqualTo("ACTIVE");
        jdbc.update("UPDATE folio_product_snapshot_generation SET status='BUILDING' WHERE id=?",id);
        tx.executeWithoutResult(s -> snapshots.publish(publish));
        assertThat(jdbc.queryForObject("SELECT available_mask FROM folio_product_availability_monthly WHERE warehouse_id=1 AND sku='SKU-0'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM folio_product_snapshot_generation WHERE id=1",String.class)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_product_availability_monthly_stage",Long.class)).isZero();
    }

    @Test void crossMonthWindowsAndUnknownCaptureDaysAreNotSilentlyTruncated() {
        jdbc.update("UPDATE folio_product_snapshot_generation SET as_of_date='2026-08-01'");
        for (int w : List.of(1,7)) {
            snapshots.stageAvailability(w,"Fixture",w,List.of(new Month("SKU-0",START.plusMonths(1),
                    (1L<<31)-1,w==1 ? (1L<<31)-1 : 0,0,"RECONCILED",BigDecimal.ZERO)));
        }
        jdbc.update("INSERT INTO folio_product_availability_monthly SELECT * FROM folio_product_availability_monthly_stage");
        var spec = new QuerySpec("Fixture",List.of(1,7),START.plusDays(15),LocalDate.of(2026,7,10),
                null,Map.of(),Map.of(),50,0,List.of(),"GROSS_PROFIT");
        var physical = dao.availability(spec,List.of(1),List.of("SKU-0")).get("SKU-0");
        assertThat(physical.periodDays()).isEqualTo(25);
        assertThat(physical.availableDays()).isEqualTo(15);
        assertThat(dao.availability(spec,List.of(1,7),List.of("SKU-0")).get("SKU-0").availableDays()).isEqualTo(25);
        var includingCaptureDay = new QuerySpec("Fixture",List.of(1,7),START,LocalDate.of(2026,8,1),
                null,Map.of(),Map.of(),50,0,List.of(),"GROSS_PROFIT");
        var incomplete = dao.availability(includingCaptureDay,List.of(1,7),List.of("SKU-0")).get("SKU-0");
        assertThat(incomplete.status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(incomplete.availabilityPercent()).isNull();
    }

    @Test void configuredTransitSqlUsesCurrentGenerationAndSupplierIdentityNotHistoricReceipts() {
        jdbc.update("UPDATE folio_product_snapshot_generation SET completed_at=NOW(3) WHERE source_database='Fixture'");
        for (int warehouse : List.of(1,7)) {
            int physical = warehouse == 1 ? 14 : 8;
            int reserve = warehouse == 1 ? 2 : 0;
            jdbc.update("UPDATE folio_product_metric_current SET physical_quantity=?,reserved_quantity=?,available_quantity=? "
                    + "WHERE warehouse_id=? AND sku='SKU-0'",physical,reserve,physical-reserve,warehouse);
            for (int n=1;n<=2;n++) jdbc.update("""
                INSERT INTO folio_product_movement_fact
                (source_database,warehouse_id,movement_recno,generation_id,document_date,sku,quantity,signed_quantity,
                 movement_class,stock_direction,demand_mode,payment_terms,customer_segment,supplier_state,
                 affects_stock,organization_type,counterparty_short_name,captured_at)
                VALUES ('Fixture',?,?,?,'2026-06-01','SKU-0',?,?,?,?,'REGULAR','UNKNOWN','UNKNOWN','CURRENT',1,'Т','SUP',NOW())
                """,warehouse,n,warehouse,n==1 ? 1000 : 1000-physical,n==1 ? 1000 : physical-1000,
                    n==1 ? "PURCHASE_RECEIPT" : "TRANSFER_OUT",n==1 ? "IN" : "OUT");
        }
        jdbc.update("""
                INSERT INTO folio_product_metric_current
                (source_database,warehouse_id,sku,health_status,generation_id,calculated_at,physical_quantity,available_quantity)
                VALUES ('OtherDatabase',1,'SKU-0','OK',1,NOW(),999,999)
                """);
        var first = dao.transitRows("Fixture",1,1,List.of("SKU-0"),List.of("Т","I"));
        var second = dao.transitRows("Fixture",7,7,List.of("SKU-0"),List.of("Т","I"));
        assertThat(first.get("SKU-0").openingQuantity()).isEqualByComparingTo("0");
        assertThat(first.get("SKU-0").availableQuantity()).isEqualByComparingTo("12");
        assertThat(first.get("SKU-0").suppliers().get(0).receiptQuantity()).isEqualByComparingTo("1000");
        var config = new TransitCalculation(List.of(1,7),
                org.example.proect.lavka.service.folio.FolioTransitAnalytics.revision(List.of(1,7)));
        var cap = org.example.proect.lavka.service.folio.FolioTransitAnalytics.capability(config,
                dao.activeGenerations("Fixture",List.of(1,7)),List.of(5),5);
        var total = org.example.proect.lavka.service.folio.FolioTransitAnalytics.stock(cap,Map.of(1,first,7,second),"SKU-0");
        assertThat(total.availableQuantity()).isEqualByComparingTo("20");
        assertThat(total.availableForPlanningQuantity()).isNull();
        assertThat(total.availableForNetworkPlanningQuantity()).isNull();
        assertThat(total.sources().get(0).availableForNetworkPlanningQuantity()).isEqualByComparingTo("12");
        jdbc.update("UPDATE folio_product_movement_fact SET counterparty_short_name='' WHERE warehouse_id=1 AND movement_recno=1");
        assertThat(dao.transitRows("Fixture",1,1,List.of("SKU-0"),List.of("Т","I")).get("SKU-0").supplierInboundCount()).isZero();
        jdbc.update("UPDATE folio_product_metric_current SET generation_id=7 WHERE source_database='Fixture' AND warehouse_id=1 AND sku='SKU-0'");
        assertThat(dao.transitRows("Fixture",1,1,List.of("SKU-0"),List.of("Т","I"))).isEmpty();
    }

    @Test void arithmeticJournalPersistsCompleteJsonAndPreviewDoesNotChangeVerification() {
        jdbc.update("""
                INSERT INTO folio_product_snapshot_item
                    (source_database,warehouse_id,sku,observed_digest,applied_digest,verification_state,
                     present_in_folio,first_seen_at,last_seen_at,last_observed_at,last_generation_id)
                VALUES ('Fixture',1,'ARITHMETIC','same','same','VERIFIED',1,NOW(),NOW(),NOW(),1)
                """);
        String json = "{\"sku\":\"ARITHMETIC\",\"rollbackConfirmed\":true,\"detail\":\"" + "x".repeat(2000) + "\"}";
        snapshots.recordSkuFailureDiagnostic("Fixture",1,"ARITHMETIC","job",true,"ACCOUNTING_PRICE_DIVIDE_BY_ZERO",
                "rolled back",json,LocalDateTime.now());
        assertThat(jdbc.queryForObject("SELECT verification_state FROM folio_product_snapshot_item WHERE sku='ARITHMETIC'",String.class))
                .isEqualTo("VERIFIED");
        snapshots.recordSkuFailureDiagnostic("Fixture",1,"ARITHMETIC","job",false,"ACCOUNTING_PRICE_DIVIDE_BY_ZERO",
                "rolled back",json,LocalDateTime.now());
        snapshots.recordSkuFailureDiagnostic("Fixture",1,"ARITHMETIC","job",false,"NEGATIVE_CHRONOLOGICAL_STOCK",
                "negative stock",json,LocalDateTime.now());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_accounting_price_diagnostic",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT error_code FROM folio_accounting_price_diagnostic WHERE preview_only=0",String.class))
                .isEqualTo("NEGATIVE_CHRONOLOGICAL_STOCK");
        assertThat(jdbc.queryForObject("SELECT last_error FROM folio_product_snapshot_item WHERE sku='ARITHMETIC'",String.class))
                .contains("NEGATIVE_CHRONOLOGICAL_STOCK", "jobId=job");
        assertThat(jdbc.queryForObject("SELECT diagnostics_json FROM folio_accounting_price_diagnostic WHERE preview_only=0",String.class))
                .isEqualTo(json);
        assertThat(jdbc.queryForMap("SELECT verification_state,applied_digest FROM folio_product_snapshot_item WHERE sku='ARITHMETIC'"))
                .containsEntry("verification_state","FAILED").containsEntry("applied_digest",null);
    }

    static QuerySpec spec(AvailabilityCalculation options,List<SortSpec> sort) {
        return new QuerySpec("Fixture",List.of(1,7),START,START.plusDays(29),null,Map.of(),Map.of(),50,0,sort,"GROSS_PROFIT",options);
    }
}
