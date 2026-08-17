package org.example.folioruslab.sql;

import java.util.List;

public record SqlResult(
        int ordinal,
        String kind,
        Integer updateCount,
        List<SqlColumn> columns,
        List<List<Object>> rows
) {
    public static SqlResult updateCount(int ordinal, int count) {
        return new SqlResult(ordinal, "UPDATE_COUNT", count, List.of(), List.of());
    }

    public static SqlResult rowSet(
            int ordinal,
            List<SqlColumn> columns,
            List<List<Object>> rows
    ) {
        return new SqlResult(ordinal, "ROWSET", null, columns, rows);
    }
}
