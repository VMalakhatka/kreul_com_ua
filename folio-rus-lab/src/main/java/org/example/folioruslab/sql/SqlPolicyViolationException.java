package org.example.folioruslab.sql;

import java.util.List;

public class SqlPolicyViolationException extends RuntimeException {

    private final List<String> violations;

    public SqlPolicyViolationException(List<String> violations) {
        super("SQL batch crosses the Paint_Rus laboratory boundary");
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
