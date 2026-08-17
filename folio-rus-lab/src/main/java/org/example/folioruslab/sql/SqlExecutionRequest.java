package org.example.folioruslab.sql;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SqlExecutionRequest(
        @NotBlank @Size(max = 500_000) String sql,
        ExecutionMode mode,
        Boolean allowPersistentChanges,
        @Size(max = 128) String confirmDatabase,
        @Min(1) @Max(300) Integer timeoutSeconds,
        @Min(1) @Max(100_000) Integer maxRows,
        @Min(1024) @Max(52_428_800) Long maxBytes
) {
}
