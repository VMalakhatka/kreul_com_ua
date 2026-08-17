package org.example.folioruslab.sql;

import org.example.folioruslab.config.FolioRusProperties;
import org.example.folioruslab.config.LabProperties;
import org.example.folioruslab.db.DatabaseFingerprint;
import org.example.folioruslab.db.DatabaseGuard;
import org.example.folioruslab.db.DatabaseSessionState;
import org.example.folioruslab.db.SqlConnectionProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExecutionServiceProtocolTest {

    @Test
    void drainsRowSetThenUpdateCountThenRowSetUntilEnd() {
        JdbcScript script = new JdbcScript();
        script.userResults = List.of(
                rows(List.of("first_value"), List.of(List.of("alpha"))),
                new UpdateCount(7),
                rows(List.of("second_value"), List.of(List.of(42)))
        );

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.ROLLED_BACK, response.state());
        assertEquals(List.of("ROWSET", "UPDATE_COUNT", "ROWSET"),
                response.results().stream().map(SqlResult::kind).toList());
        assertEquals(List.of(0, 1, 2),
                response.results().stream().map(SqlResult::ordinal).toList());
        assertEquals("alpha", response.results().get(0).rows().get(0).get(0));
        assertEquals(7, response.results().get(1).updateCount());
        assertEquals("42", response.results().get(2).rows().get(0).get(0));
        assertEquals(3, script.userGetMoreResultsCalls);
        assertTrue(indexOf(script.events, "user.result-set.close:0")
                < indexOf(script.events, "user.get-more-results:1"));
        assertTrue(indexOf(script.events, "user.result-set.close:2")
                < indexOf(script.events, "user.get-more-results:3"));
    }

    @Test
    void closesUserStatementBeforeRollbackAfterSqlException() {
        JdbcScript script = new JdbcScript();
        script.userException = new SQLException("synthetic execution failure", "42000", 50001);

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.SQL_FAILED_ROLLED_BACK, response.state());
        assertEquals(1, script.rollbackAttempts);
        assertTrue(indexOf(script.events, "user.cancel")
                < indexOf(script.events, "user.statement.close"));
        assertTrue(indexOf(script.events, "user.statement.close")
                < indexOf(script.events, "control.execute:ROLLBACK TRANSACTION"));
        assertTrue(indexOf(script.events, "control.statement.close:ROLLBACK TRANSACTION")
                < indexOf(script.events, "connection.close"));
    }

    @Test
    void preservesSqlErrorWhenSentinelContinuityCannotBeChecked() {
        JdbcScript script = new JdbcScript();
        script.userException = new SQLException("Divide by zero error encountered.", "22012", 8134);
        script.failSentinelRelease = true;

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.TX_BOUNDARY_BROKEN, response.state());
        assertEquals("SQL_EXECUTION_FAILED_TRANSACTION_CONTINUITY_UNKNOWN",
                response.error().code());
        assertEquals("22012", response.error().sqlState());
        assertEquals(8134, response.error().vendorCode());
        assertEquals(1, script.rollbackAttempts);
        assertTrue(response.warnings().contains("SQL_BATCH_DID_NOT_COMPLETE"));
    }

    @Test
    void closesResultSetAndUserStatementBeforeRollbackAfterOutputLimit() {
        JdbcScript script = new JdbcScript();
        script.userResults = List.of(rows(
                List.of("value"),
                List.of(List.of("first"), List.of("second"))
        ));

        SqlExecutionResponse response = service(script).execute(request(
                ExecutionMode.ROLLBACK, 1
        ));

        assertEquals(ExecutionState.OUTPUT_LIMIT_ABORTED, response.state());
        assertEquals(1, response.rowCount());
        assertEquals(1, script.rollbackAttempts);
        assertTrue(indexOf(script.events, "user.result-set.close:0")
                < indexOf(script.events, "user.cancel"));
        assertTrue(indexOf(script.events, "user.cancel")
                < indexOf(script.events, "user.statement.close"));
        assertTrue(indexOf(script.events, "user.statement.close")
                < indexOf(script.events, "control.execute:ROLLBACK TRANSACTION"));
    }

    @Test
    void reportsManagedBoundaryZeroWithoutInventingARollback() {
        JdbcScript script = new JdbcScript();
        script.transactionCountAfterUserBatch = 0;

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.TX_BOUNDARY_BROKEN, response.state());
        assertEquals(0, response.transactionAfter());
        assertEquals(0, script.rollbackAttempts);
        assertTrue(response.warnings().contains(
                "OUTER_TRANSACTION_DISAPPEARED_CHANGES_MAY_HAVE_PERSISTED"));
    }

    @Test
    void rollsBackOnceWhenManagedBatchLeavesNestedTransaction() {
        JdbcScript script = new JdbcScript();
        script.transactionCountAfterUserBatch = 2;

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.TX_BOUNDARY_BROKEN, response.state());
        assertEquals(0, response.transactionAfter());
        assertEquals(1, script.rollbackAttempts);
        assertTrue(response.warnings().contains("USER_SQL_LEFT_NESTED_TRANSACTIONS"));
        assertTrue(indexOf(script.events, "user.statement.close")
                < indexOf(script.events, "control.execute:ROLLBACK TRANSACTION"));
    }

    @Test
    void doesNotRetryFailedFinalRollback() {
        JdbcScript script = new JdbcScript();
        script.failRollback = true;

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.TX_BOUNDARY_BROKEN, response.state());
        assertNull(response.transactionAfter());
        assertEquals("ROLLBACK_OUTCOME_UNKNOWN", response.error().code());
        assertEquals(1, script.rollbackAttempts);
        assertEquals(0, script.commitAttempts);
    }

    @Test
    void doesNotRetryOrRollbackAfterUnknownCommitOutcome() {
        JdbcScript script = new JdbcScript();
        script.failCommit = true;

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.COMMIT));

        assertEquals(ExecutionState.COMMIT_OUTCOME_UNKNOWN, response.state());
        assertNull(response.transactionAfter());
        assertEquals("COMMIT_OUTCOME_UNKNOWN", response.error().code());
        assertEquals(1, script.commitAttempts);
        assertEquals(0, script.rollbackAttempts);
        assertTrue(response.warnings().contains("DO_NOT_RETRY_VERIFY_POSTCONDITIONS"));
    }

    @Test
    void detectsCommitAndReplacementTransactionEvenWhenTransactionCountReturnsToOne() {
        JdbcScript script = new JdbcScript();
        script.transactionCountAfterUserBatch = 1;
        script.loseSentinelAfterUserBatch = true;

        SqlExecutionResponse response = service(script).execute(request(ExecutionMode.ROLLBACK));

        assertEquals(ExecutionState.TX_BOUNDARY_BROKEN, response.state());
        assertEquals("ORIGINAL_TRANSACTION_CONTINUITY_LOST", response.error().code());
        assertEquals(1, script.rollbackAttempts);
        assertTrue(response.warnings().contains(
                "ORIGINAL_MANAGED_TRANSACTION_NOT_PROVEN_CHANGES_MAY_HAVE_PERSISTED"));
    }

    private static SqlExecutionService service(JdbcScript script) {
        SensitiveValueRedactor redactor = new SensitiveValueRedactor();
        SqlConnectionProvider connectionProvider = script::connection;
        DatabaseGuard guard = new FakeDatabaseGuard(script);
        return new SqlExecutionService(
                connectionProvider,
                guard,
                new SqlSafetyPolicy(),
                new JdbcValueNormalizer(redactor),
                redactor,
                laboratoryProperties()
        );
    }

    private static LabProperties laboratoryProperties() {
        LabProperties properties = new LabProperties();
        properties.setToken("unit-test-token-with-at-least-thirty-two-characters");
        return properties;
    }

    private static SqlExecutionRequest request(ExecutionMode mode) {
        return request(mode, null);
    }

    private static SqlExecutionRequest request(ExecutionMode mode, Integer maxRows) {
        boolean persistent = mode != ExecutionMode.ROLLBACK;
        return new SqlExecutionRequest(
                "SELECT lab_value",
                mode,
                persistent ? Boolean.TRUE : null,
                persistent ? FolioRusProperties.EXPECTED_DATABASE : null,
                null,
                maxRows,
                null
        );
    }

    private static RowSet rows(List<String> columns, List<List<Object>> values) {
        return new RowSet(columns, values);
    }

    private static int indexOf(List<String> events, String expected) {
        int index = events.indexOf(expected);
        assertTrue(index >= 0, () -> "Missing event " + expected + " in " + events);
        return index;
    }

    private static final class FakeDatabaseGuard implements DatabaseGuard {

        private final JdbcScript script;

        private FakeDatabaseGuard(JdbcScript script) {
            this.script = script;
        }

        @Override
        public DatabaseFingerprint verify(Connection connection) {
            return new DatabaseFingerprint(
                    FolioRusProperties.EXPECTED_DATABASE,
                    "8.00.0000",
                    80,
                    "SQL_Ukrainian_CP1251_CI_AS",
                    1251,
                    script.transactionCount,
                    0,
                    false,
                    false,
                    0,
                    0,
                    0,
                    0,
                    Instant.EPOCH
            );
        }

        @Override
        public DatabaseSessionState readSessionState(Connection connection) {
            return new DatabaseSessionState(
                    FolioRusProperties.EXPECTED_DATABASE,
                    script.transactionCount
            );
        }

        @Override
        public int readTransactionCount(Connection connection) {
            return script.transactionCount;
        }
    }

    private static final class JdbcScript {

        private List<BatchItem> userResults = List.of();
        private SQLException userException;
        private Integer transactionCountAfterUserBatch;
        private boolean failRollback;
        private boolean failCommit;
        private boolean sentinelHeld;
        private boolean loseSentinelAfterUserBatch;
        private boolean failSentinelRelease;
        private int transactionCount;
        private int rollbackAttempts;
        private int commitAttempts;
        private int userGetMoreResultsCalls;
        private final List<String> events = new ArrayList<>();

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, arguments) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "close" -> {
                    events.add("connection.close");
                    yield null;
                }
                case "isClosed" -> false;
                case "getWarnings" -> null;
                case "clearWarnings", "setReadOnly", "setAutoCommit" -> null;
                case "getAutoCommit" -> true;
                case "isWrapperFor" -> false;
                case "unwrap" -> throw new SQLException("Not a wrapper");
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            StatementHandler handler = new StatementHandler(this);
            return proxy(Statement.class, handler);
        }
    }

    private static final class StatementHandler implements InvocationHandler {

        private final JdbcScript script;
        private List<BatchItem> results = List.of();
        private int resultIndex;
        private boolean userStatement;
        private String executedSql = "not-executed";
        private boolean closed;

        private StatementHandler(JdbcScript script) {
            this.script = script;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            return switch (method.getName()) {
                case "execute" -> execute((String) arguments[0]);
                case "getResultSet" -> currentResultSet();
                case "getUpdateCount" -> currentUpdateCount();
                case "getMoreResults" -> getMoreResults();
                case "setQueryTimeout", "clearWarnings" -> null;
                case "getWarnings" -> null;
                case "cancel" -> {
                    script.events.add(eventPrefix() + ".cancel");
                    yield null;
                }
                case "close" -> {
                    if (!closed) {
                        closed = true;
                        script.events.add(closeEvent());
                    }
                    yield null;
                }
                case "isClosed" -> closed;
                case "isWrapperFor" -> false;
                case "unwrap" -> throw new SQLException("Not a wrapper");
                default -> defaultValue(method.getReturnType());
            };
        }

        private boolean execute(String sql) throws SQLException {
            executedSql = sql.trim();
            String normalized = executedSql.toUpperCase(Locale.ROOT);
            resultIndex = 0;
            results = List.of();
            if (normalized.startsWith("SET ")) {
                script.events.add("control.execute:" + executedSql);
                return false;
            }
            if (normalized.startsWith("BEGIN TRANSACTION")) {
                script.events.add("control.execute:" + executedSql);
                script.transactionCount = 1;
                return false;
            }
            if (normalized.contains("SP_GETAPPLOCK")) {
                script.events.add("control.execute:acquire-sentinel");
                script.sentinelHeld = true;
                results = List.of(rows(List.of("lock_result"), List.of(List.of(0))));
                return true;
            }
            if (normalized.contains("SP_RELEASEAPPLOCK")) {
                script.events.add("control.execute:release-sentinel");
                if (script.failSentinelRelease) {
                    throw new SQLException("synthetic sentinel release failure", "HY000", 50103);
                }
                int releaseResult = script.sentinelHeld ? 0 : -999;
                script.sentinelHeld = false;
                results = List.of(rows(List.of("lock_result"), List.of(List.of(releaseResult))));
                return true;
            }
            if (normalized.startsWith("ROLLBACK TRANSACTION")) {
                script.events.add("control.execute:" + executedSql);
                script.rollbackAttempts++;
                if (script.failRollback) {
                    throw new SQLException("synthetic rollback failure", "HY000", 50101);
                }
                script.transactionCount = 0;
                return false;
            }
            if (normalized.startsWith("COMMIT TRANSACTION")) {
                script.events.add("control.execute:" + executedSql);
                script.commitAttempts++;
                if (script.failCommit) {
                    throw new SQLException("synthetic commit failure", "HY000", 50102);
                }
                script.transactionCount = 0;
                return false;
            }

            userStatement = true;
            script.events.add("user.execute");
            if (script.userException != null) {
                throw script.userException;
            }
            results = script.userResults;
            if (script.transactionCountAfterUserBatch != null) {
                script.transactionCount = script.transactionCountAfterUserBatch;
            }
            if (script.loseSentinelAfterUserBatch) {
                script.sentinelHeld = false;
            }
            return currentIsResultSet();
        }

        private ResultSet currentResultSet() {
            if (!currentIsResultSet()) {
                return null;
            }
            return resultSet((RowSet) results.get(resultIndex), resultIndex, script.events);
        }

        private int currentUpdateCount() {
            if (resultIndex >= results.size()) {
                return -1;
            }
            BatchItem item = results.get(resultIndex);
            return item instanceof UpdateCount updateCount ? updateCount.value() : -1;
        }

        private boolean getMoreResults() {
            resultIndex++;
            if (userStatement) {
                script.userGetMoreResultsCalls++;
                script.events.add("user.get-more-results:" + resultIndex);
            }
            return currentIsResultSet();
        }

        private boolean currentIsResultSet() {
            return resultIndex < results.size() && results.get(resultIndex) instanceof RowSet;
        }

        private String eventPrefix() {
            return userStatement ? "user" : "control";
        }

        private String closeEvent() {
            return userStatement
                    ? "user.statement.close"
                    : "control.statement.close:" + executedSql;
        }
    }

    private static ResultSet resultSet(RowSet rowSet, int ordinal, List<String> events) {
        class Cursor {
            int row = -1;
            boolean closed;
        }
        Cursor cursor = new Cursor();
        ResultSetMetaData metadata = metadata(rowSet.columns());
        return proxy(ResultSet.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getMetaData" -> metadata;
            case "next" -> ++cursor.row < rowSet.values().size();
            case "getObject" -> rowSet.values().get(cursor.row).get((Integer) arguments[0] - 1);
            case "close" -> {
                if (!cursor.closed) {
                    cursor.closed = true;
                    events.add("user.result-set.close:" + ordinal);
                }
                yield null;
            }
            case "isClosed" -> cursor.closed;
            case "wasNull" -> false;
            case "isWrapperFor" -> false;
            case "unwrap" -> throw new SQLException("Not a wrapper");
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSetMetaData metadata(List<String> columns) {
        return proxy(ResultSetMetaData.class, (proxy, method, arguments) -> switch (method.getName()) {
            case "getColumnCount" -> columns.size();
            case "getColumnLabel", "getColumnName" -> columns.get((Integer) arguments[0] - 1);
            case "getColumnType" -> Types.VARCHAR;
            case "getColumnTypeName" -> "varchar";
            case "isNullable" -> ResultSetMetaData.columnNullableUnknown;
            case "isWrapperFor" -> false;
            case "unwrap" -> throw new SQLException("Not a wrapper");
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }

    private sealed interface BatchItem permits RowSet, UpdateCount {
    }

    private record RowSet(List<String> columns, List<List<Object>> values) implements BatchItem {
        private RowSet {
            columns = List.copyOf(columns);
            values = values.stream().map(List::copyOf).toList();
        }
    }

    private record UpdateCount(int value) implements BatchItem {
    }
}
