package org.example.folioruslab.accounting;

import org.example.folioruslab.config.FolioRusProperties;
import org.example.folioruslab.config.LabProperties;
import org.example.folioruslab.db.DatabaseFingerprint;
import org.example.folioruslab.db.DatabaseGuard;
import org.example.folioruslab.db.SqlConnectionProvider;
import org.springframework.stereotype.Component;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

@Component
final class JdbcSafeAccountingPriceGateway implements SafeAccountingPriceGateway {

    private static final String PROCEDURE = "dbo.LAVKA_I_UCHET_TOVAR_SAFE";
    private static final String CALL_SQL = "{? = call " + PROCEDURE
            + "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)}";

    private final SqlConnectionProvider connectionProvider;
    private final DatabaseGuard databaseGuard;
    private final int queryTimeoutSeconds;

    JdbcSafeAccountingPriceGateway(
            SqlConnectionProvider connectionProvider,
            DatabaseGuard databaseGuard,
            LabProperties labProperties
    ) {
        this.connectionProvider = connectionProvider;
        this.databaseGuard = databaseGuard;
        this.queryTimeoutSeconds = labProperties.getMaximumTimeoutSeconds();
    }

    @Override
    public PreviewSession open(int warehouseId) {
        Connection connection = null;
        try {
            connection = connectionProvider.open();
            DatabaseFingerprint fingerprint = databaseGuard.verify(connection);
            if (!FolioRusProperties.EXPECTED_DATABASE.equals(fingerprint.databaseName())) {
                throw new SafeAccountingPriceException("The connection is not Paint_Rus");
            }
            verifyProcedure(connection);
            verifyWarehouseMode(connection, warehouseId);
            PreviewScope scope = new PreviewScope(
                    warehouseId, loadSkus(connection, warehouseId)
            );
            return new JdbcPreviewSession(connection, scope);
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw new SafeAccountingPriceException(
                    "Could not prepare the safe accounting-price preview", exception
            );
        } catch (RuntimeException exception) {
            closeQuietly(connection);
            throw exception;
        }
    }

    private SkuPreview previewOne(Connection connection, int warehouseId, String sku) {
        try {
            beginTransaction(connection);
            verifyTransactionCount(connection, 1, "before procedure");
            SkuPreview result = callProcedure(connection, warehouseId, sku);
            verifyTransactionCount(connection, 1, "after procedure");
            rollbackAndVerify(connection);
            return result;
        } catch (Exception exception) {
            rollbackAfterFailure(connection, exception);
            if (exception instanceof SafeAccountingPriceException safety) {
                throw safety;
            }
            throw new SafeAccountingPriceException(
                    "Safe accounting-price preview failed for SKU " + sku, exception
            );
        }
    }

    private void verifyProcedure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT OBJECT_ID('" + PROCEDURE + "')"
             )) {
            if (!resultSet.next() || resultSet.getObject(1) == null) {
                throw new SafeAccountingPriceException(
                        "Safe procedure " + PROCEDURE + " is not installed"
                );
            }
        }
    }

    private void verifyWarehouseMode(Connection connection, int warehouseId) throws SQLException {
        String sql = "SELECT N_2,N_4 FROM dbo.SCLAD_R WHERE ID_SCLAD=" + warehouseId;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SafeAccountingPriceException(
                        "Folio warehouse was not found: " + warehouseId
                );
            }
            double rawMode = resultSet.getDouble(1);
            boolean rawModeNull = resultSet.wasNull();
            resultSet.getDouble(2);
            boolean groupNull = resultSet.wasNull();
            if (rawModeNull || Math.abs(rawMode - 1000.0d) > 0.0000001d || !groupNull) {
                throw new SafeAccountingPriceException(
                        "Safe preview currently requires N_2=1000 and N_4 IS NULL"
                );
            }
        }
    }

    private List<String> loadSkus(Connection connection, int warehouseId) throws SQLException {
        List<String> skus = new ArrayList<>();
        String sql = "SELECT COD_ARTIC FROM dbo.SCL_ARTC WHERE ID_SCLAD="
                + warehouseId + " ORDER BY COD_ARTIC";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setFetchSize(500);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    String sku = resultSet.getString(1);
                    if (sku != null && !sku.isBlank()) {
                        skus.add(sku);
                    }
                }
            }
        }
        return skus;
    }

    private SkuPreview callProcedure(Connection connection, int warehouseId, String sku)
            throws SQLException {
        try (CallableStatement statement = connection.prepareCall(CALL_SQL)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setNull(2, Types.INTEGER);
            statement.setInt(3, warehouseId);
            statement.setBoolean(4, false);
            statement.setInt(5, 0);
            statement.setInt(6, 0);
            statement.setBoolean(7, false);
            setInOutString(statement, 8, sku, Types.VARCHAR);
            setInOutInt(statement, 9, 0);
            setInOutInt(statement, 10, 0);
            setInOutString(statement, 11, null, Types.VARCHAR);
            setInOutString(statement, 12, null, Types.CHAR);
            registerNullableOut(statement, 13, Types.VARCHAR);
            registerNullableOut(statement, 14, Types.VARCHAR);
            registerNullableOut(statement, 15, Types.INTEGER);
            registerNullableOut(statement, 16, Types.TIMESTAMP);
            registerNullableOut(statement, 17, Types.VARCHAR);
            registerNullableOut(statement, 18, Types.DOUBLE);
            registerNullableOut(statement, 19, Types.DOUBLE);
            registerNullableOut(statement, 20, Types.DOUBLE);
            registerNullableOut(statement, 21, Types.DOUBLE);

            drain(statement);

            int returnCode = statement.getInt(1);
            String processedSku = statement.getString(8);
            String nextSku = statement.getString(11);
            String negativeDate = trimToNull(statement.getString(12));
            String problemCode = trimToNull(statement.getString(13));
            if (processedSku == null || !processedSku.equals(sku)) {
                throw new SafeAccountingPriceException(
                        "Safe Folio procedure returned an unexpected SKU for " + sku
                );
            }
            SafeAccountingPriceProblem problem = null;
            if (problemCode != null) {
                problem = new SafeAccountingPriceProblem(
                        problemCode,
                        "Folio safely stopped this SKU before dividing by zero",
                        valueOrDefault(statement.getString(14), processedSku),
                        nextSku,
                        nullableInt(statement, 15),
                        nullableTimestamp(statement, 16),
                        trimToNull(statement.getString(17)),
                        nullableDouble(statement, 18),
                        nullableDouble(statement, 19),
                        nullableDouble(statement, 20),
                        nullableDouble(statement, 21),
                        negativeDate
                );
            } else if (negativeDate != null) {
                problem = new SafeAccountingPriceProblem(
                        "NEGATIVE_CHRONOLOGICAL_STOCK",
                        "Folio reported a negative chronological stock",
                        processedSku,
                        nextSku,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        negativeDate
                );
            }
            if (returnCode != 0 && returnCode != 20) {
                throw new SafeAccountingPriceException(
                        "Safe Folio procedure returned unsupported code " + returnCode
                                + " for SKU " + sku
                );
            }
            if (returnCode == 20 && problem == null) {
                throw new SafeAccountingPriceException(
                        "Safe Folio procedure returned code 20 without diagnostics for SKU " + sku
                );
            }
            return new SkuPreview(processedSku, nextSku, returnCode, negativeDate, problem);
        }
    }

    private static void drain(CallableStatement statement) throws SQLException {
        boolean resultSetAvailable = statement.execute();
        while (true) {
            if (resultSetAvailable) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    while (resultSet.next()) {
                        // The safe procedure currently returns only OUT parameters.
                    }
                }
            } else if (statement.getUpdateCount() == -1) {
                break;
            }
            resultSetAvailable = statement.getMoreResults();
        }
    }

    private static void setInOutString(
            CallableStatement statement, int index, String value, int sqlType
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, sqlType);
        } else {
            statement.setString(index, value);
        }
        statement.registerOutParameter(index, sqlType);
    }

    private static void setInOutInt(CallableStatement statement, int index, int value)
            throws SQLException {
        statement.setInt(index, value);
        statement.registerOutParameter(index, Types.INTEGER);
    }

    private static void registerNullableOut(
            CallableStatement statement, int index, int sqlType
    ) throws SQLException {
        statement.setNull(index, sqlType);
        statement.registerOutParameter(index, sqlType);
    }

    private static Integer nullableInt(CallableStatement statement, int index)
            throws SQLException {
        int value = statement.getInt(index);
        return statement.wasNull() ? null : value;
    }

    private static Double nullableDouble(CallableStatement statement, int index)
            throws SQLException {
        double value = statement.getDouble(index);
        return statement.wasNull() ? null : value;
    }

    private static String nullableTimestamp(CallableStatement statement, int index)
            throws SQLException {
        Timestamp value = statement.getTimestamp(index);
        return value == null ? null : value.toLocalDateTime().toString();
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        String normalized = trimToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static void beginTransaction(Connection connection) throws SQLException {
        executeControl(connection, "BEGIN TRANSACTION");
    }

    private static void rollbackAndVerify(Connection connection) throws SQLException {
        executeControl(connection, "ROLLBACK TRANSACTION");
        verifyTransactionCount(connection, 0, "after rollback");
    }

    private static void rollbackAfterFailure(Connection connection, Exception original) {
        try {
            int count = transactionCount(connection);
            if (count > 0) {
                executeControl(connection, "ROLLBACK TRANSACTION");
            }
            verifyTransactionCount(connection, 0, "after failure rollback");
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            throw new SafeAccountingPriceException(
                    "Rollback outcome is unknown; stop the Paint_Rus preview", original
            );
        }
    }

    private static void verifyTransactionCount(
            Connection connection, int expected, String stage
    ) throws SQLException {
        int actual = transactionCount(connection);
        if (actual != expected) {
            throw new SafeAccountingPriceException(
                    "Unexpected transaction count " + actual + " " + stage
            );
        }
    }

    private static int transactionCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@TRANCOUNT")) {
            if (!resultSet.next()) {
                throw new SQLException("@@TRANCOUNT returned no row");
            }
            return resultSet.getInt(1);
        }
    }

    private static void executeControl(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A setup failure is already being reported; no transaction has started yet.
        }
    }

    private final class JdbcPreviewSession implements PreviewSession {
        private final Connection connection;
        private final PreviewScope scope;
        private boolean closed;

        private JdbcPreviewSession(Connection connection, PreviewScope scope) {
            this.connection = connection;
            this.scope = scope;
        }

        @Override
        public PreviewScope scope() {
            return scope;
        }

        @Override
        public SkuPreview previewOne(String sku) {
            if (closed) {
                throw new SafeAccountingPriceException("The preview session is closed");
            }
            return JdbcSafeAccountingPriceGateway.this.previewOne(
                    connection, scope.warehouseId(), sku
            );
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (transactionCount(connection) != 0) {
                    executeControl(connection, "ROLLBACK TRANSACTION");
                }
                verifyTransactionCount(connection, 0, "before connection close");
                connection.close();
            } catch (SQLException exception) {
                closeQuietly(connection);
                throw new SafeAccountingPriceException(
                        "Could not safely close the Paint_Rus preview session", exception
                );
            }
        }
    }
}
