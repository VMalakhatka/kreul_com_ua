package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class FolioBalanceDatabaseActivityDao {

    private static final String PROCEDURE_MARKER = "I_DOLG_DOC";

    private final DataSource dataSource;

    public FolioBalanceDatabaseActivityDao(@Qualifier("folioDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Executes read-only SQL Server 2000 diagnostics. Login, host, application name,
     * SQL text and procedure arguments deliberately never leave this DAO.
     */
    public Inspection inspect() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            CurrentSession current = currentSession(connection);
            List<WhoSession> candidates = sameDatabaseSessions(connection, current);

            int detected = 0;
            int active = 0;
            int blocked = 0;
            int idle = 0;
            int inspectionFailures = 0;

            for (WhoSession session : candidates) {
                String input;
                try {
                    input = inputBuffer(connection, session.spid());
                } catch (SQLException ignored) {
                    inspectionFailures++;
                    continue;
                }
                if (!containsProcedure(input)) {
                    continue;
                }

                detected++;
                if (session.blocked()) {
                    blocked++;
                } else if (session.sleeping()) {
                    idle++;
                } else {
                    active++;
                }
            }

            return new Inspection(detected, active, blocked, idle, inspectionFailures);
        }
    }

    private static CurrentSession currentSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT SYSTEM_USER, DB_NAME(), @@SPID")) {
            if (!resultSet.next()) {
                throw new SQLException("Cannot read current SQL Server session");
            }
            return new CurrentSession(
                    resultSet.getString(1),
                    resultSet.getString(2),
                    resultSet.getInt(3)
            );
        }
    }

    private static List<WhoSession> sameDatabaseSessions(Connection connection,
                                                         CurrentSession current) throws SQLException {
        List<WhoSession> sessions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("EXEC master.dbo.sp_who2")) {
            while (resultSet.next()) {
                int spid = parseInteger(resultSet.getString(1));
                if (spid <= 0 || spid == current.spid()) {
                    continue;
                }
                String login = resultSet.getString(3);
                String database = resultSet.getString(6);
                if (!same(login, current.login()) || !same(database, current.database())) {
                    continue;
                }
                sessions.add(new WhoSession(
                        spid,
                        resultSet.getString(2),
                        parseInteger(resultSet.getString(5)) > 0
                ));
            }
        }
        return sessions;
    }

    private static String inputBuffer(Connection connection, int spid) throws SQLException {
        // spid comes from sp_who2 and is parsed as an integer, not from an HTTP parameter.
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("DBCC INPUTBUFFER(" + spid + ")")) {
            return resultSet.next() ? resultSet.getString(3) : null;
        }
    }

    private static boolean containsProcedure(String input) {
        return input != null && input.toUpperCase(Locale.ROOT).contains(PROCEDURE_MARKER);
    }

    private static int parseInteger(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private record CurrentSession(String login, String database, int spid) {
    }

    private record WhoSession(int spid, String status, boolean blocked) {
        boolean sleeping() {
            return status != null && status.trim().equalsIgnoreCase("sleeping");
        }
    }

    public record Inspection(
            int detectedSessions,
            int activeSessions,
            int blockedSessions,
            int idleSessions,
            int inspectionFailures
    ) {
    }
}
