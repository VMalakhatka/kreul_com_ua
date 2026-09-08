package org.example.proect.lavka.dao.folio;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** SQL lock evidence, captured on the transaction's JDBC connection, never inferred from text. */
public final class NativeLockFailure extends RuntimeException {
    private final Integer before;
    private final Integer after;
    private final boolean beforeProcedure;

    public NativeLockFailure(Throwable cause, Integer before, Integer after, boolean beforeProcedure) {
        super("Folio SQL lock conflict", cause);
        this.before = before;
        this.after = after;
        this.beforeProcedure = beforeProcedure;
    }

    public boolean rollbackBoundaryKnown() {
        int code = lockCode(getCause());
        if (code == 0 || after == null || after < 0) return false;
        // Before SAFE, only reads/mutex acquisition occurred for an explicit SKU.
        if (beforeProcedure) return after > 0 || code == 1205;
        return before != null && before > 0
                && (before.equals(after) || (code == 1205 && after == 0));
    }

    /** Reject unknown, mixed, cyclic, connection and generic query-timeout errors. */
    public static int lockCode(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && !(current instanceof SQLException)) {
            if (!seen.add(current) || seen.size() > 32 || current.getSuppressed().length != 0) return 0;
            current = current.getCause();
        }
        int code = 0;
        for (SQLException sql = (SQLException) current; sql != null; sql = sql.getNextException()) {
            if (!seen.add(sql) || seen.size() > 48 || sql.getSuppressed().length != 0
                    || sql.getCause() != null) return 0;
            if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) return 0;
            int vendor = sql.getErrorCode();
            if (vendor == 1222 || vendor == 1205) {
                if (code != 0 && code != vendor) return 0;
                code = vendor;
            } else if (vendor != 3621) return 0;
        }
        return code;
    }
}
