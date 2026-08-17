package org.example.folioruslab.sql;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SqlExecutionResponse(
        UUID runId,
        ExecutionState state,
        String database,
        ExecutionMode mode,
        Instant startedAt,
        long durationMs,
        String sqlSha256,
        int transactionBefore,
        Integer transactionAfter,
        int rowCount,
        long estimatedOutputBytes,
        List<SqlResult> results,
        List<String> warnings,
        SqlExecutionError error
) {
}
