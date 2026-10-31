package org.example.proect.lavka.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WpFlywayMigrationConfigurationTest {
    @Test void mysqlSelectsOnlyJavaV12EvenWithMariaDbJdbcDriver() throws Exception {
        var config = configuration(source("MySQL"));
        new WpFlywayMigrationConfiguration().customize(config);
        var captured=org.mockito.ArgumentCaptor.forClass(JavaMigration[].class);
        verify(config).javaMigrations(captured.capture());
        assertThat(captured.getValue()).hasSize(1);
        assertThat(captured.getValue()[0].getVersion().toString()).isEqualTo("12");
        assertThat(captured.getValue()[0].getChecksum()).isEqualTo(2026091901);
    }
    @Test void mariaDbKeepsOriginalSqlAndChecksum() throws Exception {
        var config = configuration(source("MariaDB"));
        new WpFlywayMigrationConfiguration().customize(config);
        verify(config).locations(WpFlywayMigrationConfiguration.COMMON,WpFlywayMigrationConfiguration.MARIADB);
        verify(config,never()).javaMigrations(any(JavaMigration[].class));
        String name="V12__folio_product_analytics_schema_v4.sql";
        assertThat(getClass().getClassLoader().getResource("db/wp/migration/"+name)).isNull();
        try(var in=getClass().getClassLoader().getResourceAsStream("db/wp/vendor/mariadb/"+name)) {
            assertThat(in).isNotNull();
            CRC32 crc=new CRC32();
            new String(in.readAllBytes(),StandardCharsets.UTF_8).lines()
                    .forEach(line->crc.update(line.getBytes(StandardCharsets.UTF_8)));
            assertThat((int)crc.getValue()).isEqualTo(-1886659825);
        }
    }
    @Test void unknownEngineFailsClosed() throws Exception {
        var config=configuration(source("PostgreSQL"));
        assertThatThrownBy(()->new WpFlywayMigrationConfiguration().customize(config)).hasMessageContaining("only MySQL or MariaDB");
    }
    @Test void mariaDbWithMysqlMetadataKeepsSqlV12NotJavaV12() throws Exception {
        var config=configuration(source("MySQL", "10.6.19-MariaDB", "mariadb.org binary distribution"));
        new WpFlywayMigrationConfiguration().customize(config);
        verify(config).locations(WpFlywayMigrationConfiguration.COMMON,WpFlywayMigrationConfiguration.MARIADB);
        verify(config,never()).javaMigrations(any(JavaMigration[].class));
    }
    @Test void serverCommentIdentifiesMariaDbWithCustomVersionString() throws Exception {
        var config=configuration(source("MySQL", "10.6.19", "MariaDB Server"));
        new WpFlywayMigrationConfiguration().customize(config);
        verify(config).locations(WpFlywayMigrationConfiguration.COMMON,WpFlywayMigrationConfiguration.MARIADB);
        verify(config,never()).javaMigrations(any(JavaMigration[].class));
    }
    @Test void unidentifiableMaskedServerFailsClosed() throws Exception {
        var config=configuration(source("MySQL", "10.6.19", "Source distribution"));
        assertThatThrownBy(()->new WpFlywayMigrationConfiguration().customize(config)).hasMessageContaining("selection blocked");
        verify(config,never()).javaMigrations(any(JavaMigration[].class));
    }
    @Test void failedIdentityReadNeverFallsBackToMisleadingMetadata() throws Exception {
        var ds=source("MySQL");
        when(ds.getConnection().createStatement().executeQuery(anyString())).thenThrow(new SQLException("test failure"));
        var config=configuration(ds);
        assertThatThrownBy(()->new WpFlywayMigrationConfiguration().customize(config)).hasMessageContaining("Cannot identify");
        verify(config,never()).javaMigrations(any(JavaMigration[].class));
        verify(ds.getConnection()).close();
    }
    @Test void emptyIdentityReadFailsClosedAndClosesResources() throws Exception {
        var ds=source("MySQL", "", "MySQL Community Server");
        var config=configuration(ds);
        assertThatThrownBy(()->new WpFlywayMigrationConfiguration().customize(config)).hasMessageContaining("empty version");
        verify(ds.getConnection().createStatement()).setQueryTimeout(10);
        verify(ds.getConnection().createStatement()).close();
        verify(ds.getConnection().createStatement().executeQuery("SELECT VERSION(), @@version_comment")).close();
        verify(ds.getConnection()).close();
    }
    @Test void unrelatedLocationsAreNotChangedOrConnected() {
        DataSource ds=mock(DataSource.class);
        var config=configuration(ds);
        when(config.getLocations()).thenReturn(new Location[]{new Location("classpath:unrelated")});
        new WpFlywayMigrationConfiguration().customize(config);
        verifyNoInteractions(ds);
    }
    private FluentConfiguration configuration(DataSource ds) {
        var cfg=mock(FluentConfiguration.class);
        when(cfg.getLocations()).thenReturn(new Location[]{new Location(WpFlywayMigrationConfiguration.COMMON)});
        when(cfg.getDataSource()).thenReturn(ds);
        when(cfg.getJavaMigrations()).thenReturn(new JavaMigration[0]);
        return cfg;
    }
    private DataSource source(String product) throws Exception {
        return source(product, "MariaDB".equals(product) ? "10.6.19-MariaDB" : "8.0.35", "Source distribution");
    }
    private DataSource source(String product, String version, String comment) throws Exception {
        DataSource ds=mock(DataSource.class); Connection c=mock(Connection.class); DatabaseMetaData md=mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(c); when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn(product);
        when(md.getURL()).thenReturn("jdbc:mariadb://localhost/test");
        Statement st=mock(Statement.class); ResultSet rs=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(st);
        when(st.executeQuery("SELECT VERSION(), @@version_comment")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(version); when(rs.getString(2)).thenReturn(comment);
        return ds;
    }
}
