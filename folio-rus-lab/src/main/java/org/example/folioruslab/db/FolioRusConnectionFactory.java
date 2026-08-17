package org.example.folioruslab.db;

import org.example.folioruslab.config.FolioRusProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

@Component
public final class FolioRusConnectionFactory implements SqlConnectionProvider {

    private final FolioRusProperties properties;
    private final String jdbcUrl;

    public FolioRusConnectionFactory(FolioRusProperties properties) {
        this.properties = properties;
        registerDriver();
        this.jdbcUrl = buildJdbcUrl(properties);
    }

    @Override
    public Connection open() throws SQLException {
        Properties credentials = new Properties();
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword());
        Connection connection = DriverManager.getConnection(jdbcUrl, credentials);
        connection.setReadOnly(false);
        return connection;
    }

    private static void registerDriver() {
        try {
            Class.forName("net.sourceforge.jtds.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("jTDS driver is not available", exception);
        }
    }

    private static String buildJdbcUrl(FolioRusProperties properties) {
        return String.format(
                Locale.ROOT,
                "jdbc:jtds:sqlserver://%s:%d/%s;TDS=8.0;charset=cp1251;"
                        + "loginTimeout=%d;socketTimeout=%d;socketKeepAlive=true;"
                        + "lastUpdateCount=false;prepareSQL=0;progName=FolioRusLab",
                properties.getHost(),
                properties.getPort(),
                FolioRusProperties.EXPECTED_DATABASE,
                properties.getLoginTimeoutSeconds(),
                properties.getSocketTimeoutSeconds()
        );
    }
}
