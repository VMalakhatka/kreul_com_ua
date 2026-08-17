package org.example.folioruslab.db;

import org.example.folioruslab.config.FolioRusProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

@Component
public final class PaintRusDatabaseGuard implements DatabaseGuard {

    private static final String FINGERPRINT_SQL = """
            SELECT DB_NAME() AS database_name,
                   CONVERT(varchar(128), SERVERPROPERTY('ProductVersion')) AS product_version,
                   d.cmptlevel AS compatibility_level,
                   CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation')) AS database_collation,
                   CONVERT(int, COLLATIONPROPERTY(
                       CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation')),
                       'CodePage')) AS database_code_page,
                   @@TRANCOUNT AS transaction_count,
                   ISNULL(IS_SRVROLEMEMBER('sysadmin'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('serveradmin'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('setupadmin'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('securityadmin'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('processadmin'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('dbcreator'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('diskadmin'), 0)
                     + ISNULL(IS_SRVROLEMEMBER('bulkadmin'), 0) AS fixed_server_role_count
            FROM master.dbo.sysdatabases d
            WHERE d.name = DB_NAME()
            """;

    private static final String INSTANCE_TOPOLOGY_SQL = """
            SELECT
                (SELECT COUNT(*)
                 FROM master.dbo.sysdatabases d
                 WHERE d.name NOT IN ('master', 'tempdb', 'model', 'msdb', 'Paint_Rus'))
                    AS other_user_database_count,
                (SELECT COUNT(*)
                 FROM master.dbo.sysdatabases d
                 WHERE d.name NOT IN (
                     'master', 'tempdb', 'model', 'msdb', 'Paint_Rus', 'Northwind', 'pubs'
                 )
                   AND HAS_DBACCESS(d.name) = 1)
                    AS accessible_other_user_database_count,
                (SELECT COUNT(*)
                 FROM master.dbo.sysdatabases d
                 WHERE d.name IN ('Northwind', 'pubs')
                   AND HAS_DBACCESS(d.name) = 1)
                    AS accessible_allowed_demo_database_count,
                (SELECT COUNT(*)
                 FROM master.dbo.sysservers s
                 WHERE s.srvid <> 0)
                    AS linked_or_remote_server_count
            """;

    private static final String SERVER_CHAINING_SQL =
            "EXEC master.dbo.sp_configure N'Cross DB Ownership Chaining'";

    private static final String DATABASE_CHAINING_SQL =
            "EXEC master.dbo.sp_dboption N'Paint_Rus', N'db chaining'";

    @Override
    public DatabaseFingerprint verify(Connection connection) {
        try {
            DatabaseFingerprint base = readFingerprint(connection);
            InstanceTopology topology = readInstanceTopology(connection);
            CrossDatabaseChainingState chaining = readCrossDatabaseChainingState(connection);
            DatabaseFingerprint fingerprint = new DatabaseFingerprint(
                    base.databaseName(),
                    base.productVersion(),
                    base.compatibilityLevel(),
                    base.collation(),
                    base.codePage(),
                    base.transactionCount(),
                    base.fixedServerRoleCount(),
                    chaining.serverWideEnabled(),
                    chaining.databaseEnabled(),
                    topology.accessibleOtherUserDatabaseCount(),
                    topology.accessibleAllowedDemoDatabaseCount(),
                    topology.otherUserDatabaseCount(),
                    topology.linkedOrRemoteServerCount(),
                    Instant.now()
            );
            enforce(fingerprint);
            return fingerprint;
        } catch (SQLException exception) {
            throw new DatabaseGuardException(
                    "DATABASE_PREFLIGHT_FAILED",
                    "Paint_Rus preflight could not be completed",
                    exception
            );
        }
    }

    @Override
    public int readTransactionCount(Connection connection) throws SQLException {
        return readSessionState(connection).transactionCount();
    }

    @Override
    public DatabaseSessionState readSessionState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = querySessionState(statement)) {
            if (!resultSet.next()) {
                throw new SQLException("Database session state result is missing");
            }
            return new DatabaseSessionState(
                    resultSet.getString("database_name"),
                    resultSet.getInt("transaction_count")
            );
        }
    }

    private ResultSet querySessionState(Statement statement) throws SQLException {
        statement.setQueryTimeout(10);
        return statement.executeQuery(
                "SELECT DB_NAME() AS database_name, @@TRANCOUNT AS transaction_count"
        );
    }

    private DatabaseFingerprint readFingerprint(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery(FINGERPRINT_SQL)) {
                if (!resultSet.next()) {
                    throw new DatabaseGuardException(
                            "DATABASE_NOT_RESOLVED",
                            "The connected database was not found in the SQL Server catalog"
                    );
                }
                return new DatabaseFingerprint(
                        resultSet.getString("database_name"),
                        resultSet.getString("product_version"),
                        resultSet.getInt("compatibility_level"),
                        resultSet.getString("database_collation"),
                        resultSet.getInt("database_code_page"),
                        resultSet.getInt("transaction_count"),
                        resultSet.getInt("fixed_server_role_count"),
                        false,
                        false,
                        0,
                        0,
                        0,
                        0,
                        Instant.now()
                );
            }
        }
    }

    private InstanceTopology readInstanceTopology(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery(INSTANCE_TOPOLOGY_SQL)) {
                if (!resultSet.next()) {
                    throw new SQLException("SQL Server instance topology result is missing");
                }
                return new InstanceTopology(
                        resultSet.getInt("other_user_database_count"),
                        resultSet.getInt("accessible_other_user_database_count"),
                        resultSet.getInt("accessible_allowed_demo_database_count"),
                        resultSet.getInt("linked_or_remote_server_count")
                );
            }
        }
    }

    private CrossDatabaseChainingState readCrossDatabaseChainingState(Connection connection)
            throws SQLException {
        boolean serverWideEnabled;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery(SERVER_CHAINING_SQL)) {
                if (!resultSet.next()) {
                    throw new SQLException("Cross-database server option result is missing");
                }
                serverWideEnabled = resultSet.getInt("run_value") != 0;
            }
        }

        boolean databaseEnabled;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery(DATABASE_CHAINING_SQL)) {
                if (!resultSet.next()) {
                    throw new SQLException("Paint_Rus db chaining option result is missing");
                }
                databaseEnabled = parseDatabaseOption(resultSet.getString(2));
            }
        }
        return new CrossDatabaseChainingState(serverWideEnabled, databaseEnabled);
    }

    private static boolean parseDatabaseOption(String value) throws SQLException {
        if (value == null) {
            throw new SQLException("Paint_Rus db chaining option is null");
        }
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("ON")
                || normalized.equalsIgnoreCase("TRUE")
                || normalized.equals("1")) {
            return true;
        }
        if (normalized.equalsIgnoreCase("OFF")
                || normalized.equalsIgnoreCase("FALSE")
                || normalized.equals("0")) {
            return false;
        }
        throw new SQLException("Paint_Rus db chaining option has an unknown state");
    }

    void enforce(DatabaseFingerprint fingerprint) {
        if (!FolioRusProperties.EXPECTED_DATABASE.equals(fingerprint.databaseName())) {
            throw new DatabaseGuardException(
                    "WRONG_DATABASE",
                    "Connection is not attached to the exact Paint_Rus laboratory database"
            );
        }
        if (!isSqlServer2000Sp3OrLater(fingerprint.productVersion())) {
            throw new DatabaseGuardException(
                    "WRONG_SQL_SERVER_VERSION",
                    "The laboratory requires Microsoft SQL Server 2000 SP3 or later"
            );
        }
        if (fingerprint.compatibilityLevel() != 80) {
            throw new DatabaseGuardException(
                    "WRONG_COMPATIBILITY_LEVEL",
                    "Paint_Rus must use SQL Server compatibility level 80"
            );
        }
        if (fingerprint.codePage() != 1251) {
            throw new DatabaseGuardException(
                    "WRONG_CODE_PAGE",
                    "Paint_Rus must use code page 1251"
            );
        }
        if (!FolioRusProperties.EXPECTED_COLLATION.equals(fingerprint.collation())) {
            throw new DatabaseGuardException(
                    "WRONG_COLLATION",
                    "Paint_Rus must use the expected Ukrainian CP1251 collation"
            );
        }
        if (fingerprint.transactionCount() != 0) {
            throw new DatabaseGuardException(
                    "DIRTY_CONNECTION",
                    "A new laboratory connection unexpectedly has an open transaction"
            );
        }
        if (fingerprint.fixedServerRoleCount() != 0) {
            throw new DatabaseGuardException(
                    "SERVER_ROLE_NOT_ALLOWED",
                    "The laboratory SQL login must not belong to any fixed server role"
            );
        }
        if (fingerprint.hasCrossDatabaseOwnershipChaining()) {
            throw new DatabaseGuardException(
                    "CROSS_DATABASE_OWNERSHIP_CHAINING_NOT_ALLOWED",
                    "Cross-database ownership chaining must be disabled for the server and Paint_Rus"
            );
        }
        if (fingerprint.hasOtherUserDatabaseAccess()) {
            throw new DatabaseGuardException(
                    "OTHER_DATABASE_ACCESS_NOT_ALLOWED",
                    "The laboratory SQL login must not have access to another user database"
            );
        }
    }

    private static boolean isSqlServer2000Sp3OrLater(String productVersion) {
        if (productVersion == null) {
            return false;
        }
        String[] parts = productVersion.trim().split("\\.");
        if (parts.length < 3 || !parts[0].equals("8")) {
            return false;
        }
        try {
            return Integer.parseInt(parts[2]) >= 760;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private record CrossDatabaseChainingState(
            boolean serverWideEnabled,
            boolean databaseEnabled
    ) {
    }

    private record InstanceTopology(
            int otherUserDatabaseCount,
            int accessibleOtherUserDatabaseCount,
            int accessibleAllowedDemoDatabaseCount,
            int linkedOrRemoteServerCount
    ) {
    }
}
