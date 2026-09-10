package org.example.proect.lavka.dao.wp;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Opt-in, disposable localhost database only. Never loads application/Folio configuration. */
@EnabledIfSystemProperty(named="folio.profit.test.mariadb.port", matches="[0-9]+")
class FolioProfitHistoryMariaDbTest {
    @TempDir Path migrations;
    AnnotationConfigApplicationContext context;
    FolioProfitHistoryDao dao;
    JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Config {
        @Bean DataSource dataSource() {
            int port=Integer.parseInt(System.getProperty("folio.profit.test.mariadb.port"));
            if(port<1024||port>65535) throw new IllegalArgumentException("Use disposable high localhost port");
            return new DriverManagerDataSource("jdbc:mariadb:"+"//127.0.0.1:"+port+"/profit_history_test","root","");
        }
        @Bean JdbcTemplate wpJdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager wpTransactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean FolioProfitHistoryDao dao(JdbcTemplate jdbc) { return new FolioProfitHistoryDao(jdbc); }
    }

    @BeforeEach void setup() throws Exception {
        context=new AnnotationConfigApplicationContext(Config.class);
        dao=context.getBean(FolioProfitHistoryDao.class); jdbc=context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("profit_history_test");
        Files.copy(Path.of("src/main/resources/db/wp/migration/V15__folio_profit_report_history.sql"),
                migrations.resolve("V15__folio_profit_report_history.sql"));
        Flyway flyway=Flyway.configure().dataSource(context.getBean(DataSource.class))
                .locations("filesystem:"+migrations).load();
        flyway.migrate(); flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc.update("DELETE FROM folio_profit_report_month");
        jdbc.update("DELETE FROM folio_profit_report_revision");
    }
    @AfterEach void close() { if(context!=null) context.close(); }
    FolioProfitHistoryDao.Row reserve(String id) {
        return dao.reserve("Synthetic","2025-07",id,"0".repeat(64),"{\"salary\":0}");
    }

    @Test void publicationHistoryUnicodeAndImmutableTerminalRevision() {
        var first=reserve(UUID.randomUUID().toString());
        String payload="{\"note\":\"Київ — Одеса\",\"amount\":\"100.0100\"}";
        dao.finish("Synthetic","2025-07",first.id(),"COMPLETED",payload,true,null);
        var second=reserve(UUID.randomUUID().toString());
        dao.finish("Synthetic","2025-07",second.id(),"PROVISIONAL","{}",false,null);
        var failed=reserve(UUID.randomUUID().toString());
        dao.finish("Synthetic","2025-07",failed.id(),"FAILED",null,false,"SYNTHETIC_FAILURE");
        assertThat(dao.state("Synthetic","2025-07").orElseThrow().publishedRevisionId()).isEqualTo(first.id());
        assertThat(dao.state("Synthetic","2025-07").orElseThrow().latestRevisionId()).isEqualTo(failed.id());
        assertThat(dao.byId("Synthetic","2025-07",first.id()).orElseThrow().reportJson()).isEqualTo(payload);
        assertThat(dao.revisions("Synthetic","2025-07",10,Long.MAX_VALUE)).hasSize(3).allMatch(r->r.reportJson()==null);
        assertThatThrownBy(()->dao.finish("Synthetic","2025-07",first.id(),"FAILED",null,false,"ERROR"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void publicationFailureRollsBackRevisionInSameTransaction() {
        var row=reserve(UUID.randomUUID().toString());
        jdbc.execute("ALTER TABLE folio_profit_report_month ADD CONSTRAINT test_no_publish CHECK (published_revision_id IS NULL)");
        try {
            assertThatThrownBy(()->dao.finish("Synthetic","2025-07",row.id(),"COMPLETED","{}",true,null))
                    .isInstanceOf(DataAccessException.class);
            assertThat(dao.byId("Synthetic","2025-07",row.id()).orElseThrow().status()).isEqualTo("RUNNING");
            assertThat(dao.byId("Synthetic","2025-07",row.id()).orElseThrow().reportJson()).isNull();
        } finally { jdbc.execute("ALTER TABLE folio_profit_report_month DROP CONSTRAINT test_no_publish"); }
    }

    @Test void concurrentRequestReservesExactlyOnceAndOlderCompletionCannotReplaceNewer() throws Exception {
        String id=UUID.randomUUID().toString();
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> call=()->{ start.await(); try { reserve(id); return true; } catch(DuplicateKeyException expected) { return false; } };
        try {
            var a=pool.submit(call); var b=pool.submit(call); start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)^b.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_report_revision",Integer.class)).isEqualTo(1);
            var old=dao.byRequest("Synthetic",id).orElseThrow(); var newer=reserve(UUID.randomUUID().toString());
            dao.finish("Synthetic","2025-07",newer.id(),"COMPLETED","{}",true,null);
            dao.finish("Synthetic","2025-07",old.id(),"COMPLETED","{}",true,null);
            assertThat(dao.state("Synthetic","2025-07").orElseThrow().publishedRevisionId()).isEqualTo(newer.id());
        } finally { pool.shutdownNow(); }
    }
}
