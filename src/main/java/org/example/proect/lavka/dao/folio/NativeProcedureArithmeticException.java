package org.example.proect.lavka.dao.folio;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Evidence captured on the same JDBC connection before Spring attempts rollback. */
public final class NativeProcedureArithmeticException extends RuntimeException {
    private final int transactionCountBefore;
    private final Integer transactionCountAfterError;

    public NativeProcedureArithmeticException(SQLException cause, int before, Integer after) {
        super("LAVKA_I_UCHET_TOVAR_SAFE: " + cause.getMessage(), cause);
        transactionCountBefore = before;
        transactionCountAfterError = after;
    }

    public int transactionCountBefore() { return transactionCountBefore; }
    public Integer transactionCountAfterError() { return transactionCountAfterError; }
    public boolean boundaryPreserved() {
        return transactionCountBefore > 0 && transactionCountAfterError != null
                && transactionCountBefore == transactionCountAfterError;
    }

    public static boolean isolatedDivideByZero(SQLException error) {
        boolean divide = false;
        Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException next = error; next != null; next = next.getNextException()) {
            if (!seen.add(next) || seen.size() > 16) return false;
            if (next.getSQLState() != null && next.getSQLState().startsWith("08")) return false;
            if (next.getErrorCode() == 8134 || "22012".equals(next.getSQLState())) divide = true;
            else if (next.getErrorCode() != 3621) return false; // only companion "statement terminated"
        }
        return divide;
    }
}
