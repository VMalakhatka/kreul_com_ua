package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Repository
public class FolioAccountingPriceDao {

    public static final String TYPE_RECEIPT = "\u041f";
    public static final String TYPE_EXPENSE = "\u0420";
    public static final String TYPE_ACCOUNT = "\u0421";
    private static final String MUTEX_RESOURCE = "lavka|folio|accounting-price-recalculation";
    private static final String REBUILD_CALL = "{call dbo.i_uchet_add(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)}";
    private static final String NATIVE_FULL_CALL =
            "{? = call dbo.LAVKA_I_UCHET_TOVAR_SAFE(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)}";
    // CASE-based restore uses three bind parameters per SKU. Staying well
    // below SQL Server's 2100-parameter limit also keeps jTDS packets modest.
    private static final int NATIVE_QUARANTINE_BATCH_SIZE = 400;

    private final JdbcTemplate jdbc;

    public FolioAccountingPriceDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WarehouseScope findWarehouseScope(int warehouseId) {
        List<WarehouseRow> requestedRows = jdbc.query("""
                SELECT ID_SCLAD, NAME_SCLAD, N_2, N_4
                  FROM dbo.SCLAD_R
                 WHERE ID_SCLAD = ?
                """, (rs, rowNum) -> mapWarehouse(rs), warehouseId);
        if (requestedRows.size() != 1) {
            return null;
        }

        WarehouseRow requested = requestedRows.get(0);
        // i_uchet_add normalizes NULL N_4 to -1 and then uses the effective
        // value in its group predicates. Include those rows in the discovered
        // scope too, so an unusual N_4=-1 warehouse cannot be changed unseen.
        int effectiveGroup = requested.accountingGroup() == null
                ? -1
                : requested.accountingGroup();
        List<WarehouseRow> affected = jdbc.query("""
                SELECT ID_SCLAD, NAME_SCLAD, N_2, N_4
                  FROM dbo.SCLAD_R
                 WHERE N_4 = ?
                 ORDER BY ID_SCLAD
                """, (rs, rowNum) -> mapWarehouse(rs), effectiveGroup);
        if (affected.stream().noneMatch(row -> row.warehouseId() == warehouseId)) {
            List<WarehouseRow> withRequested = new ArrayList<>(affected);
            withRequested.add(requested);
            withRequested.sort(java.util.Comparator.comparingInt(WarehouseRow::warehouseId));
            affected = List.copyOf(withRequested);
        }
        return new WarehouseScope(requested, List.copyOf(affected));
    }

    public List<String> findSkus(int warehouseId) {
        return jdbc.query("""
                SELECT COD_ARTIC
                  FROM dbo.SCL_ARTC
                 WHERE ID_SCLAD = ?
                 ORDER BY COD_ARTIC
                """, (rs, rowNum) -> rs.getString(1).trim(), warehouseId);
    }

    /**
     * Returns the exact article order used by the native recalculation while
     * excluding product types which Folio itself does not calculate.
     */
    public List<String> findNativeEligibleSkus(int warehouseId,
                                               String startArt,
                                               int queryTimeoutSeconds) {
        String startPredicate = startArt == null
                ? ""
                : " AND a.COD_ARTIC >= ?";
        return jdbc.query("""
                SELECT a.COD_ARTIC
                  FROM dbo.SCL_ARTC a
                 WHERE a.ID_SCLAD = ?
                   %s
                   AND NOT EXISTS (
                       SELECT 1
                         FROM dbo.TIP_TOVR t
                        WHERE t.SIGNIFIC = a.TIP_TOVR
                          AND t.CHECK_SAVE = 0
                          AND t.SHOW_OSTATOK = 0
                   )
                 ORDER BY a.COD_ARTIC
                """.formatted(startPredicate), statement -> {
            statement.setQueryTimeout(Math.max(1, queryTimeoutSeconds));
            statement.setInt(1, warehouseId);
            if (startArt != null) {
                statement.setString(2, startArt);
            }
        }, (rs, rowNum) -> trim(rs.getString(1)));
    }

    public String currentDatabaseName() {
        return trim(jdbc.queryForObject("SELECT DB_NAME()", String.class));
    }

    public boolean safeNativeProceduresInstalled() {
        Integer installed = jdbc.queryForObject("""
                SELECT CASE
                         WHEN OBJECT_ID('dbo.LAVKA_I_UCHET_1_TOVAR_SAFE') IS NOT NULL
                          AND OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE') IS NOT NULL
                          AND (SELECT COUNT(*) FROM dbo.syscolumns
                                WHERE id = OBJECT_ID('dbo.LAVKA_I_UCHET_1_TOVAR_SAFE')) = 20
                          AND (SELECT COUNT(*) FROM dbo.syscolumns
                                WHERE id = OBJECT_ID('dbo.LAVKA_I_UCHET_TOVAR_SAFE')) = 20
                         THEN 1 ELSE 0
                       END
                """, Integer.class);
        return installed != null && installed == 1;
    }

    public ArithmeticSessionOptions readArithmeticSessionOptions() {
        return jdbc.queryForObject("""
                SELECT @@OPTIONS AS option_mask,
                       CASE WHEN @@OPTIONS & 8 = 8 THEN 1 ELSE 0 END AS ansi_warnings,
                       CASE WHEN @@OPTIONS & 64 = 64 THEN 1 ELSE 0 END AS arithabort,
                       CASE WHEN @@OPTIONS & 128 = 128 THEN 1 ELSE 0 END AS arithignore
                """, (rs, rowNum) -> new ArithmeticSessionOptions(
                rs.getInt("option_mask"),
                rs.getBoolean("ansi_warnings"),
                rs.getBoolean("arithabort"),
                rs.getBoolean("arithignore")));
    }

    public List<ArticleRow> findArticles(String sku,
                                         List<Integer> warehouseIds,
                                         boolean forUpdate) {
        if (warehouseIds.isEmpty()) {
            return List.of();
        }
        String lock = forUpdate ? " WITH (UPDLOCK, HOLDLOCK)" : "";
        String sql = """
                SELECT a.COD_ARTIC,
                       a.ID_SCLAD,
                       s.NAME_SCLAD,
                       a.NAME_ARTIC,
                       a.TIP_TOVR,
                       a.NACH_KOLCH,
                       a.KON_KOLCH,
                       a.REZ_KOLCH,
                       a.KOL_SUM,
                       a.UCHET_SUM,
                       a.UCHET_SMVL,
                       a.UCHET_CENA,
                       a.UCHET_VALT,
                       a.UCHET_0_C,
                       a.UCHET_0_VL,
                       (SELECT COUNT(*)
                          FROM dbo.TIP_TOVR t
                         WHERE t.SIGNIFIC = a.TIP_TOVR
                           AND t.CHECK_SAVE = 0
                           AND t.SHOW_OSTATOK = 0) AS HIDDEN_TYPE_COUNT
                  FROM dbo.SCL_ARTC a%s
                  JOIN dbo.SCLAD_R s ON s.ID_SCLAD = a.ID_SCLAD
                 WHERE a.COD_ARTIC = ?
                   AND a.ID_SCLAD IN (%s)
                 ORDER BY a.ID_SCLAD
                """.formatted(lock, placeholders(warehouseIds.size()));

        List<Object> args = new ArrayList<>();
        args.add(sku);
        args.addAll(warehouseIds);
        return jdbc.query(sql, (rs, rowNum) -> new ArticleRow(
                trim(rs.getString("COD_ARTIC")),
                rs.getInt("ID_SCLAD"),
                trim(rs.getString("NAME_SCLAD")),
                trim(rs.getString("NAME_ARTIC")),
                trim(rs.getString("TIP_TOVR")),
                decimal(rs, "NACH_KOLCH"),
                decimal(rs, "KON_KOLCH"),
                decimal(rs, "REZ_KOLCH"),
                decimal(rs, "KOL_SUM"),
                decimal(rs, "UCHET_SUM"),
                decimal(rs, "UCHET_SMVL"),
                decimal(rs, "UCHET_CENA"),
                decimal(rs, "UCHET_VALT"),
                decimal(rs, "UCHET_0_C"),
                decimal(rs, "UCHET_0_VL"),
                rs.getInt("HIDDEN_TYPE_COUNT") > 0
        ), args.toArray());
    }

    public Map<Integer, MovementTotals> findMovementTotals(String sku,
                                                           List<Integer> warehouseIds) {
        if (warehouseIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT ID_SCLAD,
                       COUNT(*) AS MOVEMENT_COUNT,
                       SUM(CASE TYPDOCM_PR
                               WHEN ? THEN ISNULL(KOLC_PREDM, 0)
                               WHEN ? THEN -ISNULL(KOLC_PREDM, 0)
                               ELSE 0
                           END) AS MOVEMENT_QUANTITY,
                       SUM(CASE TYPDOCM_PR
                               WHEN ? THEN ISNULL(SUM_UCHET, 0)
                               WHEN ? THEN -ISNULL(SUM_UCHET, 0)
                               ELSE 0
                           END) AS ACCOUNTING_AMOUNT,
                       SUM(CASE TYPDOCM_PR
                               WHEN ? THEN ISNULL(SUM_UCVAL, 0)
                               WHEN ? THEN -ISNULL(SUM_UCVAL, 0)
                               ELSE 0
                           END) AS ACCOUNTING_CURRENCY_AMOUNT
                  FROM dbo.SCL_MOVE
                 WHERE NAME_PREDM = ?
                   AND STND_UCHET = 1
                   AND TYPDOCM_PR IN (?, ?)
                   AND ID_SCLAD IN (%s)
                 GROUP BY ID_SCLAD
                """.formatted(placeholders(warehouseIds.size()));
        List<Object> args = new ArrayList<>();
        args.add(TYPE_RECEIPT);
        args.add(TYPE_EXPENSE);
        args.add(TYPE_RECEIPT);
        args.add(TYPE_EXPENSE);
        args.add(TYPE_RECEIPT);
        args.add(TYPE_EXPENSE);
        args.add(sku);
        args.add(TYPE_RECEIPT);
        args.add(TYPE_EXPENSE);
        args.addAll(warehouseIds);
        Map<Integer, MovementTotals> result = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> result.put(rs.getInt("ID_SCLAD"), new MovementTotals(
                rs.getInt("MOVEMENT_COUNT"),
                decimal(rs, "MOVEMENT_QUANTITY"),
                decimal(rs, "ACCOUNTING_AMOUNT"),
                decimal(rs, "ACCOUNTING_CURRENCY_AMOUNT")
        )), args.toArray());
        return result;
    }

    public List<MovementRow> findChronologicalMovements(String sku,
                                                        List<Integer> warehouseIds,
                                                        boolean holdLock) {
        if (warehouseIds.isEmpty()) {
            return List.of();
        }
        String lock = holdLock ? " WITH (UPDLOCK, HOLDLOCK)" : "";
        String sql = """
                SELECT RECNO,
                       UNICUM_NUM,
                       NUMDOCM_PR,
                       ID_SCLAD,
                       DATE_PREDM,
                       TYPDOCM_PR,
                       VOZVRAT_PR,
                       KOLC_PREDM
                  FROM dbo.SCL_MOVE%s
                 WHERE NAME_PREDM = ?
                   AND STND_UCHET = 1
                   AND TYPDOCM_PR IN (?, ?)
                   AND ID_SCLAD IN (%s)
                 ORDER BY DATE_PREDM,
                          CASE TYPDOCM_PR WHEN ? THEN 0 ELSE 1 END,
                          NUMDOCM_PR,
                          RECNO
                """.formatted(lock, placeholders(warehouseIds.size()));
        List<Object> args = new ArrayList<>();
        args.add(sku);
        args.add(TYPE_RECEIPT);
        args.add(TYPE_EXPENSE);
        args.addAll(warehouseIds);
        args.add(TYPE_RECEIPT);
        return jdbc.query(sql, (rs, rowNum) -> new MovementRow(
                rs.getLong("RECNO"),
                decimal(rs, "UNICUM_NUM"),
                decimalNullable(rs, "NUMDOCM_PR"),
                rs.getInt("ID_SCLAD"),
                timestamp(rs, "DATE_PREDM"),
                trim(rs.getString("TYPDOCM_PR")),
                rs.getBoolean("VOZVRAT_PR"),
                decimal(rs, "KOLC_PREDM")
        ), args.toArray());
    }

    public void acquireRecalculationMutex(int timeoutMs) {
        Integer result = jdbc.execute((Connection connection) -> {
            if (connection.getAutoCommit()) {
                throw new CannotAcquireLockException(
                        "Folio accounting-price mutex requires a managed JDBC transaction");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    DECLARE @rc int
                    -- With jTDS/SQL Server 2000, setAutoCommit(false) does not
                    -- necessarily create a server-side transaction before the
                    -- first statement. Transaction-owned application locks
                    -- require @@TRANCOUNT > 0, so start the Spring-managed
                    -- transaction explicitly when it has not materialised yet.
                    IF @@TRANCOUNT = 0
                        BEGIN TRANSACTION
                    EXEC @rc = sp_getapplock
                         @Resource = ?,
                         @LockMode = 'Exclusive',
                         @LockOwner = 'Transaction',
                         @LockTimeout = ?
                    SELECT @rc
                    """)) {
                statement.setString(1, MUTEX_RESOURCE);
                statement.setInt(2, Math.max(0, timeoutMs));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt(1) : -999;
                }
            }
        });
        if (result == null || result < 0) {
            throw new CannotAcquireLockException(
                    "Cannot acquire Folio accounting-price mutex (code=" + result + ")");
        }
    }

    public ProcedureOutput rebuildOne(String sku, int warehouseId, int queryTimeoutSeconds) {
        return jdbc.execute((Connection connection) -> {
            try (CallableStatement statement = connection.prepareCall(REBUILD_CALL)) {
                statement.setQueryTimeout(Math.max(1, queryTimeoutSeconds));
                statement.setString(1, sku);
                statement.setInt(2, warehouseId);
                statement.setDouble(3, 1D);
                statement.setDouble(4, 0D);
                statement.setDouble(5, 0D);
                statement.setDouble(6, 0D);
                statement.setDouble(7, 0D);
                statement.setNull(8, Types.TIMESTAMP);
                statement.setString(9, TYPE_RECEIPT);
                statement.setBoolean(10, true);
                statement.setBoolean(11, false);
                statement.setString(12, "");
                registerFloatInOut(statement, 13);
                registerFloatInOut(statement, 14);
                statement.setInt(15, 2);
                statement.setString(16, "");
                statement.setNull(17, Types.TIMESTAMP);

                boolean hasResult = statement.execute();
                while (hasResult || statement.getUpdateCount() != -1) {
                    hasResult = statement.getMoreResults();
                }
                return new ProcedureOutput(
                        statement.getBigDecimal(13),
                        statement.getBigDecimal(14)
                );
            }
        });
    }

    /**
     * Calls the guarded Folio accounting-price procedure for exactly one SKU.
     * It retains the native INOUT cursor contract while additionally returning
     * structured diagnostics before a zero denominator can reach SQL Server.
     * Every result set/update count must be drained before OUT values are read.
     */
    public NativeFullChunkOutput callNativeFullChunk(
            Integer accountingGroup,
            int warehouseId,
            int calculationMode,
            int periodMode,
            boolean includeTax,
            String startArt,
            int currentUnits,
            int totalUnits,
            int queryTimeoutSeconds) {
        return jdbc.execute((Connection connection) -> {
            int transactionCountBefore = transactionCount(connection);
            if (transactionCountBefore < 1) {
                throw new SQLException(
                        "I_UCHET_TOVAR requires an active caller transaction");
            }
            try (CallableStatement statement = connection.prepareCall(NATIVE_FULL_CALL)) {
                statement.setQueryTimeout(Math.max(1, queryTimeoutSeconds));
                statement.registerOutParameter(1, Types.INTEGER);
                if (accountingGroup == null) {
                    statement.setNull(2, Types.INTEGER);
                } else {
                    statement.setInt(2, accountingGroup);
                }
                statement.setInt(3, warehouseId);
                statement.setBoolean(4, false);
                statement.setInt(5, calculationMode);
                statement.setInt(6, periodMode);
                statement.setBoolean(7, includeTax);
                registerVarcharInOut(statement, 8, startArt);
                registerIntegerInOut(statement, 9, currentUnits);
                registerIntegerInOut(statement, 10, totalUnits);
                registerVarcharInOut(statement, 11, null);
                registerVarcharInOut(statement, 12, null);
                statement.registerOutParameter(13, Types.VARCHAR);
                statement.registerOutParameter(14, Types.VARCHAR);
                statement.registerOutParameter(15, Types.INTEGER);
                statement.registerOutParameter(16, Types.TIMESTAMP);
                statement.registerOutParameter(17, Types.VARCHAR);
                statement.registerOutParameter(18, Types.DOUBLE);
                statement.registerOutParameter(19, Types.DOUBLE);
                statement.registerOutParameter(20, Types.DOUBLE);
                statement.registerOutParameter(21, Types.DOUBLE);

                int resultRowCount = 0;
                boolean hasResult = statement.execute();
                while (hasResult || statement.getUpdateCount() != -1) {
                    if (hasResult) {
                        try (ResultSet resultSet = statement.getResultSet()) {
                            while (resultSet != null && resultSet.next()) {
                                resultRowCount++;
                            }
                        }
                    }
                    hasResult = statement.getMoreResults();
                }

                return new NativeFullChunkOutput(
                        nullableOutInteger(statement, 1),
                        trim(statement.getString(8)),
                        nullableOutInteger(statement, 9),
                        nullableOutInteger(statement, 10),
                        trim(statement.getString(11)),
                        trim(statement.getString(12)),
                        trim(statement.getString(13)),
                        trim(statement.getString(14)),
                        nullableOutInteger(statement, 15),
                        nullableOutDateTime(statement, 16),
                        trim(statement.getString(17)),
                        nullableOutDouble(statement, 18),
                        nullableOutDouble(statement, 19),
                        nullableOutDouble(statement, 20),
                        nullableOutDouble(statement, 21),
                        transactionCountBefore,
                        transactionCount(connection),
                        resultRowCount
                );
            }
        });
    }

    public List<NativeChronologyProblem> findNativeChronologyProblems(
            int warehouseId,
            int queryTimeoutSeconds) {
        List<NativeChronologyProblem> problems = new ArrayList<>();
        Map<String, BigDecimal> runningBySku = new LinkedHashMap<>();
        Map<String, Integer> movementCounts = new LinkedHashMap<>();
        Map<String, String> lastSortKeys = new HashMap<>();
        java.util.Set<String> reportedSkus = new java.util.HashSet<>();
        BigDecimal epsilon = new BigDecimal("0.00000000001");

        jdbc.query("""
                SELECT m.NAME_PREDM, m.RECNO, m.UNICUM_NUM, m.NUMDOCM_PR,
                       m.DATE_PREDM, m.TYPDOCM_PR, m.VOZVRAT_PR,
                       m.KOLC_PREDM, a.NACH_KOLCH, a.KON_KOLCH,
                       a.REZ_KOLCH, a.KOL_SUM, a.UCHET_CENA
                  FROM dbo.SCL_MOVE m
                  JOIN dbo.SCL_ARTC a
                    ON a.COD_ARTIC = m.NAME_PREDM
                   AND a.ID_SCLAD = m.ID_SCLAD
                 WHERE m.ID_SCLAD = ?
                   AND m.STND_UCHET = 1
                   AND m.TYPDOCM_PR <> ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM dbo.TIP_TOVR t
                        WHERE t.SIGNIFIC = a.TIP_TOVR
                          AND t.CHECK_SAVE = 0
                          AND t.SHOW_OSTATOK = 0
                   )
                 ORDER BY m.NAME_PREDM, m.DATE_PREDM,
                          m.TYPDOCM_PR, m.NUMDOCM_PR, m.RECNO
                """, statement -> {
            statement.setFetchSize(500);
            statement.setQueryTimeout(Math.max(600, queryTimeoutSeconds));
            statement.setInt(1, warehouseId);
            statement.setString(2, TYPE_ACCOUNT);
        }, rs -> {
            String sku = trim(rs.getString("NAME_PREDM"));
            int movementPosition = movementCounts.merge(sku, 1, Integer::sum);
            String sortKey = String.valueOf(rs.getTimestamp("DATE_PREDM"))
                    + '\u0000' + trim(rs.getString("TYPDOCM_PR"))
                    + '\u0000' + String.valueOf(rs.getBigDecimal("NUMDOCM_PR"));
            boolean ambiguousOrder = sortKey.equals(lastSortKeys.put(sku, sortKey));
            BigDecimal before = runningBySku.get(sku);
            if (before == null) {
                before = decimal(rs, "NACH_KOLCH");
            }
            BigDecimal quantity = decimal(rs, "KOLC_PREDM");
            String documentType = trim(rs.getString("TYPDOCM_PR"));
            BigDecimal after = TYPE_RECEIPT.equals(documentType)
                    ? before.add(quantity)
                    : before.subtract(quantity);
            runningBySku.put(sku, after);

            if (reportedSkus.contains(sku)) {
                return;
            }
            String code = null;
            if (ambiguousOrder) {
                code = "AMBIGUOUS_MOVEMENT_ORDER";
            } else if (TYPE_RECEIPT.equals(documentType)
                    && after.abs().compareTo(epsilon) <= 0) {
                code = "ZERO_ACCOUNTING_QUANTITY_DENOMINATOR";
            } else if (TYPE_EXPENSE.equals(documentType)
                    && (before.compareTo(BigDecimal.ZERO) <= 0
                    || after.compareTo(epsilon.negate()) < 0)) {
                code = "NEGATIVE_CHRONOLOGICAL_STOCK";
            }
            if (code == null) {
                return;
            }
            reportedSkus.add(sku);
            problems.add(new NativeChronologyProblem(
                    code, sku, warehouseId, rs.getLong("RECNO"),
                    decimal(rs, "UNICUM_NUM"), decimalNullable(rs, "NUMDOCM_PR"),
                    timestamp(rs, "DATE_PREDM"), documentType,
                    rs.getBoolean("VOZVRAT_PR"), quantity, before, after,
                    movementPosition, 0,
                    decimal(rs, "NACH_KOLCH"), decimal(rs, "KON_KOLCH"),
                    decimal(rs, "REZ_KOLCH"), decimal(rs, "KOL_SUM"),
                    decimal(rs, "UCHET_CENA")
            ));
        });
        return problems.stream()
                .map(problem -> problem.withMovementCount(
                        movementCounts.getOrDefault(problem.sku(), 0)))
                .toList();
    }

    public String findUnusedProductTypeMarker() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String value : jdbc.query("""
                SELECT TIP_TOVR AS value FROM dbo.SCL_ARTC
                UNION
                SELECT SIGNIFIC AS value FROM dbo.TIP_TOVR
                UNION
                SELECT TIP_TOVAR AS value FROM dbo.TIP_TOVR
                """, (rs, rowNum) -> trim(rs.getString(1)))) {
            if (value != null) {
                used.add(value.toUpperCase(Locale.ROOT));
            }
        }
        for (String candidate : List.of("9", "8", "7", "6", "5", "4", "3", "Z", "Y", "X")) {
            if (!used.contains(candidate.toUpperCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    public Map<String, String> quarantineNativeSkus(int warehouseId,
                                                     Set<String> skus,
                                                     String unusedProductTypeMarker) {
        Map<String, String> originalTypes = new LinkedHashMap<>();
        List<String> orderedSkus = new ArrayList<>(skus);
        for (int start = 0; start < orderedSkus.size(); start += NATIVE_QUARANTINE_BATCH_SIZE) {
            List<String> batch = orderedSkus.subList(
                    start, Math.min(start + NATIVE_QUARANTINE_BATCH_SIZE, orderedSkus.size()));
            String placeholders = placeholders(batch.size());
            int foundBefore = originalTypes.size();
            jdbc.query("""
                    SELECT COD_ARTIC, TIP_TOVR
                      FROM dbo.SCL_ARTC WITH (UPDLOCK, HOLDLOCK)
                     WHERE ID_SCLAD = ?
                       AND COD_ARTIC IN (""" + placeholders + ")", statement -> {
                statement.setInt(1, warehouseId);
                bindStrings(statement, 2, batch);
            }, (RowCallbackHandler) rs -> originalTypes.put(
                    trim(rs.getString("COD_ARTIC")), trim(rs.getString("TIP_TOVR"))));
            int found = originalTypes.size() - foundBefore;
            if (found != batch.size()) {
                throw new IllegalStateException(
                        "Cannot read all Folio products for quarantine in warehouse " + warehouseId
                                + ": expected=" + batch.size() + ", found=" + found);
            }

            int updated = jdbc.update("""
                    UPDATE dbo.SCL_ARTC
                       SET TIP_TOVR = ?
                     WHERE ID_SCLAD = ?
                       AND COD_ARTIC IN (""" + placeholders + ")", statement -> {
                statement.setString(1, unusedProductTypeMarker);
                statement.setInt(2, warehouseId);
                bindStrings(statement, 3, batch);
            });
            if (updated != batch.size()) {
                throw new IllegalStateException(
                        "Cannot quarantine all Folio products in warehouse " + warehouseId
                                + ": expected=" + batch.size() + ", updated=" + updated);
            }
        }
        return originalTypes;
    }

    public void createNativeQuarantineType(String marker) {
        int inserted = jdbc.update("""
                INSERT INTO dbo.TIP_TOVR
                       (SIGNIFIC, TIP_TOVAR, CHECK_SAVE, SHOW_OSTATOK)
                VALUES (?, ?, 0, 0)
                """, marker, marker);
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Cannot create temporary Folio quarantine product type");
        }
    }

    /**
     * Makes only one inclusive article range visible to I_UCHET_TOVAR.
     * This helper is used exclusively in an outer rollback transaction while
     * isolating a legacy arithmetic error, so the original product types are
     * restored by that rollback rather than by a compensating UPDATE.
     */
    public int quarantineNativeOutsideRange(int warehouseId,
                                            String firstArt,
                                            String lastArt,
                                            Set<String> additionallySkippedSkus,
                                            String marker) {
        int updated = jdbc.update("""
                UPDATE dbo.SCL_ARTC
                   SET TIP_TOVR = ?
                 WHERE ID_SCLAD = ?
                   AND (COD_ARTIC < ? OR COD_ARTIC > ?)
                """, marker, warehouseId, firstArt, lastArt);

        List<String> skipped = new ArrayList<>(additionallySkippedSkus);
        for (int start = 0; start < skipped.size(); start += NATIVE_QUARANTINE_BATCH_SIZE) {
            List<String> batch = skipped.subList(
                    start, Math.min(start + NATIVE_QUARANTINE_BATCH_SIZE, skipped.size()));
            String placeholders = placeholders(batch.size());
            updated += jdbc.update("""
                    UPDATE dbo.SCL_ARTC
                       SET TIP_TOVR = ?
                     WHERE ID_SCLAD = ?
                       AND COD_ARTIC IN (""" + placeholders + ")", statement -> {
                statement.setString(1, marker);
                statement.setInt(2, warehouseId);
                bindStrings(statement, 3, batch);
            });
        }
        return updated;
    }

    public void restoreNativeSkus(int warehouseId, Map<String, String> originalTypes) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(originalTypes.entrySet());
        for (int start = 0; start < entries.size(); start += NATIVE_QUARANTINE_BATCH_SIZE) {
            List<Map.Entry<String, String>> batch = entries.subList(
                    start, Math.min(start + NATIVE_QUARANTINE_BATCH_SIZE, entries.size()));
            StringBuilder cases = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                cases.append(" WHEN ? THEN ?");
            }
            String placeholders = placeholders(batch.size());
            int updated = jdbc.update("""
                    UPDATE dbo.SCL_ARTC
                       SET TIP_TOVR = CASE COD_ARTIC""" + cases + " ELSE TIP_TOVR END\n"
                    + " WHERE ID_SCLAD = ?\n"
                    + "   AND COD_ARTIC IN (" + placeholders + ")", statement -> {
                int parameter = 1;
                for (Map.Entry<String, String> entry : batch) {
                    statement.setString(parameter++, entry.getKey());
                    if (entry.getValue() == null) {
                        statement.setNull(parameter++, Types.VARCHAR);
                    } else {
                        statement.setString(parameter++, entry.getValue());
                    }
                }
                statement.setInt(parameter++, warehouseId);
                bindStrings(statement, parameter, batch.stream()
                        .map(Map.Entry::getKey)
                        .toList());
            });
            if (updated != batch.size()) {
                throw new IllegalStateException(
                        "Cannot restore all Folio products in warehouse " + warehouseId
                                + ": expected=" + batch.size() + ", updated=" + updated);
            }
        }
    }

    private static void bindStrings(PreparedStatement statement,
                                    int firstParameter,
                                    List<String> values) throws SQLException {
        int parameter = firstParameter;
        for (String value : values) {
            statement.setString(parameter++, value);
        }
    }

    public void deleteNativeQuarantineType(String marker) {
        int deleted = jdbc.update("""
                DELETE FROM dbo.TIP_TOVR
                 WHERE SIGNIFIC = ?
                   AND TIP_TOVAR = ?
                   AND CHECK_SAVE = 0
                   AND SHOW_OSTATOK = 0
                """, marker, marker);
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Cannot remove temporary Folio quarantine product type");
        }
    }

    public NativeProtectedSnapshot captureNativeProtectedSnapshot(
            int warehouseId,
            String startArt,
            String endArt) {
        String articleStart = startArt == null
                ? ""
                : " AND COD_ARTIC >= ?";
        String articleEnd = endArt == null
                ? ""
                : " AND COD_ARTIC <= ?";
        String movementStart = startArt == null
                ? ""
                : " AND m.NAME_PREDM >= ?";
        String movementEnd = endArt == null
                ? ""
                : " AND m.NAME_PREDM <= ?";

        List<Object> articleArgs = new ArrayList<>();
        articleArgs.add(warehouseId);
        if (startArt != null) {
            articleArgs.add(startArt);
        }
        if (endArt != null) {
            articleArgs.add(endArt);
        }
        Map<String, NativeInvariantDigest> articleDigests = new LinkedHashMap<>();
        List<String> orderedSkus = new ArrayList<>();
        streamQuery("""
                SELECT COD_ARTIC, ID_SCLAD, NACH_KOLCH, KON_KOLCH,
                       REZ_KOLCH, UCHET_0_C, UCHET_0_VL, TIP_TOVR
                  FROM dbo.SCL_ARTC WITH (UPDLOCK, HOLDLOCK)
                 WHERE ID_SCLAD = ?
                   %s%s
                 ORDER BY COD_ARTIC, ID_SCLAD
                """.formatted(articleStart, articleEnd),
                articleArgs.toArray(), rs -> {
                    String sku = trim(rs.getString(1));
                    MessageDigest digest = sha256();
                    updateDigestRow(digest, rs, 8);
                    orderedSkus.add(sku);
                    articleDigests.put(sku, new NativeInvariantDigest(
                            1, HexFormat.of().formatHex(digest.digest())));
                });

        List<Object> movementArgs = new ArrayList<>();
        movementArgs.add(warehouseId);
        if (startArt != null) {
            movementArgs.add(startArt);
        }
        if (endArt != null) {
            movementArgs.add(endArt);
        }
        Map<String, NativeInvariantDigest> movementDigests = new LinkedHashMap<>();
        String[] activeSku = {null};
        MessageDigest[] activeDigest = {null};
        int[] activeRows = {0};
        streamQuery("""
                SELECT m.RECNO, m.UNICUM_NUM, m.NUMDOCM_PR, m.NUM_PREDMT,
                       m.NAME_PREDM, m.ID_SCLAD, m.DATE_PREDM, m.TYPDOCM_PR,
                       m.STND_UCHET, m.VOZVRAT_PR, m.KOLC_PREDM, m.KOLTREB_PR,
                       m.CENA_PREDM, m.SUM_PREDM, m.VALUT_CENA, m.SUM_VALUT,
                       m.NALOGMONEY, m.NALOGVALUT, m.ORG_PREDM, m.PARTIA, m.SROK
                  FROM dbo.SCL_MOVE m WITH (UPDLOCK, HOLDLOCK)
                  JOIN dbo.SCL_ARTC a WITH (UPDLOCK, HOLDLOCK)
                    ON a.COD_ARTIC = m.NAME_PREDM
                   AND a.ID_SCLAD = m.ID_SCLAD
                 WHERE m.ID_SCLAD = ?
                   %s%s
                 ORDER BY m.NAME_PREDM, m.DATE_PREDM, m.TYPDOCM_PR,
                          m.NUMDOCM_PR, m.RECNO
                """.formatted(movementStart, movementEnd),
                movementArgs.toArray(), rs -> {
                    String sku = trim(rs.getString(5));
                    if (activeSku[0] == null || !activeSku[0].equals(sku)) {
                        finishDigest(activeSku[0], activeDigest[0], activeRows[0],
                                movementDigests);
                        activeSku[0] = sku;
                        activeDigest[0] = sha256();
                        activeRows[0] = 0;
                    }
                    updateDigestRow(activeDigest[0], rs, 21);
                    activeRows[0]++;
                });
        finishDigest(activeSku[0], activeDigest[0], activeRows[0], movementDigests);

        Map<String, NativeSkuProtectedState> states = new LinkedHashMap<>();
        NativeInvariantDigest empty = emptyDigest();
        for (String sku : orderedSkus) {
            states.put(sku, new NativeSkuProtectedState(
                    articleDigests.get(sku),
                    movementDigests.getOrDefault(sku, empty)));
        }
        return new NativeProtectedSnapshot(
                List.copyOf(orderedSkus), Map.copyOf(states));
    }

    private void streamQuery(String sql,
                             Object[] args,
                             RowCallbackHandler handler) {
        jdbc.query(sql, statement -> {
            statement.setFetchSize(500);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
        }, handler);
    }

    private static void updateDigestRow(MessageDigest digest,
                                        ResultSet rs,
                                        int columnCount) throws SQLException {
        for (int column = 1; column <= columnCount; column++) {
            String value = rs.getString(column);
            if (value == null) {
                digest.update((byte) 0);
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) 1);
                updateInt(digest, bytes.length);
                digest.update(bytes);
            }
        }
        digest.update((byte) 0x7f);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void finishDigest(String sku,
                                     MessageDigest digest,
                                     int rowCount,
                                     Map<String, NativeInvariantDigest> target) {
        if (sku == null || digest == null) {
            return;
        }
        target.put(sku, new NativeInvariantDigest(
                rowCount, HexFormat.of().formatHex(digest.digest())));
    }

    private static NativeInvariantDigest emptyDigest() {
        return new NativeInvariantDigest(
                0, HexFormat.of().formatHex(sha256().digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public boolean isArtAfter(int warehouseId,
                              String previousArt,
                              String nextArt) {
        if (previousArt == null || nextArt == null) {
            return false;
        }
        Integer result = jdbc.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                      FROM dbo.SCL_ARTC a
                     WHERE a.ID_SCLAD = ?
                       AND a.COD_ARTIC = ?
                       AND a.COD_ARTIC > ?
                ) THEN 1 ELSE 0 END
                """,
                Integer.class, warehouseId, nextArt, previousArt);
        return result != null && result == 1;
    }

    public boolean isArtAtOrAfter(int warehouseId,
                                  String firstArt,
                                  String candidateArt) {
        if (firstArt == null || candidateArt == null) {
            return false;
        }
        Integer result = jdbc.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                      FROM dbo.SCL_ARTC a
                     WHERE a.ID_SCLAD = ?
                       AND a.COD_ARTIC = ?
                       AND a.COD_ARTIC >= ?
                ) THEN 1 ELSE 0 END
                """,
                Integer.class, warehouseId, candidateArt, firstArt);
        return result != null && result == 1;
    }

    /**
     * Returns the last article that belongs to a native recalculation chunk.
     * I_UCHET_TOVAR exposes the first article of the next chunk, while its
     * @art OUT value is not a reliable range boundary on the legacy database.
     */
    public String findProcessedRangeEnd(int warehouseId, String nextArt) {
        String result;
        if (nextArt == null) {
            result = jdbc.queryForObject("""
                    SELECT MAX(COD_ARTIC)
                      FROM dbo.SCL_ARTC
                     WHERE ID_SCLAD = ?
                    """, String.class, warehouseId);
        } else {
            result = jdbc.queryForObject("""
                    SELECT MAX(COD_ARTIC)
                      FROM dbo.SCL_ARTC
                     WHERE ID_SCLAD = ?
                       AND COD_ARTIC < ?
                    """, String.class, warehouseId, nextArt);
        }
        return trim(result);
    }

    public WarehouseRow findWarehouseForUpdate(int warehouseId) {
        return jdbc.queryForObject("""
                SELECT ID_SCLAD, NAME_SCLAD, N_2, N_4
                  FROM dbo.SCLAD_R WITH (UPDLOCK, HOLDLOCK)
                 WHERE ID_SCLAD = ?
                """, (rs, rowNum) -> mapWarehouse(rs), warehouseId);
    }

    private static int transactionCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT @@TRANCOUNT");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : -1;
        }
    }

    private static void registerVarcharInOut(CallableStatement statement,
                                             int index,
                                             String value) throws SQLException {
        statement.registerOutParameter(index, Types.VARCHAR);
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void registerIntegerInOut(CallableStatement statement,
                                             int index,
                                             int value) throws SQLException {
        statement.registerOutParameter(index, Types.INTEGER);
        statement.setInt(index, value);
    }

    private static Integer nullableOutInteger(CallableStatement statement,
                                              int index) throws SQLException {
        int value = statement.getInt(index);
        return statement.wasNull() ? null : value;
    }

    private static Double nullableOutDouble(CallableStatement statement,
                                            int index) throws SQLException {
        double value = statement.getDouble(index);
        return statement.wasNull() ? null : value;
    }

    private static LocalDateTime nullableOutDateTime(CallableStatement statement,
                                                     int index) throws SQLException {
        Timestamp value = statement.getTimestamp(index);
        return value == null ? null : value.toLocalDateTime();
    }

    private static void registerFloatInOut(CallableStatement statement, int index) throws SQLException {
        statement.registerOutParameter(index, Types.DOUBLE);
        statement.setDouble(index, 0D);
    }

    private static WarehouseRow mapWarehouse(ResultSet rs) throws SQLException {
        return new WarehouseRow(
                rs.getInt("ID_SCLAD"),
                trim(rs.getString("NAME_SCLAD")),
                nullableInteger(rs, "N_2"),
                nullableInteger(rs, "N_4")
        );
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal decimalNullable(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    public record WarehouseScope(
            WarehouseRow requested,
            List<WarehouseRow> affected
    ) {
        public List<Integer> affectedWarehouseIds() {
            return affected.stream().map(WarehouseRow::warehouseId).toList();
        }
    }

    public record WarehouseRow(
            int warehouseId,
            String warehouseName,
            Integer rawAccountingCode,
            Integer accountingGroup
    ) {
    }

    public record ArticleRow(
            String sku,
            int warehouseId,
            String warehouseName,
            String productName,
            String productType,
            BigDecimal initialQuantity,
            BigDecimal physicalQuantity,
            BigDecimal availableQuantity,
            BigDecimal accountingQuantity,
            BigDecimal accountingAmount,
            BigDecimal accountingCurrencyAmount,
            BigDecimal accountingPrice,
            BigDecimal accountingCurrencyPrice,
            BigDecimal initialAccountingPrice,
            BigDecimal initialAccountingCurrencyPrice,
            boolean hiddenType
    ) {
    }

    public record MovementTotals(
            int count,
            BigDecimal quantity,
            BigDecimal accountingAmount,
            BigDecimal accountingCurrencyAmount
    ) {
    }

    public record MovementRow(
            long recno,
            BigDecimal documentId,
            BigDecimal documentNumber,
            int warehouseId,
            LocalDateTime documentDate,
            String documentType,
            boolean returnMovement,
            BigDecimal quantity
    ) {
    }

    public record ProcedureOutput(
            BigDecimal accountingAmount,
            BigDecimal accountingCurrencyAmount
    ) {
    }

    public record NativeFullChunkOutput(
            Integer returnCode,
            String art,
            Integer currentUnits,
            Integer totalUnits,
            String newArt,
            String problemDate,
            String problemCode,
            String problemArt,
            Integer problemRecno,
            LocalDateTime problemOperationDate,
            String problemFormula,
            Double problemNumerator,
            Double problemDenominator,
            Double problemQuantityBefore,
            Double problemMovementQuantity,
            int transactionCountBefore,
            int transactionCountAfter,
            int resultRowCount
    ) {
        public boolean hasProblem() {
            return problemCode != null && !problemCode.isBlank()
                    || problemDate != null && !problemDate.isBlank();
        }
    }

    public record ArithmeticSessionOptions(
            int optionMask,
            boolean ansiWarnings,
            boolean arithAbort,
            boolean arithIgnore
    ) {
    }

    public record NativeChronologyProblem(
            String code,
            String sku,
            int warehouseId,
            long recno,
            BigDecimal documentId,
            BigDecimal documentNumber,
            LocalDateTime documentDate,
            String documentType,
            boolean returnMovement,
            BigDecimal operationQuantity,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            int movementPosition,
            int movementCount,
            BigDecimal initialQuantity,
            BigDecimal physicalQuantity,
            BigDecimal availableQuantity,
            BigDecimal accountingQuantity,
            BigDecimal accountingPrice
    ) {
        NativeChronologyProblem withMovementCount(int value) {
            return new NativeChronologyProblem(
                    code, sku, warehouseId, recno, documentId, documentNumber,
                    documentDate, documentType, returnMovement,
                    operationQuantity, quantityBefore, quantityAfter,
                    movementPosition, value, initialQuantity, physicalQuantity,
                    availableQuantity, accountingQuantity, accountingPrice);
        }
    }

    public record NativeProtectedSnapshot(
            List<String> orderedSkus,
            Map<String, NativeSkuProtectedState> states
    ) {
    }

    public record NativeSkuProtectedState(
            NativeInvariantDigest article,
            NativeInvariantDigest movements
    ) {
    }

    public record NativeInvariantDigest(int rowCount, String sha256) {
    }

}
