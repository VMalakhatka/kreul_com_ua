package org.example.proect.lavka.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: disposable localhost instances only; never loads application/Folio beans. */
@EnabledIfSystemProperty(named="wp.v12.test.port",matches="13369|13370")
class WpFlywayV12DatabaseTest {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    String engine;
    @BeforeEach void setup() {
        String port=System.getProperty("wp.v12.test.port");
        String pass=System.getenv("WP_V12_TEST_PASSWORD");
        assertThat(pass).isNotNull();
        var admin=new DriverManagerDataSource("jdbc:mariadb://127.0.0.1:"+port+"/mysql?allowPublicKeyRetrieval=true","root",pass);
        String db="wp_v12_test_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(admin).execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        ds=new DriverManagerDataSource("jdbc:mariadb://127.0.0.1:"+port+"/"+db+"?allowPublicKeyRetrieval=true","root",pass);
        jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection()) {engine=c.getMetaData().getDatabaseProductName();} catch(Exception e){throw new RuntimeException(e);}
        legacy().target("11").load().migrate();
    }
    FluentConfiguration legacy() {return Flyway.configure().dataSource(ds).locations(WpFlywayMigrationConfiguration.COMMON,WpFlywayMigrationConfiguration.MARIADB);}
    Flyway runtime(String target) {
        var cfg=Flyway.configure().dataSource(ds).locations(WpFlywayMigrationConfiguration.COMMON).target(target);
        new WpFlywayMigrationConfiguration().customize(cfg); return cfg.load();
    }
    @Test void completeChainAndSpringBootRuntimeHaveExactlyOneV12() {
        runtime("15").migrate(); runtime("15").validate();
        assertThat(runtime("15").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='12' AND success=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(15);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(WpFlywayMigrationConfiguration.class).withBean(DataSource.class,()->ds)
                .withPropertyValues("spring.flyway.locations="+WpFlywayMigrationConfiguration.COMMON,
                        "spring.flyway.target=15")
                .run(ctx->{assertThat(ctx).hasNotFailed();ctx.getBean(Flyway.class).validate();});
    }
    @Test void existingMariaDbHistoryValidatesWithoutRepairOrChecksumChange() {
        Assumptions.assumeTrue("MariaDB".equals(engine));
        legacy().load().migrate();
        var before=jdbc.queryForList("SELECT version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        runtime("15").validate(); assertThat(runtime("15").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='12'",Integer.class)).isEqualTo(-1886659825);
    }
    @Test void existingV18UpgradesThroughV20AndKeepsLegacyChecksums() {
        Assumptions.assumeTrue("MariaDB".equals(engine));
        legacy().target("18").load().migrate();
        var before=jdbc.queryForList("SELECT version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        var latest=runtime("20");
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT version,type,script,checksum,success FROM flyway_schema_history WHERE installed_rank<=18 ORDER BY installed_rank"))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='19'",Integer.class)).isEqualTo(-1642929544);
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='20'",Integer.class)).isEqualTo(497939418);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_scope_settings",Integer.class)).isZero();
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void mariaDbMysqlMetadataCompatibilityValidatesOriginalHistoryAndBootsWithoutRepair() throws Exception {
        Assumptions.assumeTrue("MariaDB".equals(engine));
        legacy().load().migrate();
        var before=jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var masked=new DriverManagerDataSource(ds.getUrl()+"&useMysqlMetadata=true",ds.getUsername(),ds.getPassword());
        try(var c=masked.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(WpFlywayMigrationConfiguration.databaseEngine(c)).isEqualTo("MariaDB");
        }
        var config=Flyway.configure().dataSource(masked).locations(WpFlywayMigrationConfiguration.COMMON);
        new WpFlywayMigrationConfiguration().customize(config);
        var runtime=config.load();
        runtime.validate();
        assertThat(runtime.migrate().migrationsExecuted).isZero();
        assertThat(runtime.info().all()).filteredOn(m->m.getVersion()!=null && m.getVersion().toString().equals("12"))
                .hasSize(1).allSatisfy(m->{assertThat(m.getType().toString()).isEqualTo("SQL");assertThat(m.getChecksum()).isEqualTo(-1886659825);});
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withUserConfiguration(WpFlywayMigrationConfiguration.class).withBean(DataSource.class,()->masked)
                .withPropertyValues("spring.flyway.locations="+WpFlywayMigrationConfiguration.COMMON)
                .run(ctx->{assertThat(ctx).hasNotFailed();ctx.getBean(Flyway.class).validate();});
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(before);
    }
    @Test void failedMysqlSqlV12CanBeRepairedWithoutChangingEarlierHistory() {
        Assumptions.assumeTrue("MySQL".equals(engine));
        var before=jdbc.queryForList("SELECT version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        assertThatThrownBy(()->legacy().target("12").load().migrate()).hasMessageContaining("1064");
        assertThatThrownBy(()->runtime("12").validate()).hasMessageContaining("failed");
        var repair=runtime("12").repair();
        assertThat(repair.migrationsRemoved).hasSize(1);
        assertThat(repair.migrationsAligned).isEmpty(); assertThat(repair.migrationsDeleted).isEmpty();
        assertThat(jdbc.queryForList("SELECT version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(before);
        assertThat(runtime("12").migrate().migrationsExecuted).isEqualTo(1);
        runtime("12").validate(); assertThat(runtime("15").migrate().migrationsExecuted).isEqualTo(3);
    }
    @Test void partialMatchingMysqlColumnIsPreservedAndMissingObjectsAdded() {
        Assumptions.assumeTrue("MySQL".equals(engine));
        jdbc.execute("ALTER TABLE folio_product_metric_current ADD primary_barcode VARCHAR(128) NULL AFTER maximum_stock");
        runtime("12").migrate(); runtime("12").validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='primary_barcode'",Integer.class)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"type","nullable","position"})
    void wrongExistingMysqlColumnFailsBeforeAnyOtherDdl(String problem) {
        Assumptions.assumeTrue("MySQL".equals(engine));
        jdbc.execute("ALTER TABLE folio_product_metric_current ADD primary_barcode " + switch(problem) {
            case "type" -> "VARCHAR(64) NULL AFTER maximum_stock";
            case "nullable" -> "VARCHAR(128) NOT NULL AFTER maximum_stock";
            default -> "VARCHAR(128) NULL";
        });
        assertThatThrownBy(()->runtime("12").migrate()).hasStackTraceContaining("WP_V12_SCHEMA_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='primary_barcode'",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"order","prefix"})
    void wrongExistingMysqlIndexOrderAndPrefixFailClosed(String problem) {
        Assumptions.assumeTrue("MySQL".equals(engine));
        jdbc.execute("ALTER TABLE folio_product_metric_current ADD primary_barcode VARCHAR(128) NULL AFTER maximum_stock");
        jdbc.execute("ALTER TABLE folio_product_metric_current ADD KEY idx_folio_product_metric_primary_barcode "
                + (problem.equals("order") ? "(sku,primary_barcode,source_database,warehouse_id)"
                : "(source_database,primary_barcode(10),warehouse_id,sku)"));
        assertThatThrownBy(()->runtime("12").migrate()).hasStackTraceContaining("WP_V12_SCHEMA_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='primary_barcode'",Integer.class)).isEqualTo(1);
    }
}
