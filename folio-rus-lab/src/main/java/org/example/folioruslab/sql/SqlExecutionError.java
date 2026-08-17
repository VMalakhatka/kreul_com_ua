package org.example.folioruslab.sql;

public record SqlExecutionError(
        String code,
        String sqlState,
        Integer vendorCode,
        String message
) {
}
