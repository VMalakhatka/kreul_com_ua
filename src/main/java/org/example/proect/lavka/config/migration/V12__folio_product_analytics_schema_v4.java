package org.example.proect.lavka.config.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** MySQL-only V12. MariaDB keeps its original SQL/checksum, in the vendor location. */
public class V12__folio_product_analytics_schema_v4 extends BaseJavaMigration {
    private static final String CURRENT = "folio_product_metric_current";
    private static final String STAGE = "folio_product_metric_current_stage";
    private static final String INDEX = "idx_folio_product_metric_primary_barcode";

    // Versioned contract: do not silently edit this migration after successful application.
    @Override public Integer getChecksum() { return 2026091901; }
    @Override public boolean canExecuteInTransaction() { return false; }

    @Override public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        if (!"MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()))
            throw mismatch("MySQL V12 must not run on a different database engine");
        // Validate ALL existing objects before any DDL, rather than masking a partial wrong schema.
        List<String> missing = new ArrayList<>();
        for (String table : List.of(CURRENT, STAGE)) if (!validateColumn(c, table)) missing.add(table);
        boolean hasIndex = validateIndex(c);
        for (String table : missing) execute(c, "ALTER TABLE " + table
                + " ADD COLUMN primary_barcode VARCHAR(128) NULL AFTER maximum_stock");
        if (!hasIndex) execute(c, "ALTER TABLE " + CURRENT + " ADD KEY " + INDEX
                + " (source_database, primary_barcode, warehouse_id, sku)");
        for (String table : List.of(CURRENT, STAGE)) {
            if (!validateColumn(c, table)) throw mismatch("Missing barcode column after DDL: " + table);
        }
        if (!validateIndex(c)) throw mismatch("Missing barcode index after DDL");
    }

    private static boolean validateColumn(Connection c, String table) throws SQLException {
        String collation;
        try (var s = c.prepareStatement("SELECT TABLE_COLLATION FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND TABLE_TYPE='BASE TABLE'")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw mismatch("Missing prerequisite table: " + table);
                collation = r.getString(1);
            }
        }
        try (var s = c.prepareStatement("SELECT b.COLUMN_TYPE,b.IS_NULLABLE,b.COLUMN_DEFAULT,b.EXTRA,b.COLLATION_NAME,"
                + "b.ORDINAL_POSITION,m.ORDINAL_POSITION FROM information_schema.COLUMNS b"
                + " LEFT JOIN information_schema.COLUMNS m ON m.TABLE_SCHEMA=b.TABLE_SCHEMA"
                + " AND m.TABLE_NAME=b.TABLE_NAME AND m.COLUMN_NAME='maximum_stock'"
                + " WHERE b.TABLE_SCHEMA=DATABASE() AND b.TABLE_NAME=? AND b.COLUMN_NAME='primary_barcode'")) {
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                if (!r.next()) return false;
                if (!"varchar(128)".equalsIgnoreCase(r.getString(1)) || !"YES".equals(r.getString(2))
                        || r.getObject(3) != null || !"".equals(r.getString(4))
                        || !java.util.Objects.equals(collation, r.getString(5))
                        || r.getObject(7) == null || r.getInt(6) != r.getInt(7) + 1)
                    throw mismatch("Unexpected existing primary_barcode definition: " + table);
                return true;
            }
        }
    }

    private static boolean validateIndex(Connection c) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var s = c.prepareStatement("SELECT COLUMN_NAME,NON_UNIQUE,SUB_PART,INDEX_TYPE,COLLATION,IS_VISIBLE"
                + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?"
                + " AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX")) {
            s.setString(1, CURRENT); s.setString(2, INDEX);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (r.getInt(2) != 1 || r.getObject(3) != null || !"BTREE".equals(r.getString(4))
                            || !"A".equals(r.getString(5)) || !"YES".equals(r.getString(6)))
                        throw mismatch("Unexpected existing barcode index properties");
                    columns.add(r.getString(1));
                }
            }
        }
        if (columns.isEmpty()) return false;
        if (!columns.equals(List.of("source_database", "primary_barcode", "warehouse_id", "sku")))
            throw mismatch("Unexpected existing barcode index columns");
        return true;
    }

    private static SQLException mismatch(String message) { return new SQLException("WP_V12_SCHEMA_MISMATCH: " + message, "45000"); }
    private static void execute(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) { s.execute(sql); }
    }
}
