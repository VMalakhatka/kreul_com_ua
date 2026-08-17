package org.example.folioruslab.sql;

public record SqlColumn(
        int ordinal,
        String label,
        int jdbcType,
        String databaseType,
        boolean sensitive
) {
}
