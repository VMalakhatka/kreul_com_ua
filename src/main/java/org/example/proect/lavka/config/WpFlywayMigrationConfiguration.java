package org.example.proect.lavka.config;

import org.example.proect.lavka.config.migration.V12__folio_product_analytics_schema_v4;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Route only WP V12 by the actual server, not by the MariaDB JDBC driver's URL. */
@Configuration(proxyBeanMethods = false)
public class WpFlywayMigrationConfiguration implements FlywayConfigurationCustomizer {
    public static final String COMMON = "classpath:db/wp/migration";
    public static final String MARIADB = "classpath:db/wp/vendor/mariadb";

    @Override
    public void customize(FluentConfiguration configuration) {
        var locations = Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toList();
        if (!locations.contains(COMMON)) return; // Do not alter unrelated/test migration sets.
        if (!locations.equals(java.util.List.of(COMMON)))
            throw new IllegalStateException("WP Flyway expects the canonical common location only; vendor selection is automatic");
        try (var connection = configuration.getDataSource().getConnection()) {
            String product = databaseEngine(connection);
            if ("MariaDB".equalsIgnoreCase(product)) {
                configuration.locations(COMMON, MARIADB);
            } else if ("MySQL".equalsIgnoreCase(product)) {
                var migrations = new ArrayList<JavaMigration>(Arrays.asList(configuration.getJavaMigrations()));
                migrations.add(new V12__folio_product_analytics_schema_v4());
                configuration.javaMigrations(migrations.toArray(JavaMigration[]::new));
            } else {
                throw new IllegalStateException("WP Flyway supports only MySQL or MariaDB");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot identify WP database engine for Flyway", ex);
        }
    }

    /** Connector/J useMysqlMetadata=true masks MariaDB's product name, not VERSION(). */
    static String databaseEngine(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (!"MariaDB".equalsIgnoreCase(product) && !"MySQL".equalsIgnoreCase(product))
            throw new IllegalStateException("WP Flyway supports only MySQL or MariaDB");
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var result = statement.executeQuery("SELECT VERSION(), @@version_comment")) {
                if (!result.next()) throw new IllegalStateException("WP database engine identification returned no row");
                String version = result.getString(1);
                String comment = result.getString(2);
                if (version == null || version.isBlank())
                    throw new IllegalStateException("WP database engine identification returned an empty version");
                if (version.toLowerCase(Locale.ROOT).contains("mariadb")
                        || (comment != null && comment.toLowerCase(Locale.ROOT).contains("mariadb")))
                    return "MariaDB";
                // Do not accidentally route a vendor-masked MariaDB 10+ through Java V12.
                if ("MySQL".equalsIgnoreCase(product) && version.matches("[589]\\..*")) return "MySQL";
                throw new IllegalStateException("WP database engine could not be identified safely; V12 selection blocked");
            }
        }
    }
}
