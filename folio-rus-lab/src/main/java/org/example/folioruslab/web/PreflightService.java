package org.example.folioruslab.web;

import org.example.folioruslab.db.DatabaseGuardException;
import org.example.folioruslab.db.DatabaseFingerprint;
import org.example.folioruslab.db.FolioRusConnectionFactory;
import org.example.folioruslab.db.PaintRusDatabaseGuard;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

@Service
final class PreflightService {

    private static final Logger log = LoggerFactory.getLogger(PreflightService.class);

    private final FolioRusConnectionFactory connectionFactory;
    private final PaintRusDatabaseGuard databaseGuard;

    PreflightService(
            FolioRusConnectionFactory connectionFactory,
            PaintRusDatabaseGuard databaseGuard
    ) {
        this.connectionFactory = connectionFactory;
        this.databaseGuard = databaseGuard;
    }

    PreflightResponse run() {
        log.info("LAB_MANUAL_PREFLIGHT_START database=Paint_Rus");
        try (Connection connection = connectionFactory.open()) {
            DatabaseFingerprint fingerprint = databaseGuard.verify(connection);
            log.info(
                    "LAB_MANUAL_PREFLIGHT_OK version={} compatibility={} codePage={} "
                            + "otherAccessibleDatabases={} allowedDemoDatabases={} "
                            + "otherDatabasesOnServer={} linkedServers={}",
                    fingerprint.productVersion(), fingerprint.compatibilityLevel(),
                    fingerprint.codePage(), fingerprint.accessibleOtherUserDatabaseCount(),
                    fingerprint.accessibleAllowedDemoDatabaseCount(),
                    fingerprint.otherUserDatabaseCount(), fingerprint.linkedOrRemoteServerCount()
            );
            return PreflightResponse.from(fingerprint);
        } catch (SQLException exception) {
            log.warn("LAB_MANUAL_PREFLIGHT_FAILED category=DATABASE_CONNECTION_OR_GUARD");
            throw new DatabaseGuardException(
                    "DATABASE_PREFLIGHT_FAILED",
                    "The local laboratory could not verify Paint_Rus",
                    exception
            );
        }
    }
}
