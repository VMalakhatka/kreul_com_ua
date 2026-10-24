package org.example.proect.lavka.dao.wp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitScopeCatalogDao;
import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog.Warehouse;
import org.example.proect.lavka.dto.folio.FolioProfitScopeSettings;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.example.proect.lavka.service.folio.*;
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
import static org.mockito.Mockito.*;

/** Opt-in only: random schema on a disposable loopback MariaDB; Folio itself is always mocked. */
@EnabledIfSystemProperty(named="folio.profit.scope.test.port",matches="13370")
class FolioProfitScopeSettingsMariaDbTest {
    @TempDir Path migrations;
    private JdbcTemplate root,jdbc;
    private String schema;
    private FolioProfitScopeSettingsService service;
    private final FolioProfitScopeCatalogDao catalog=mock(FolioProfitScopeCatalogDao.class);
    @BeforeEach void setup() throws Exception {
        String password=Objects.requireNonNull(System.getenv("WP_SCOPE_TEST_PASSWORD"),"Test password required");
        String url="jdbc:mariadb://127.0.0.1:13370/";
        root=new JdbcTemplate(new DriverManagerDataSource(url,"root",password));
        schema="profit_scope_test_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+schema);
        var ds=new DriverManagerDataSource(url+schema,"root",password);
        jdbc=new JdbcTemplate(ds);
        Files.copy(Path.of("src/main/resources/db/wp/migration/V20__folio_profit_scope_settings.sql"),
                migrations.resolve("V20__folio_profit_scope_settings.sql"));
        var flyway=Flyway.configure().dataSource(ds).locations("filesystem:"+migrations).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        when(catalog.warehouses()).thenReturn(List.of(new Warehouse(1,"Киев"),new Warehouse(5,"Одесса"),
                new Warehouse(7,"Опт"),new Warehouse(12,"Хранение")));
        when(catalog.groupPaths()).thenReturn(List.of(List.of("Материалы","Бумага")));
        service=instance();
    }
    private FolioProfitScopeSettingsService instance() {
        return new FolioProfitScopeSettingsService(new FolioProfitScopeSettingsDao(jdbc),catalog,
                new ObjectMapper(),new FolioProfitReportProperties());
    }
    @AfterEach void cleanup() {
        if(root!=null&&schema!=null&&schema.matches("profit_scope_test_[a-f0-9]{32}")) root.execute("DROP DATABASE "+schema);
    }
    @Test void emptyGetDoesNotWriteAndSelectionsSurviveServiceRestart() {
        assertThat(service.get().version()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_scope_settings",Integer.class)).isZero();
        var selected=new FolioProfitScopeSettings(0L,List.of(1,7),List.of(5),List.of(1,7,12),List.of(5),
                List.of(),List.of(FolioProfitGroupPath.id(List.of("Материалы","Бумага"))));
        var saved=service.put(selected);
        var restarted=instance();
        assertThat(restarted.get()).isEqualTo(saved);
        assertThat(restarted.put(saved).version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_scope_settings",Integer.class)).isEqualTo(1);
    }
    @Test void simultaneousFirstInsertAndUpdateEachHaveOneWinner() throws Exception {
        race(0L); assertThat(service.get().version()).isEqualTo(1);
        race(1L); assertThat(service.get().version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM folio_profit_scope_settings",Integer.class)).isEqualTo(1);
    }
    private void race(long version) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> writer=()->{
            start.await();
            try {service.put(FolioProfitScopeSettingsService.defaults(new FolioProfitReportProperties()).withVersion(version));return true;}
            catch(FolioAccountConflictException expected) {return false;}
        };
        try {
            var first=pool.submit(writer);var second=pool.submit(writer);start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS)^second.get(10,TimeUnit.SECONDS)).isTrue();
        } finally {pool.shutdownNow();}
    }
}
