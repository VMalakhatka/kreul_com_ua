package org.example.folioruslab.sql;

public final class LabBusyException extends RuntimeException {

    public LabBusyException() {
        super("Another Paint_Rus laboratory execution is still running");
    }
}
