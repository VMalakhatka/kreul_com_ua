package org.example.folioruslab.sql;

import org.example.folioruslab.config.FolioRusProperties;
import org.example.folioruslab.config.LabProperties;
import org.example.folioruslab.db.DatabaseFingerprint;
import org.example.folioruslab.db.DatabaseSessionState;
import org.example.folioruslab.db.DatabaseGuard;
import org.example.folioruslab.db.SqlConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public final class SqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);
    private static final int MAXIMUM_WARNINGS = 20;

    private final SqlConnectionProvider connectionFactory;
    private final DatabaseGuard databaseGuard;
    private final SqlSafetyPolicy safetyPolicy;
    private final JdbcValueNormalizer valueNormalizer;
    private final SensitiveValueRedactor redactor;
    private final LabProperties labProperties;
    private final Semaphore singleExecution = new Semaphore(1, true);

    public SqlExecutionService(
            SqlConnectionProvider connectionFactory,
            DatabaseGuard databaseGuard,
            SqlSafetyPolicy safetyPolicy,
            JdbcValueNormalizer valueNormalizer,
            SensitiveValueRedactor redactor,
            LabProperties labProperties
    ) {
        this.connectionFactory = connectionFactory;
        this.databaseGuard = databaseGuard;
        this.safetyPolicy = safetyPolicy;
        this.valueNormalizer = valueNormalizer;
        this.redactor = redactor;
        this.labProperties = labProperties;
    }

    public SqlExecutionResponse execute(SqlExecutionRequest request) {
        ResolvedExecutionOptions options = ResolvedExecutionOptions.from(request, labProperties);
        safetyPolicy.validate(request.sql(), options.mode());
        if (!singleExecution.tryAcquire()) {
            throw new LabBusyException();
        }

        UUID runId = UUID.randomUUID();
        String managedTransactionSentinel = "FolioRusLabBoundary:" + runId.toString().replace("-", "");
        Instant startedAt = Instant.now();
        String sqlHash = sha256(request.sql());
        OutputBudget budget = new OutputBudget(options.maxRows(), options.maxBytes());
        List<SqlResult> results = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        ExecutionState state = ExecutionState.SQL_FAILED;
        SqlExecutionError error = null;
        int transactionBefore = 0;
        Integer transactionAfter = null;
        boolean userBatchStarted = false;

        log.info(
                "LAB_SQL_START runId={} mode={} sqlSha256={} timeoutSeconds={} maxRows={} maxBytes={}",
                runId, options.mode(), sqlHash, options.timeoutSeconds(),
                options.maxRows(), options.maxBytes()
        );

        try (Connection connection = connectionFactory.open()) {
            log.info("LAB_DB_CONNECTED runId={} expectedDatabase={}",
                    runId, FolioRusProperties.EXPECTED_DATABASE);
            DatabaseFingerprint fingerprint = databaseGuard.verify(connection);
            transactionBefore = fingerprint.transactionCount();
            log.info(
                    "LAB_PREFLIGHT_OK runId={} serverVersion={} compatibility={} codePage={} "
                            + "otherAccessibleDatabases={} allowedDemoDatabases={} "
                            + "otherDatabasesOnServer={} linkedServers={}",
                    runId,
                    fingerprint.productVersion(),
                    fingerprint.compatibilityLevel(),
                    fingerprint.codePage(),
                    fingerprint.accessibleOtherUserDatabaseCount(),
                    fingerprint.accessibleAllowedDemoDatabaseCount(),
                    fingerprint.otherUserDatabaseCount(),
                    fingerprint.linkedOrRemoteServerCount()
            );
            if (fingerprint.hasOtherUserDatabaseAccess()) {
                warnings.add("SQL_LOGIN_CAN_ACCESS_ANOTHER_USER_DATABASE");
            }
            if (fingerprint.hasAllowedDemoDatabaseAccess()) {
                warnings.add("SQL_LOGIN_CAN_ACCESS_ALLOWED_DEMO_DATABASES");
            }

            if (options.mode().isManaged()) {
                beginManagedTransaction(connection, managedTransactionSentinel);
                log.info("LAB_TRANSACTION_READY runId={} mode={} sentinel=ACQUIRED",
                        runId, options.mode());
            }

            userBatchStarted = true;
            ExecutionAttempt attempt = executeUserBatch(
                    connection, request.sql(), options, results, budget, warnings
            );
            log.info(
                    "LAB_BATCH_DRAINED runId={} successful={} resultCount={} rows={} estimatedBytes={}",
                    runId, attempt.successful(), results.size(), budget.rows(), budget.bytes()
            );
            logResultSummaries(runId, results);
            if (options.mode().isManaged()) {
                SentinelOutcome sentinel = releaseManagedTransactionSentinel(
                        connection, managedTransactionSentinel
                );
                log.info("LAB_SENTINEL_CHECK runId={} outcome={}", runId, sentinel);
                if (sentinel != SentinelOutcome.CONTINUOUS) {
                    warnings.add("ORIGINAL_MANAGED_TRANSACTION_NOT_PROVEN_CHANGES_MAY_HAVE_PERSISTED");
                    if (!attempt.successful()) {
                        warnings.add("SQL_BATCH_DID_NOT_COMPLETE");
                    }
                    CleanupOutcome cleanup = cleanupOpenTransaction(connection);
                    if (cleanup == CleanupOutcome.FAILED) {
                        warnings.add("ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
                    }
                    transactionAfter = readTransactionCountQuietly(connection);
                    state = ExecutionState.TX_BOUNDARY_BROKEN;
                    error = !attempt.successful() && attempt.sqlException() != null
                            ? sqlError(
                            sentinel == SentinelOutcome.LOST
                                    ? "SQL_EXECUTION_FAILED_TRANSACTION_CONTINUITY_LOST"
                                    : "SQL_EXECUTION_FAILED_TRANSACTION_CONTINUITY_UNKNOWN",
                            attempt.sqlException())
                            : new SqlExecutionError(
                            sentinel == SentinelOutcome.LOST
                                    ? "ORIGINAL_TRANSACTION_CONTINUITY_LOST"
                                    : "ORIGINAL_TRANSACTION_CONTINUITY_UNKNOWN",
                            null,
                            null,
                            "The original managed transaction no longer has its private continuity sentinel"
                    );
                    return finish(runId, state, options, startedAt, sqlHash, transactionBefore,
                            transactionAfter, budget, results, warnings, error);
                }
            }
            if (!attempt.successful()) {
                CleanupOutcome cleanup = cleanupOpenTransaction(connection);
                transactionAfter = readTransactionCountQuietly(connection);
                if (attempt.outputLimit() != null) {
                    warnings.add("RESULT_WAS_TRUNCATED_AND_CONNECTION_WAS_DISCARDED");
                    state = ExecutionState.OUTPUT_LIMIT_ABORTED;
                    error = new SqlExecutionError(
                            "OUTPUT_LIMIT_EXCEEDED", null, null, attempt.outputLimit().getMessage()
                    );
                } else {
                    state = options.mode().isManaged() && cleanup == CleanupOutcome.ROLLED_BACK
                            ? ExecutionState.SQL_FAILED_ROLLED_BACK
                            : ExecutionState.SQL_FAILED;
                    error = sqlError("SQL_EXECUTION_FAILED", attempt.sqlException());
                }
                if (cleanup == CleanupOutcome.FAILED) {
                    warnings.add("ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
                }
                if (options.mode().isManaged() && cleanup == CleanupOutcome.NO_TRANSACTION) {
                    warnings.add("MANAGED_TRANSACTION_DISAPPEARED_CHANGES_MAY_HAVE_PERSISTED");
                }
                if (options.mode() == ExecutionMode.SELF_MANAGED) {
                    warnings.add("SELF_MANAGED_EARLIER_COMMITS_MAY_HAVE_PERSISTED");
                }
                return finish(runId, state, options, startedAt, sqlHash, transactionBefore,
                        transactionAfter, budget, results, warnings, error);
            }

            DatabaseSessionState sessionState = databaseGuard.readSessionState(connection);
            transactionAfter = sessionState.transactionCount();
            if (!FolioRusProperties.EXPECTED_DATABASE.equals(sessionState.databaseName())) {
                warnings.add("DATABASE_CONTEXT_CHANGED");
                cleanupOpenTransaction(connection);
                transactionAfter = readTransactionCountQuietly(connection);
                state = ExecutionState.TX_BOUNDARY_BROKEN;
                error = new SqlExecutionError(
                        "DATABASE_CONTEXT_CHANGED", null, null,
                        "The SQL batch changed the fixed Paint_Rus database context"
                );
                return finish(runId, state, options, startedAt, sqlHash, transactionBefore,
                        transactionAfter, budget, results, warnings, error);
            }
            if (options.mode().isManaged()) {
                if (transactionAfter != 1) {
                    if (transactionAfter == 0) {
                        warnings.add("OUTER_TRANSACTION_DISAPPEARED_CHANGES_MAY_HAVE_PERSISTED");
                    } else {
                        warnings.add("USER_SQL_LEFT_NESTED_TRANSACTIONS");
                    }
                    CleanupOutcome cleanup = cleanupOpenTransaction(connection);
                    if (cleanup == CleanupOutcome.FAILED) {
                        warnings.add("ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
                    }
                    transactionAfter = readTransactionCountQuietly(connection);
                    state = ExecutionState.TX_BOUNDARY_BROKEN;
                    error = new SqlExecutionError(
                            "TRANSACTION_BOUNDARY_BROKEN", null, null,
                            "The SQL batch changed the managed transaction boundary"
                    );
                } else if (options.mode() == ExecutionMode.ROLLBACK) {
                    try {
                        executeControl(connection, "ROLLBACK TRANSACTION", 10);
                        transactionAfter = databaseGuard.readTransactionCount(connection);
                        state = transactionAfter == 0
                                ? ExecutionState.ROLLED_BACK
                                : ExecutionState.TX_BOUNDARY_BROKEN;
                        if (state == ExecutionState.TX_BOUNDARY_BROKEN) {
                            warnings.add("ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
                            error = new SqlExecutionError(
                                    "ROLLBACK_OUTCOME_UNKNOWN", null, null,
                                    "Rollback did not restore a clean transaction boundary"
                            );
                        }
                    } catch (SQLException rollbackException) {
                        transactionAfter = null;
                        state = ExecutionState.TX_BOUNDARY_BROKEN;
                        warnings.add("ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
                        error = new SqlExecutionError(
                                "ROLLBACK_OUTCOME_UNKNOWN",
                                rollbackException.getSQLState(),
                                rollbackException.getErrorCode(),
                                "The rollback outcome could not be verified"
                        );
                    }
                } else {
                    try {
                        executeControl(connection, "COMMIT TRANSACTION", 10);
                        transactionAfter = databaseGuard.readTransactionCount(connection);
                        state = transactionAfter == 0
                                ? ExecutionState.COMMITTED
                                : ExecutionState.TX_BOUNDARY_BROKEN;
                        if (state == ExecutionState.TX_BOUNDARY_BROKEN) {
                            warnings.add("COMMIT_COMPLETED_WITH_DIRTY_TRANSACTION_STATE");
                            error = new SqlExecutionError(
                                    "COMMIT_BOUNDARY_BROKEN", null, null,
                                    "Commit returned without a clean transaction boundary"
                            );
                        }
                    } catch (SQLException commitException) {
                        transactionAfter = null;
                        state = ExecutionState.COMMIT_OUTCOME_UNKNOWN;
                        warnings.add("DO_NOT_RETRY_VERIFY_POSTCONDITIONS");
                        error = new SqlExecutionError(
                                "COMMIT_OUTCOME_UNKNOWN",
                                commitException.getSQLState(),
                                commitException.getErrorCode(),
                                "The commit outcome could not be verified; do not retry"
                        );
                    }
                }
            } else if (transactionAfter == 0) {
                state = ExecutionState.SELF_MANAGED_COMPLETED;
            } else {
                warnings.add("SELF_MANAGED_SQL_LEFT_AN_OPEN_TRANSACTION");
                CleanupOutcome cleanup = cleanupOpenTransaction(connection);
                if (cleanup == CleanupOutcome.FAILED) {
                    warnings.add("ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
                }
                transactionAfter = readTransactionCountQuietly(connection);
                state = ExecutionState.TX_BOUNDARY_BROKEN;
                error = new SqlExecutionError(
                        "TRANSACTION_BOUNDARY_BROKEN", null, null,
                        "SELF_MANAGED SQL returned with an open transaction"
                );
            }

            collectConnectionWarningsQuietly(connection, warnings);
        } catch (SQLException sqlException) {
            state = ExecutionState.SQL_FAILED;
            warnings.add("DATABASE_OR_TRANSACTION_CONTROL_FAILED");
            if (userBatchStarted) {
                warnings.add("TRANSACTION_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED");
            }
            error = new SqlExecutionError(
                    "DATABASE_OR_TRANSACTION_CONTROL_FAILED",
                    sqlException.getSQLState(),
                    sqlException.getErrorCode(),
                    "The database connection or transaction control operation failed"
            );
        } finally {
            singleExecution.release();
        }

        return finish(runId, state, options, startedAt, sqlHash, transactionBefore,
                transactionAfter, budget, results, warnings, error);
    }

    private ExecutionAttempt executeUserBatch(
            Connection connection,
            String sql,
            ResolvedExecutionOptions options,
            List<SqlResult> results,
            OutputBudget budget,
            Set<String> warnings
    ) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(options.timeoutSeconds());
            try {
                executeAndDrain(statement, sql, results, budget, warnings);
                return ExecutionAttempt.success();
            } catch (OutputLimitExceededException limit) {
                cancelQuietly(statement);
                return ExecutionAttempt.outputLimit(limit);
            } catch (SQLException sqlException) {
                collectStatementWarningsQuietly(statement, warnings);
                cancelQuietly(statement);
                return ExecutionAttempt.sqlFailure(sqlException);
            }
        } catch (SQLException closeOrCreateException) {
            return ExecutionAttempt.sqlFailure(closeOrCreateException);
        }
    }

    private void executeAndDrain(
            Statement statement,
            String sql,
            List<SqlResult> results,
            OutputBudget budget,
            Set<String> warnings
    ) throws SQLException {
        boolean hasResultSet = statement.execute(sql);
        int ordinal = 0;
        while (true) {
            if (hasResultSet) {
                budget.addResult();
                try (ResultSet resultSet = statement.getResultSet()) {
                    results.add(readResultSet(ordinal++, resultSet, budget));
                }
            } else {
                int updateCount = statement.getUpdateCount();
                if (updateCount == -1) {
                    break;
                }
                budget.addResult();
                results.add(SqlResult.updateCount(ordinal++, updateCount));
            }
            collectWarnings(statement.getWarnings(), warnings);
            statement.clearWarnings();
            hasResultSet = statement.getMoreResults();
        }
        collectWarnings(statement.getWarnings(), warnings);
    }

    private SqlResult readResultSet(int ordinal, ResultSet resultSet, OutputBudget budget)
            throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<SqlColumn> columns = new ArrayList<>(columnCount);
        boolean[] sensitive = new boolean[columnCount];

        for (int index = 1; index <= columnCount; index++) {
            String label = metadata.getColumnLabel(index);
            String typeName = metadata.getColumnTypeName(index);
            sensitive[index - 1] = redactor.isSensitiveColumn(label);
            budget.addText(label);
            budget.addText(typeName);
            columns.add(new SqlColumn(
                    index,
                    label,
                    metadata.getColumnType(index),
                    typeName,
                    sensitive[index - 1]
            ));
        }

        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            budget.addRow();
            List<Object> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                budget.addCell();
                row.add(valueNormalizer.normalize(
                        resultSet.getObject(index), sensitive[index - 1], budget
                ));
            }
            rows.add(row);
        }
        return SqlResult.rowSet(ordinal, List.copyOf(columns), List.copyOf(rows));
    }

    private void beginManagedTransaction(Connection connection, String sentinelResource)
            throws SQLException {
        try {
            executeControl(connection, "SET XACT_ABORT ON", 10);
            executeControl(connection, "SET IMPLICIT_TRANSACTIONS OFF", 10);
            executeControl(connection, "BEGIN TRANSACTION FOLIO_LAB_OUTER", 10);
            DatabaseSessionState state = databaseGuard.readSessionState(connection);
            if (FolioRusProperties.EXPECTED_DATABASE.equals(state.databaseName())
                    && state.transactionCount() == 1) {
                int lockResult = executeForSingleInt(
                        connection,
                        "DECLARE @folio_lab_lock_result int "
                                + "EXEC @folio_lab_lock_result = sp_getapplock "
                                + "@Resource = N'" + sentinelResource + "', "
                                + "@LockMode = 'Exclusive', @LockOwner = 'Transaction', @LockTimeout = 0 "
                                + "SELECT @folio_lab_lock_result AS lock_result",
                        10
                );
                if (lockResult >= 0) {
                    return;
                }
                throw new SQLException("Could not acquire the managed transaction continuity sentinel");
            }
            throw new SQLException("Could not establish the managed transaction boundary");
        } catch (SQLException exception) {
            cleanupOpenTransaction(connection);
            throw exception;
        }
    }

    private SentinelOutcome releaseManagedTransactionSentinel(
            Connection connection,
            String sentinelResource
    ) {
        try {
            int releaseResult = executeForSingleInt(
                    connection,
                    "DECLARE @folio_lab_lock_result int "
                            + "EXEC @folio_lab_lock_result = sp_releaseapplock "
                            + "@Resource = N'" + sentinelResource + "', @LockOwner = 'Transaction' "
                            + "SELECT @folio_lab_lock_result AS lock_result",
                    10
            );
            return releaseResult >= 0 ? SentinelOutcome.CONTINUOUS : SentinelOutcome.LOST;
        } catch (SQLException ignored) {
            return SentinelOutcome.UNKNOWN;
        }
    }

    private static int executeForSingleInt(Connection connection, String sql, int timeoutSeconds)
            throws SQLException {
        Integer value = null;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            boolean hasResultSet = statement.execute(sql);
            while (true) {
                if (hasResultSet) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        while (resultSet.next()) {
                            if (value == null) {
                                Object raw = resultSet.getObject(1);
                                if (raw instanceof Number number) {
                                    value = number.intValue();
                                } else if (raw != null) {
                                    value = Integer.parseInt(raw.toString());
                                }
                            }
                        }
                    } catch (NumberFormatException exception) {
                        throw new SQLException("Control statement returned a non-integer result", exception);
                    }
                } else if (statement.getUpdateCount() == -1) {
                    break;
                }
                hasResultSet = statement.getMoreResults();
            }
        }
        if (value == null) {
            throw new SQLException("Control statement did not return an integer result");
        }
        return value;
    }

    private static void executeControl(Connection connection, String sql, int timeoutSeconds)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            boolean hasResultSet = statement.execute(sql);
            while (true) {
                if (hasResultSet) {
                    try (ResultSet ignored = statement.getResultSet()) {
                        while (ignored.next()) {
                            // Control statements are not expected to return rows, but drain safely.
                        }
                    }
                } else if (statement.getUpdateCount() == -1) {
                    break;
                }
                hasResultSet = statement.getMoreResults();
            }
        }
    }

    private CleanupOutcome cleanupOpenTransaction(Connection connection) {
        try {
            int before = databaseGuard.readTransactionCount(connection);
            if (before == 0) {
                return CleanupOutcome.NO_TRANSACTION;
            }
            executeControl(connection, "ROLLBACK TRANSACTION", 10);
            return databaseGuard.readTransactionCount(connection) == 0
                    ? CleanupOutcome.ROLLED_BACK
                    : CleanupOutcome.FAILED;
        } catch (SQLException ignored) {
            return CleanupOutcome.FAILED;
        }
    }

    private Integer readTransactionCountQuietly(Connection connection) {
        try {
            return databaseGuard.readTransactionCount(connection);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static void cancelQuietly(Statement statement) {
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // Closing the one-use physical connection is the final cancellation boundary.
        }
    }

    private void collectWarnings(SQLWarning first, Set<String> warnings) {
        SQLWarning warning = first;
        int seen = 0;
        while (warning != null && warnings.size() < MAXIMUM_WARNINGS && seen++ < 100) {
            warnings.add(redactor.sanitizeDiagnostic(warning.getMessage()));
            warning = warning.getNextWarning();
        }
    }

    private void collectStatementWarningsQuietly(Statement statement, Set<String> warnings) {
        try {
            collectWarnings(statement.getWarnings(), warnings);
        } catch (SQLException ignored) {
            warnings.add("SQL_WARNINGS_COULD_NOT_BE_READ");
        }
    }

    private void collectConnectionWarningsQuietly(Connection connection, Set<String> warnings) {
        try {
            collectWarnings(connection.getWarnings(), warnings);
        } catch (SQLException ignored) {
            warnings.add("CONNECTION_WARNINGS_COULD_NOT_BE_READ");
        }
    }

    private void logResultSummaries(UUID runId, List<SqlResult> results) {
        for (SqlResult result : results) {
            if ("ROWSET".equals(result.kind())) {
                log.info(
                        "LAB_RESULT runId={} ordinal={} kind=ROWSET columns={} rows={}",
                        runId, result.ordinal(), result.columns().size(), result.rows().size()
                );
            } else {
                log.info(
                        "LAB_RESULT runId={} ordinal={} kind=UPDATE_COUNT affectedRows={}",
                        runId, result.ordinal(), result.updateCount()
                );
            }
        }
    }

    private SqlExecutionError sqlError(String code, SQLException exception) {
        return new SqlExecutionError(
                code,
                exception.getSQLState(),
                exception.getErrorCode(),
                redactor.sanitizeDiagnostic(exception.getMessage())
        );
    }

    private SqlExecutionResponse finish(
            UUID runId,
            ExecutionState state,
            ResolvedExecutionOptions options,
            Instant startedAt,
            String sqlHash,
            int transactionBefore,
            Integer transactionAfter,
            OutputBudget budget,
            List<SqlResult> results,
            Set<String> warnings,
            SqlExecutionError error
    ) {
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        log.info(
                "Paint_Rus run completed: runId={}, sqlSha256={}, mode={}, state={}, rows={}, bytes={}, durationMs={}",
                runId, sqlHash, options.mode(), state, budget.rows(), budget.bytes(), durationMs
        );
        return new SqlExecutionResponse(
                runId,
                state,
                FolioRusProperties.EXPECTED_DATABASE,
                options.mode(),
                startedAt,
                durationMs,
                sqlHash,
                transactionBefore,
                transactionAfter,
                budget.rows(),
                budget.bytes(),
                List.copyOf(results),
                List.copyOf(warnings),
                error
        );
    }

    private static String sha256(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private enum CleanupOutcome {
        ROLLED_BACK,
        NO_TRANSACTION,
        FAILED
    }

    private enum SentinelOutcome {
        CONTINUOUS,
        LOST,
        UNKNOWN
    }

    private record ExecutionAttempt(
            OutputLimitExceededException outputLimit,
            SQLException sqlException
    ) {
        static ExecutionAttempt success() {
            return new ExecutionAttempt(null, null);
        }

        static ExecutionAttempt outputLimit(OutputLimitExceededException limit) {
            return new ExecutionAttempt(limit, null);
        }

        static ExecutionAttempt sqlFailure(SQLException exception) {
            return new ExecutionAttempt(null, exception);
        }

        boolean successful() {
            return outputLimit == null && sqlException == null;
        }
    }
}
