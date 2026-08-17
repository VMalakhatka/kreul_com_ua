package org.example.folioruslab.db;

public class DatabaseGuardException extends RuntimeException {

    private final String code;

    public DatabaseGuardException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DatabaseGuardException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
