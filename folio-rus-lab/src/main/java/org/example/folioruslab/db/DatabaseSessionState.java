package org.example.folioruslab.db;

public record DatabaseSessionState(String databaseName, int transactionCount) {
}
