package org.example.folioruslab.db;

import java.time.Instant;

public record DatabaseFingerprint(
        String databaseName,
        String productVersion,
        int compatibilityLevel,
        String collation,
        int codePage,
        int transactionCount,
        int fixedServerRoleCount,
        boolean serverCrossDatabaseChaining,
        boolean databaseCrossDatabaseChaining,
        int accessibleOtherUserDatabaseCount,
        int accessibleAllowedDemoDatabaseCount,
        int otherUserDatabaseCount,
        int linkedOrRemoteServerCount,
        Instant checkedAt
) {
    public boolean hasOtherUserDatabaseAccess() {
        return accessibleOtherUserDatabaseCount > 0;
    }

    public boolean hasAllowedDemoDatabaseAccess() {
        return accessibleAllowedDemoDatabaseCount > 0;
    }

    public boolean hasCrossDatabaseOwnershipChaining() {
        return serverCrossDatabaseChaining || databaseCrossDatabaseChaining;
    }

    public boolean hasOtherUserDatabasesOnInstance() {
        return otherUserDatabaseCount > 0;
    }

    public boolean hasLinkedOrRemoteServers() {
        return linkedOrRemoteServerCount > 0;
    }
}
