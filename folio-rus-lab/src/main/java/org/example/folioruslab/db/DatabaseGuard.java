package org.example.folioruslab.db;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseGuard {

    DatabaseFingerprint verify(Connection connection);

    DatabaseSessionState readSessionState(Connection connection) throws SQLException;

    int readTransactionCount(Connection connection) throws SQLException;
}
