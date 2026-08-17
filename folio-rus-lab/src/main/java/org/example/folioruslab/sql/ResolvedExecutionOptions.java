package org.example.folioruslab.sql;

import org.example.folioruslab.config.FolioRusProperties;
import org.example.folioruslab.config.LabProperties;

record ResolvedExecutionOptions(
        ExecutionMode mode,
        int timeoutSeconds,
        int maxRows,
        long maxBytes
) {
    static ResolvedExecutionOptions from(SqlExecutionRequest request, LabProperties properties) {
        ExecutionMode mode = request.mode() == null ? ExecutionMode.ROLLBACK : request.mode();
        int timeout = request.timeoutSeconds() == null
                ? properties.getDefaultTimeoutSeconds()
                : request.timeoutSeconds();
        int rows = request.maxRows() == null
                ? properties.getDefaultMaxRows()
                : request.maxRows();
        long bytes = request.maxBytes() == null
                ? properties.getDefaultMaxBytes()
                : request.maxBytes();

        if (timeout < 1 || rows < 1 || bytes < 1024) {
            throw new IllegalArgumentException("Execution limits must be positive and maxBytes must be at least 1024");
        }
        if (mode != ExecutionMode.ROLLBACK
                && (!Boolean.TRUE.equals(request.allowPersistentChanges())
                || !FolioRusProperties.EXPECTED_DATABASE.equals(request.confirmDatabase()))) {
            throw new IllegalArgumentException(
                    "Persistent modes require allowPersistentChanges=true and exact Paint_Rus confirmation"
            );
        }
        if (timeout > properties.getMaximumTimeoutSeconds()) {
            throw new IllegalArgumentException("timeoutSeconds exceeds the configured laboratory maximum");
        }
        if (rows > properties.getMaximumMaxRows()) {
            throw new IllegalArgumentException("maxRows exceeds the configured laboratory maximum");
        }
        if (bytes > properties.getMaximumMaxBytes()) {
            throw new IllegalArgumentException("maxBytes exceeds the configured laboratory maximum");
        }
        return new ResolvedExecutionOptions(mode, timeout, rows, bytes);
    }
}
