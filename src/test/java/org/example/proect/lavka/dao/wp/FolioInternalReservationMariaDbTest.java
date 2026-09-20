package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MovementFact;
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
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Disposable loopback database only; no application startup or Folio access. */
@EnabledIfEnvironmentVariable(named="FOLIO_INTERNAL_TEST_PORT", matches="[0-9]+")
class FolioInternalReservationMariaDbTest {
    @Test void stagesPublishesQueriesAndReplacesCurrentReservationsAtomically() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mariadb://127.0.0.1:"
                + System.getenv("FOLIO_INTERNAL_TEST_PORT") + "/availability_test", "root", "");
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("availability_test");
        try (var connection = source.getConnection()) {
            for (String name : List.of("V8__folio_product_source_and_economic_snapshots.sql",
                    "V9__folio_product_movement_snapshot.sql", "V10__folio_product_snapshot_bounded_staging.sql",
                    "V11__folio_product_analytics_schema_v3.sql", "V12__folio_product_analytics_schema_v4.sql",
                    "V13__folio_product_availability_history.sql", "V14__folio_accounting_price_diagnostic.sql"))
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/wp/migration/" + name));
            // MariaDB DDL may survive an interrupted migration. Reproduce live-only columns.
            jdbc.execute("ALTER TABLE folio_product_movement_fact ADD COLUMN source_info VARCHAR(255) NULL, "
                    + "ADD COLUMN internal_transfer_reservation TINYINT(1) NOT NULL DEFAULT 0");
            var migration = new ClassPathResource("db/wp/migration/V16__folio_internal_transfer_reservations.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
            var columns = "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
            assertThat(jdbc.queryForList(columns, String.class, "folio_product_movement_fact_stage"))
                    .containsExactlyElementsOf(jdbc.queryForList(columns, String.class, "folio_product_movement_fact"));
        }
        var snapshots = new FolioProductSnapshotDao(jdbc);
        var analytics = new FolioProductAnalyticsDao(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var now = LocalDateTime.now();
        long generation = snapshots.createGeneration("Fixture",7,24,"TEST",now);
        snapshots.stageMovements(generation,"Fixture",7,now,List.of(
                movement(1, "*ПЕРЕМЕЩЕНИЕ", "Odesa assembly", "4", true),
                movement(2, "*ПЕРЕМЕЩЕНИЕ", "Odesa assembly", "2", true),
                movement(3, "*ПРЕДОПЛАТ", "Client", "3", false)));
        assertThat(analytics.internalTransferReservations("Fixture",List.of(7),List.of("SKU"))).isEmpty();
        var publish = new FolioProductSnapshotDao.Publish(generation,"Fixture",7,"Fixture","digest",3,List.of(),List.of(),
                3,0,0,0,0,0,LocalDate.now(),now);
        tx.executeWithoutResult(s -> snapshots.publish(publish));
        var accounts = analytics.internalTransferReservations("Fixture",List.of(7),List.of("SKU")).get("SKU");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).quantity()).isEqualByComparingTo("6");
        assertThat(accounts.get(0).sourceInfo()).isEqualTo("Odesa assembly");
        assertThat(accounts.get(0).generationId()).isEqualTo(generation);
        assertThat(analytics.internalTransferReservations("Fixture",List.of(1),List.of("SKU"))).isEmpty();
        assertThat(analytics.internalTransferReservations("Fixture",List.of(7),List.of("OTHER"))).isEmpty();
        long next = snapshots.createGeneration("Fixture",7,24,"TEST",now);
        var empty = new FolioProductSnapshotDao.Publish(next,"Fixture",7,"Fixture","digest2",0,List.of(),List.of(),
                0,0,0,0,0,0,LocalDate.now(),now);
        jdbc.update("UPDATE folio_product_snapshot_generation SET status='FAILED' WHERE id=?",next);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> snapshots.publish(empty))).isInstanceOf(IllegalStateException.class);
        assertThat(analytics.internalTransferReservations("Fixture",List.of(7),List.of("SKU")).get("SKU")).hasSize(1);
        jdbc.update("UPDATE folio_product_snapshot_generation SET status='BUILDING' WHERE id=?",next);
        tx.executeWithoutResult(s -> snapshots.publish(empty));
        assertThat(analytics.internalTransferReservations("Fixture",List.of(7),List.of("SKU"))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_product_movement_fact_stage",Long.class)).isZero();
    }
    private static MovementFact movement(long line, String operation, String info, String qty, boolean internal) {
        return new MovementFact(line, internal ? 100L : 101L, BigDecimal.valueOf(internal ? 555279 : 555250),
                LocalDate.of(2020,1,1), "SKU", new BigDecimal(qty), BigDecimal.ZERO,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"С","С",operation,true,false,
                "RESERVATION","NONE","NOT_APPLICABLE","NOT_SPECIFIED","NOT_APPLICABLE",
                "INTERNAL","Internal","Я","Supplier","CURRENT",false,false,false,info,internal);
    }
}
