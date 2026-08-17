package org.example.folioruslab.db;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlConnectionProvider {

    Connection open() throws SQLException;
}
