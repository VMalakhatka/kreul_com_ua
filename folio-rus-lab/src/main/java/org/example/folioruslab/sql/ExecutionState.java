package org.example.folioruslab.sql;

public enum ExecutionState {
    ROLLED_BACK,
    COMMITTED,
    SELF_MANAGED_COMPLETED,
    SQL_FAILED_ROLLED_BACK,
    SQL_FAILED,
    OUTPUT_LIMIT_ABORTED,
    TX_BOUNDARY_BROKEN,
    COMMIT_OUTCOME_UNKNOWN;

    public boolean isSuccessful() {
        return this == ROLLED_BACK || this == COMMITTED || this == SELF_MANAGED_COMPLETED;
    }
}
