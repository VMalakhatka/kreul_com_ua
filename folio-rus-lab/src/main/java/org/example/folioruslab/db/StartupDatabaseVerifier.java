package org.example.folioruslab.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.sql.Connection;

@Component
public final class StartupDatabaseVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDatabaseVerifier.class);

    private final FolioRusConnectionFactory connectionFactory;
    private final PaintRusDatabaseGuard databaseGuard;

    public StartupDatabaseVerifier(
            FolioRusConnectionFactory connectionFactory,
            PaintRusDatabaseGuard databaseGuard
    ) {
        this.connectionFactory = connectionFactory;
        this.databaseGuard = databaseGuard;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = connectionFactory.open()) {
            DatabaseFingerprint fingerprint = databaseGuard.verify(connection);
            log.info(
                    "Paint_Rus guard passed: compatibility={}, codePage={}, crossDatabaseChaining={}, "
                            + "otherUserDatabases={}, linkedOrRemoteServers={}",
                    fingerprint.compatibilityLevel(),
                    fingerprint.codePage(),
                    fingerprint.hasCrossDatabaseOwnershipChaining(),
                    fingerprint.otherUserDatabaseCount(),
                    fingerprint.linkedOrRemoteServerCount()
            );
        }
    }
}
