package org.example.folioruslab.snapshot;

import org.example.folioruslab.db.DatabaseGuard;
import org.example.folioruslab.db.SqlConnectionProvider;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Component
public final class JdbcAccountingPriceSourceSnapshotGateway
        implements AccountingPriceSourceSnapshotGateway {

    private static final int QUERY_TIMEOUT_SECONDS = 300;

    private static final String WAREHOUSE_SQL = """
            SELECT N_2, N_4
              FROM dbo.SCLAD_R
             WHERE ID_SCLAD = ?
            """;

    private static final String CARD_SQL = """
            SELECT a.COD_ARTIC,
                   a.NACH_KOLCH,
                   a.UCHET_0_C,
                   a.UCHET_0_VL,
                   a.TIP_TOVR,
                   a.PRIZN_VALT,
                   a.FIX_NACEN,
                   a.CENA_ARTIC,
                   a.CENA_VALT,
                   a.CENA_BZNAL,
                   a.CENA_V_BZN,
                   a.NDS_ARTIC,
                   a.COEF_BZNAL,
                   CASE WHEN EXISTS (
                       SELECT 1
                         FROM dbo.TIP_TOVR t
                        WHERE t.SIGNIFIC = a.TIP_TOVR
                          AND t.CHECK_SAVE = 0
                          AND t.SHOW_OSTATOK = 0
                   ) THEN 1 ELSE 0 END AS hidden_for_accounting
              FROM dbo.SCL_ARTC a
             WHERE a.ID_SCLAD = ?
             ORDER BY a.COD_ARTIC
            """;

    private static final String MOVEMENT_SQL = """
            SELECT a.COD_ARTIC,
                   COUNT(*) AS movement_count,
                   MIN(m.RECNO) AS min_recno,
                   MAX(m.RECNO) AS max_recno,
                   MIN(m.DATE_PREDM) AS min_document_date,
                   MAX(m.DATE_PREDM) AS max_document_date,
                   CHECKSUM_AGG(BINARY_CHECKSUM(
                       m.RECNO, m.DATE_PREDM, m.TYPDOCM_PR, m.NUMDOCM_PR,
                       m.ORG_PREDM, m.VOZVRAT_PR, m.KOLC_PREDM
                   )) AS identity_checksum,
                   CHECKSUM_AGG(BINARY_CHECKSUM(
                       m.RECNO, m.SUM_PREDM, m.SUM_VALUT,
                       m.NALOGMONEY, m.NALOGVALUT
                   )) AS source_checksum,
                   CHECKSUM_AGG(BINARY_CHECKSUM(
                       m.RECNO, m.PARTIA, m.SROK, m.SUM_UCHET, m.SUM_UCVAL
                   )) AS accounting_checksum
              FROM dbo.SCL_MOVE m
              JOIN dbo.SCL_ARTC a
                ON a.ID_SCLAD = m.ID_SCLAD
               AND a.COD_ARTIC = m.NAME_PREDM
             WHERE m.ID_SCLAD = ?
               AND m.TYPDOCM_PR <> 'С'
               AND m.STND_UCHET = 1
             GROUP BY a.COD_ARTIC
            """;

    private static final String ORPHAN_MOVEMENT_SQL = """
            SELECT COUNT(*) AS orphan_count
              FROM dbo.SCL_MOVE m
              LEFT JOIN dbo.SCL_ARTC a
                ON a.ID_SCLAD = m.ID_SCLAD
               AND a.COD_ARTIC = m.NAME_PREDM
             WHERE m.ID_SCLAD = ?
               AND m.TYPDOCM_PR <> 'С'
               AND m.STND_UCHET = 1
               AND a.COD_ARTIC IS NULL
            """;

    private static final String PRICE_RULE_SQL = """
            SELECT a.COD_ARTIC,
                   COUNT(*) AS price_rule_count,
                   MIN(p.ID) AS min_price_rule_id,
                   MAX(p.ID) AS max_price_rule_id,
                   CHECKSUM_AGG(BINARY_CHECKSUM(p.ID, p.COEF_PRICE))
                     AS price_rule_checksum
              FROM dbo.SCL_PRIC p
              JOIN dbo.SCL_ARTC a
                ON a.ID_SCLAD = p.ID_SCLAD
               AND a.COD_ARTIC = p.COD_ARTIC
             WHERE p.ID_SCLAD = ?
             GROUP BY a.COD_ARTIC
            """;

    private static final String ORPHAN_PRICE_RULE_SQL = """
            SELECT COUNT(*) AS orphan_count
              FROM dbo.SCL_PRIC p
              LEFT JOIN dbo.SCL_ARTC a
                ON a.ID_SCLAD = p.ID_SCLAD
               AND a.COD_ARTIC = p.COD_ARTIC
             WHERE p.ID_SCLAD = ?
               AND a.COD_ARTIC IS NULL
            """;

    private final SqlConnectionProvider connectionProvider;
    private final DatabaseGuard databaseGuard;

    public JdbcAccountingPriceSourceSnapshotGateway(
            SqlConnectionProvider connectionProvider,
            DatabaseGuard databaseGuard
    ) {
        this.connectionProvider = connectionProvider;
        this.databaseGuard = databaseGuard;
    }

    @Override
    public AccountingPriceSourceSnapshot capture(int warehouseId) {
        try (Connection connection = connectionProvider.open()) {
            databaseGuard.verify(connection);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                WarehouseSettings settings = readWarehouseSettings(connection, warehouseId);
                validateWarehouse(settings);
                TreeMap<String, SkuAccumulator> accumulators = readCards(
                        connection, warehouseId, settings
                );
                RowSummary movementSummary = readMovements(
                        connection, warehouseId, accumulators
                );
                RowSummary priceRuleSummary = readPriceRules(
                        connection, warehouseId, accumulators
                );
                AccountingPriceSourceSnapshot snapshot = finish(
                        warehouseId, settings, accumulators,
                        movementSummary, priceRuleSummary
                );
                connection.rollback();
                return snapshot;
            } catch (Exception exception) {
                rollback(connection, exception);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException(
                        "Paint_Rus accounting-price snapshot failed", exception
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Paint_Rus accounting-price snapshot connection failed", exception
            );
        }
    }

    private static WarehouseSettings readWarehouseSettings(
            Connection connection,
            int warehouseId
    ) throws SQLException {
        try (PreparedStatement statement = prepare(connection, WAREHOUSE_SQL)) {
            statement.setInt(1, warehouseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Folio warehouse does not exist");
                }
                Double rawCode = nullableDouble(resultSet, "N_2");
                Double group = nullableDouble(resultSet, "N_4");
                if (resultSet.next()) {
                    throw new IllegalStateException("Folio warehouse key is not unique");
                }
                return new WarehouseSettings(rawCode, group);
            }
        }
    }

    private static void validateWarehouse(WarehouseSettings settings) {
        if (settings.rawCode() == null
                || Double.compare(settings.rawCode(), 1000.0d) != 0) {
            throw new IllegalArgumentException(
                    "Snapshot v1 supports only Folio average accounting mode N_2=1000"
            );
        }
        if (settings.group() != null) {
            throw new IllegalArgumentException(
                    "Snapshot v1 supports only an ungrouped Folio warehouse N_4=NULL"
            );
        }
    }

    private static TreeMap<String, SkuAccumulator> readCards(
            Connection connection,
            int warehouseId,
            WarehouseSettings settings
    ) throws SQLException {
        TreeMap<String, SkuAccumulator> accumulators = new TreeMap<>();
        try (PreparedStatement statement = prepare(connection, CARD_SQL)) {
            statement.setInt(1, warehouseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String sku = resultSet.getString("COD_ARTIC");
                    if (sku == null || sku.isBlank()) {
                        throw new IllegalStateException(
                                "Folio contains an accounting card without SKU"
                        );
                    }
                    SkuAccumulator accumulator = new SkuAccumulator(sku);
                    accumulator.begin("WAREHOUSE");
                    accumulator.addDouble("N_2", settings.rawCode());
                    accumulator.addDouble("N_4", settings.group());
                    accumulator.begin("CARD");
                    accumulator.addDouble(
                            "NACH_KOLCH", nullableDouble(resultSet, "NACH_KOLCH")
                    );
                    accumulator.addDouble(
                            "UCHET_0_C", nullableDouble(resultSet, "UCHET_0_C")
                    );
                    accumulator.addDouble(
                            "UCHET_0_VL", nullableDouble(resultSet, "UCHET_0_VL")
                    );
                    accumulator.addString("TIP_TOVR", resultSet.getString("TIP_TOVR"));
                    accumulator.addBoolean(
                            "PRIZN_VALT", nullableBoolean(resultSet, "PRIZN_VALT")
                    );
                    accumulator.addBoolean(
                            "FIX_NACEN", nullableBoolean(resultSet, "FIX_NACEN")
                    );
                    accumulator.addDouble(
                            "CENA_ARTIC", nullableDouble(resultSet, "CENA_ARTIC")
                    );
                    accumulator.addDouble(
                            "CENA_VALT", nullableDouble(resultSet, "CENA_VALT")
                    );
                    accumulator.addDouble(
                            "CENA_BZNAL", nullableDouble(resultSet, "CENA_BZNAL")
                    );
                    accumulator.addDouble(
                            "CENA_V_BZN", nullableDouble(resultSet, "CENA_V_BZN")
                    );
                    accumulator.addDouble(
                            "NDS_ARTIC", nullableDouble(resultSet, "NDS_ARTIC")
                    );
                    accumulator.addDouble(
                            "COEF_BZNAL", nullableDouble(resultSet, "COEF_BZNAL")
                    );
                    accumulator.addBoolean(
                            "HIDDEN_FOR_ACCOUNTING",
                            resultSet.getInt("hidden_for_accounting") != 0
                    );
                    if (accumulators.put(sku, accumulator) != null) {
                        throw new IllegalStateException(
                                "Folio contains duplicate warehouse card for SKU"
                        );
                    }
                }
            }
        }
        return accumulators;
    }

    private static RowSummary readMovements(
            Connection connection,
            int warehouseId,
            Map<String, SkuAccumulator> accumulators
    ) throws SQLException {
        long count = 0;
        long ignoredOrphans = readCount(
                connection, ORPHAN_MOVEMENT_SQL, warehouseId, "orphan_count"
        );
        try (PreparedStatement statement = prepare(connection, MOVEMENT_SQL)) {
            statement.setInt(1, warehouseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String sku = resultSet.getString("COD_ARTIC");
                    SkuAccumulator accumulator = accumulators.get(sku);
                    if (accumulator == null) {
                        throw new IllegalStateException(
                                "Canonical movement group has no warehouse card"
                        );
                    }
                    DigestWriter row = new DigestWriter(digest());
                    row.begin("MOVEMENT");
                    long rowCount = resultSet.getLong("movement_count");
                    row.addInteger("MOVEMENT_COUNT", rowCount);
                    row.addInteger("MIN_RECNO", nullableLong(resultSet, "min_recno"));
                    row.addInteger("MAX_RECNO", nullableLong(resultSet, "max_recno"));
                    row.addTimestamp(
                            "MIN_DOCUMENT_DATE",
                            resultSet.getTimestamp("min_document_date")
                    );
                    row.addTimestamp(
                            "MAX_DOCUMENT_DATE",
                            resultSet.getTimestamp("max_document_date")
                    );
                    row.addInteger(
                            "IDENTITY_CHECKSUM",
                            nullableLong(resultSet, "identity_checksum")
                    );
                    row.addInteger(
                            "SOURCE_CHECKSUM",
                            nullableLong(resultSet, "source_checksum")
                    );
                    row.addInteger(
                            "ACCOUNTING_CHECKSUM",
                            nullableLong(resultSet, "accounting_checksum")
                    );
                    accumulator.addMovementAggregate(row.finishBytes(), rowCount);
                    count += rowCount;
                }
            }
        }
        return new RowSummary(count, ignoredOrphans);
    }

    private static RowSummary readPriceRules(
            Connection connection,
            int warehouseId,
            Map<String, SkuAccumulator> accumulators
    ) throws SQLException {
        long count = 0;
        long ignoredOrphans = readCount(
                connection, ORPHAN_PRICE_RULE_SQL, warehouseId, "orphan_count"
        );
        try (PreparedStatement statement = prepare(connection, PRICE_RULE_SQL)) {
            statement.setInt(1, warehouseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String sku = resultSet.getString("COD_ARTIC");
                    SkuAccumulator accumulator = accumulators.get(sku);
                    if (accumulator == null) {
                        throw new IllegalStateException(
                                "Canonical price-rule group has no warehouse card"
                        );
                    }
                    DigestWriter row = new DigestWriter(digest());
                    row.begin("PRICE_RULE");
                    long rowCount = resultSet.getLong("price_rule_count");
                    row.addInteger("PRICE_RULE_COUNT", rowCount);
                    row.addInteger(
                            "MIN_PRICE_RULE_ID",
                            nullableLong(resultSet, "min_price_rule_id")
                    );
                    row.addInteger(
                            "MAX_PRICE_RULE_ID",
                            nullableLong(resultSet, "max_price_rule_id")
                    );
                    row.addInteger(
                            "PRICE_RULE_CHECKSUM",
                            nullableLong(resultSet, "price_rule_checksum")
                    );
                    accumulator.addPriceRuleAggregate(row.finishBytes(), rowCount);
                    count += rowCount;
                }
            }
        }
        return new RowSummary(count, ignoredOrphans);
    }

    private static long readCount(
            Connection connection,
            String sql,
            int warehouseId,
            String column
    ) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.setInt(1, warehouseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Folio count result is missing");
                }
                return resultSet.getLong(column);
            }
        }
    }

    private static AccountingPriceSourceSnapshot finish(
            int warehouseId,
            WarehouseSettings settings,
            TreeMap<String, SkuAccumulator> accumulators,
            RowSummary movementSummary,
            RowSummary priceRuleSummary
    ) {
        TreeMap<String, String> skuDigests = new TreeMap<>();
        MessageDigest warehouseDigest = digest();
        DigestWriter warehouseWriter = new DigestWriter(warehouseDigest);
        warehouseWriter.addString(
                "SNAPSHOT_VERSION", AccountingPriceSourceSnapshot.SNAPSHOT_VERSION
        );
        warehouseWriter.addInteger("WAREHOUSE_ID", (long) warehouseId);
        warehouseWriter.addDouble("N_2", settings.rawCode());
        warehouseWriter.addDouble("N_4", settings.group());
        for (Map.Entry<String, SkuAccumulator> entry : accumulators.entrySet()) {
            String skuDigest = entry.getValue().finish();
            skuDigests.put(entry.getKey(), skuDigest);
            warehouseWriter.addString("SKU", entry.getKey());
            warehouseWriter.addString("SKU_SHA256", skuDigest);
        }
        return new AccountingPriceSourceSnapshot(
                warehouseId,
                HexFormat.of().formatHex(warehouseDigest.digest()),
                skuDigests,
                movementSummary.linkedCount(),
                priceRuleSummary.linkedCount(),
                movementSummary.ignoredOrphanCount(),
                priceRuleSummary.ignoredOrphanCount()
        );
    }

    private static PreparedStatement prepare(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
        );
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static Double nullableDouble(ResultSet resultSet, String column)
            throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet resultSet, String column)
            throws SQLException {
        boolean value = resultSet.getBoolean(column);
        return resultSet.wasNull() ? null : value;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record WarehouseSettings(Double rawCode, Double group) {
    }

    private record RowSummary(long linkedCount, long ignoredOrphanCount) {
    }

    private static final class SkuAccumulator extends DigestWriter {
        private final byte[] movementXor = new byte[32];
        private final byte[] priceRuleXor = new byte[32];
        private long movementCount;
        private long priceRuleCount;

        private SkuAccumulator(String sku) {
            super(digest());
            addString("SNAPSHOT_VERSION", AccountingPriceSourceSnapshot.SNAPSHOT_VERSION);
            addString("SKU", sku);
        }

        private String finish() {
            byte[] cardDigest = finishBytes();
            DigestWriter result = new DigestWriter(digest());
            result.addBytes("CARD_SHA256", cardDigest);
            result.addInteger("MOVEMENT_COUNT", movementCount);
            result.addBytes("MOVEMENT_XOR", movementXor);
            result.addInteger("PRICE_RULE_COUNT", priceRuleCount);
            result.addBytes("PRICE_RULE_XOR", priceRuleXor);
            return HexFormat.of().formatHex(result.finishBytes());
        }

        private void addMovementAggregate(byte[] rowDigest, long rowCount) {
            xor(movementXor, rowDigest);
            movementCount += rowCount;
        }

        private void addPriceRuleAggregate(byte[] rowDigest, long rowCount) {
            xor(priceRuleXor, rowDigest);
            priceRuleCount += rowCount;
        }

        private static void xor(byte[] target, byte[] value) {
            if (target.length != value.length) {
                throw new IllegalArgumentException("Digest sizes do not match");
            }
            for (int index = 0; index < target.length; index++) {
                target[index] ^= value[index];
            }
        }
    }

    private static class DigestWriter {
        protected final MessageDigest messageDigest;

        private DigestWriter(MessageDigest messageDigest) {
            this.messageDigest = messageDigest;
        }

        protected final void begin(String recordType) {
            addString("RECORD", recordType);
        }

        protected final void addString(String field, String value) {
            field(field);
            if (value == null) {
                nullValue();
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            valueMarker();
            messageDigest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            messageDigest.update(bytes);
        }

        protected final void addDouble(String field, Double value) {
            field(field);
            if (value == null) {
                nullValue();
                return;
            }
            valueMarker();
            messageDigest.update(
                    ByteBuffer.allocate(Long.BYTES)
                            .putLong(Double.doubleToLongBits(value))
                            .array()
            );
        }

        protected final void addInteger(String field, Long value) {
            field(field);
            if (value == null) {
                nullValue();
                return;
            }
            valueMarker();
            messageDigest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        }

        protected final void addBoolean(String field, Boolean value) {
            field(field);
            if (value == null) {
                nullValue();
                return;
            }
            valueMarker();
            messageDigest.update((byte) (value ? 1 : 0));
        }

        protected final void addTimestamp(String field, Timestamp value) {
            field(field);
            if (value == null) {
                nullValue();
                return;
            }
            valueMarker();
            messageDigest.update(
                    ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                            .putLong(value.getTime())
                            .putInt(value.getNanos())
                            .array()
            );
        }

        protected final void addBytes(String field, byte[] value) {
            field(field);
            if (value == null) {
                nullValue();
                return;
            }
            valueMarker();
            messageDigest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            messageDigest.update(value);
        }

        protected final byte[] finishBytes() {
            return messageDigest.digest();
        }

        private void field(String field) {
            byte[] bytes = field.getBytes(StandardCharsets.US_ASCII);
            messageDigest.update((byte) 0x1E);
            messageDigest.update((byte) bytes.length);
            messageDigest.update(bytes);
        }

        private void nullValue() {
            messageDigest.update((byte) 0);
        }

        private void valueMarker() {
            messageDigest.update((byte) 1);
        }
    }
}
