package org.example.folioruslab.sql;

final class OutputLimitExceededException extends RuntimeException {

    OutputLimitExceededException(String message) {
        super(message);
    }
}
