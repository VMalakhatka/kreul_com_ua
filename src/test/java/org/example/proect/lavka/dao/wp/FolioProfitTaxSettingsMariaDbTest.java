package org.example.proect.lavka.dao.wp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.example.proect.lavka.service.folio.FolioProfitTaxSettingsService;
import org.example.proect.lavka.service.folio.FolioAccountConflictException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Fresh random schema on an explicitly enabled disposable local MariaDB, never app/Folio configuration. */
@EnabledIfSystemProperty(named="folio.profit.tax.test.port", matches="13370")
class FolioProfitTaxSettingsMariaDbTest {
    @TempDir Path migrations;
    private JdbcTemplate root, jdbc;
    private String schema;
    private FolioProfitTaxSettingsService service;
    @BeforeEach void setup() throws Exception {
        String password=Objects.requireNonNull(System.getenv("WP_TAX_TEST_PASSWORD"),"Test password required");
        String url="jdbc:mariadb://127.0.0.1:13370/";
        root=new JdbcTemplate(new DriverManagerDataSource(url,"root",password));
        schema="profit_tax_test_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(url+schema,"root",password);
        jdbc=new JdbcTemplate(ds);
        Files.copy(Path.of("src/main/resources/db/wp/migration/V17__folio_profit_tax_settings.sql"),
                migrations.resolve("V17__folio_profit_tax_settings.sql"));
        var flyway=Flyway.configure().dataSource(ds).locations("filesystem:"+migrations).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        service=new FolioProfitTaxSettingsService(new FolioProfitTaxSettingsDao(jdbc),new ObjectMapper());
    }
    @AfterEach void cleanup() {
        if(root!=null && schema!=null && schema.matches("profit_tax_test_[a-f0-9]{32}")) root.execute("DROP DATABASE "+schema);
    }
    @Test void absentGetIsReadOnlyAndUnicodePersistsAcrossServiceInstances() {
        assertThat(service.get()).isEqualTo(FolioProfitTaxSettings.defaults());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_tax_settings",Integer.class)).isZero();
        var saved=service.put(new FolioProfitTaxSettings(0L,List.of("МИХНФОП","МАЛАФОП"),List.of("КУЗНФОП","КОНДФОП")));
        var restarted=new FolioProfitTaxSettingsService(new FolioProfitTaxSettingsDao(jdbc),new ObjectMapper());
        assertThat(restarted.get()).isEqualTo(saved);
        assertThat(restarted.put(new FolioProfitTaxSettings(1L,List.of(),List.of())).version()).isEqualTo(2);
        assertThat(service.get().retailFirmCodes()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_tax_settings",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentFirstInsertAndConcurrentUpdateEachHaveExactlyOneWinner() throws Exception {
        race(0L); assertThat(service.get().version()).isEqualTo(1);
        race(1L); assertThat(service.get().version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_tax_settings",Integer.class)).isEqualTo(1);
    }
    private void race(long version) throws Exception {
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> writer=()->{
            start.await();
            try {service.put(new FolioProfitTaxSettings(version,List.of("ФОП-"+Thread.currentThread().getId()),List.of()));return true;}
            catch(FolioAccountConflictException expected) {return false;}
        };
        try {
            var first=pool.submit(writer);var second=pool.submit(writer);start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)^second.get(10,TimeUnit.SECONDS)).isTrue();
        } finally {pool.shutdownNow();}
    }
}
