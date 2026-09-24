package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse.Summary;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Opt-in isolated localhost MariaDB; never loads application configuration or connects to Folio. */
@EnabledIfSystemProperty(named = "folio.balance.test.mariadb.port", matches = "[0-9]+")
class FolioBalanceRefreshMariaDbTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    static final LocalDateTime START = DAY.atTime(10, 0);
    @TempDir Path migrations;
    AnnotationConfigApplicationContext context;
    FolioCustomerBalanceSnapshotDao dao;
    JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean DataSource dataSource() {
            int port = Integer.parseInt(System.getProperty("folio.balance.test.mariadb.port"));
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("Disposable localhost port required");
            return new DriverManagerDataSource("jdbc:mariadb://127.0.0.1:" + port + "/balance_refresh_test", "root", "");
        }
        @Bean JdbcTemplate wpJdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean NamedParameterJdbcTemplate wpNamedJdbc(JdbcTemplate jdbc) { return new NamedParameterJdbcTemplate(jdbc); }
        @Bean PlatformTransactionManager wpTransactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean FolioCustomerBalanceSnapshotDao dao(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
            return new FolioCustomerBalanceSnapshotDao(jdbc, named);
        }
    }

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        dao = context.getBean(FolioCustomerBalanceSnapshotDao.class);
        jdbc = context.getBean(JdbcTemplate.class);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("balance_refresh_test");
        for (String file : List.of("V6__folio_customer_balance_snapshots.sql", "V7__balance_snapshot_recovery.sql")) {
            Files.copy(Path.of("src/main/resources/db/wp/migration", file), migrations.resolve(file));
        }
        Flyway.configure().dataSource(context.getBean(DataSource.class)).locations("filesystem:" + migrations).load().migrate();
        jdbc.update("UPDATE folio_balance_snapshot_state SET active_generation_id = NULL WHERE id = 1");
        jdbc.update("DELETE FROM folio_balance_snapshot_generation");
        jdbc.update("DELETE FROM folio_balance_snapshot_live_client");
    }
    @AfterEach void close() { if (context != null) context.close(); }

    long generation(LocalDate date) { return dao.createGeneration(date, "MANUAL", START); }
    FolioCustomerBalanceSnapshotDao.SnapshotClient client(String key, String debt, LocalDateTime time) {
        var amount = new BigDecimal(debt);
        return new FolioCustomerBalanceSnapshotDao.SnapshotClient(key, "Synthetic " + key, "TEST", "", "",
                amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount, time);
    }
    Summary summary(String debt) {
        var amount = new BigDecimal(debt);
        var zero = BigDecimal.ZERO;
        return new Summary(zero, zero, zero, zero, zero, amount, zero, zero, zero, amount);
    }
    int update(String debt, LocalDateTime time) {
        return dao.updateActiveClient(DAY, "A", "Synthetic A", summary(debt), time);
    }
    BigDecimal debt(long generation) {
        return jdbc.queryForObject("SELECT payable_now FROM folio_balance_snapshot_client WHERE generation_id=? AND partner_short_name='A'",
                BigDecimal.class, generation);
    }
    long activeWithTwoClients() {
        long id = generation(DAY);
        dao.saveClients(id, List.of(client("A", "30053.68", START), client("B", "26974.79", START)));
        dao.publishGeneration(id, 2, START.plusSeconds(1));
        return id;
    }

    @Test void liveUpdateRemovesPaidClientFromPositiveDebtorsAndRecomputesSummaryAndPaging() {
        long id = activeWithTwoClients();
        assertThat(dao.findDebtors(id, BigDecimal.ZERO, null, List.of(), 1, 0).summary().matchedClients()).isEqualTo(2);
        assertThat(update("-763.20", START.plusMinutes(2))).isEqualTo(1);
        var page = dao.findDebtors(id, BigDecimal.ZERO, null, List.of(), 1, 0);
        assertThat(page.clients()).extracting(c -> c.partnerShortName()).containsExactly("B");
        assertThat(page.summary().matchedClients()).isEqualTo(1);
        assertThat(page.summary().payableNowTotal()).isEqualByComparingTo("26974.79");
        assertThat(dao.findDebtors(id, BigDecimal.ZERO, "A", List.of(), 10, 0).clients()).isEmpty();
        assertThat(dao.findDebtors(id, BigDecimal.ZERO, null, List.of(), 1, 1).clients()).isEmpty();
        update("-763.20", START.plusMinutes(2)); // replay is idempotent
        update("30053.68", START.plusMinutes(1)); // stale completion cannot overwrite
        assertThat(debt(id)).isEqualByComparingTo("-763.20");
    }

    @Test void publicationOverlaysNewerLiveResultEvenWhenBufferedRowWasInsertedLater() {
        activeWithTwoClients();
        long next = generation(DAY);
        update("-763.20", START.plusMinutes(3));
        dao.saveClients(next, List.of(client("A", "30053.68", START.plusMinutes(1))));
        dao.publishGeneration(next, 1, START.plusMinutes(4));
        assertThat(debt(next)).isEqualByComparingTo("-763.20");
        update("999", START.plusMinutes(2));
        assertThat(debt(next)).isEqualByComparingTo("-763.20");
    }

    @Test void oldDaySnapshotIsNotMixedWithTodayAndNewerBackgroundCalculationWins() {
        long old = generation(DAY.minusDays(1));
        dao.saveClients(old, List.of(client("A", "30053.68", START.minusDays(1))));
        dao.publishGeneration(old, 1, START);
        assertThat(update("-763.20", START.plusMinutes(1))).isZero();
        assertThat(debt(old)).isEqualByComparingTo("30053.68");
        long next = generation(DAY);
        dao.saveClients(next, List.of(client("A", "123", START.plusMinutes(2))));
        dao.publishGeneration(next, 1, START.plusMinutes(3));
        assertThat(debt(next)).isEqualByComparingTo("123");
    }

    @Test void failedRowUpdateRollsBackLiveOverrideAndLeavesPublishedSnapshotIntact() {
        long id = activeWithTwoClients();
        jdbc.execute("ALTER TABLE folio_balance_snapshot_client ADD CONSTRAINT test_positive CHECK (payable_now >= 0)");
        try {
            assertThatThrownBy(() -> update("-763.20", START.plusMinutes(1))).isInstanceOf(DataAccessException.class);
            assertThat(debt(id)).isEqualByComparingTo("30053.68");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_balance_snapshot_live_client", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE folio_balance_snapshot_client DROP CONSTRAINT test_positive");
        }
    }

    @Test void liveRefreshWaitsForPublicationLockThenUpdatesNewActiveGeneration() throws Exception {
        activeWithTwoClients();
        long next = generation(DAY);
        dao.saveClients(next, List.of(client("A", "30053.68", START)));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        try {
            var publication = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT active_generation_id FROM folio_balance_snapshot_state WHERE id=1 FOR UPDATE", Long.class);
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                dao.publishGeneration(next, 1, START.plusMinutes(2));
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var live = pool.submit(() -> { attempted.countDown(); return update("-763.20", START.plusMinutes(3)); });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> live.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            // The live transaction must not insert its override while publication owns the state lock.
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_balance_snapshot_live_client", Integer.class)).isZero();
            release.countDown();
            publication.get(5, TimeUnit.SECONDS);
            live.get(5, TimeUnit.SECONDS);
            assertThat(debt(next)).isEqualByComparingTo("-763.20");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
