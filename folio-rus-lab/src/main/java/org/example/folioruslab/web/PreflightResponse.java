package org.example.folioruslab.web;

import org.example.folioruslab.db.DatabaseFingerprint;

import java.time.Instant;

public record PreflightResponse(
        String database,
        String productVersion,
        int compatibilityLevel,
        String collation,
        int codePage,
        int fixedServerRoleCount,
        boolean serverCrossDatabaseChaining,
        boolean databaseCrossDatabaseChaining,
        int accessibleOtherUserDatabaseCount,
        int accessibleAllowedDemoDatabaseCount,
        int otherUserDatabaseCount,
        int linkedOrRemoteServerCount,
        boolean safeToRun,
        boolean strictIsolation,
        Instant checkedAt
) {
    static PreflightResponse from(DatabaseFingerprint fingerprint) {
        return new PreflightResponse(
                fingerprint.databaseName(),
                fingerprint.productVersion(),
                fingerprint.compatibilityLevel(),
                fingerprint.collation(),
                fingerprint.codePage(),
                fingerprint.fixedServerRoleCount(),
                fingerprint.serverCrossDatabaseChaining(),
                fingerprint.databaseCrossDatabaseChaining(),
                fingerprint.accessibleOtherUserDatabaseCount(),
                fingerprint.accessibleAllowedDemoDatabaseCount(),
                fingerprint.otherUserDatabaseCount(),
                fingerprint.linkedOrRemoteServerCount(),
                true,
                !fingerprint.hasOtherUserDatabaseAccess()
                        && !fingerprint.hasCrossDatabaseOwnershipChaining(),
                fingerprint.checkedAt()
        );
    }
}
