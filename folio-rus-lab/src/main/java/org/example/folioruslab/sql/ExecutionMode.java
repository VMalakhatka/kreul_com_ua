package org.example.folioruslab.sql;

public enum ExecutionMode {
    ROLLBACK,
    COMMIT,
    SELF_MANAGED;

    public boolean isManaged() {
        return this == ROLLBACK || this == COMMIT;
    }
}
