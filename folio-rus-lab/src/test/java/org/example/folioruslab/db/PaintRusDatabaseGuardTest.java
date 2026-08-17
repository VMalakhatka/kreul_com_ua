package org.example.folioruslab.db;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaintRusDatabaseGuardTest {

    private final PaintRusDatabaseGuard guard = new PaintRusDatabaseGuard();

    @Test
    void acceptsOnlyTheExpectedIsolatedSqlServer2000Copy() {
        assertDoesNotThrow(() -> guard.enforce(fingerprint(0, 0)));
    }

    @Test
    void acceptsSqlServer2000Sp3Build760WhenEveryOtherGuardFieldIsSafe() {
        assertDoesNotThrow(() -> guard.enforce(fingerprint("8.00.760", false, false)));
    }

    @Test
    void rejectsSqlServer2000Build759BeforeSp3() {
        DatabaseGuardException exception = assertThrows(
                DatabaseGuardException.class,
                () -> guard.enforce(fingerprint("8.00.759", false, false))
        );

        assertEquals("WRONG_SQL_SERVER_VERSION", exception.getCode());
    }

    @Test
    void rejectsServerWideCrossDatabaseOwnershipChaining() {
        DatabaseGuardException exception = assertThrows(
                DatabaseGuardException.class,
                () -> guard.enforce(fingerprint("8.00.760", true, false))
        );

        assertEquals("CROSS_DATABASE_OWNERSHIP_CHAINING_NOT_ALLOWED", exception.getCode());
    }

    @Test
    void rejectsPaintRusDatabaseOwnershipChaining() {
        DatabaseGuardException exception = assertThrows(
                DatabaseGuardException.class,
                () -> guard.enforce(fingerprint("8.00.760", false, true))
        );

        assertEquals("CROSS_DATABASE_OWNERSHIP_CHAINING_NOT_ALLOWED", exception.getCode());
    }

    @Test
    void rejectsALoginThatCanReachAnotherUserDatabase() {
        DatabaseGuardException exception = assertThrows(
                DatabaseGuardException.class,
                () -> guard.enforce(fingerprint(0, 1))
        );

        assertEquals("OTHER_DATABASE_ACCESS_NOT_ALLOWED", exception.getCode());
    }

    @Test
    void permitsOtherUserDatabasesWhenThisLoginCannotAccessThem() {
        assertDoesNotThrow(() -> guard.enforce(fingerprint(0, 0, 1, 0)));
    }

    @Test
    void permitsExplicitlyAllowedNorthwindAndPubsDemoAccess() {
        DatabaseFingerprint demoAccess = new DatabaseFingerprint(
                "Paint_Rus",
                "8.00.0760",
                80,
                "SQL_Ukrainian_CP1251_CI_AS",
                1251,
                0,
                0,
                false,
                false,
                0,
                2,
                3,
                0,
                Instant.EPOCH
        );

        assertDoesNotThrow(() -> guard.enforce(demoAccess));
    }

    @Test
    void permitsConfiguredLinkedServerBecauseRemoteSqlSyntaxIsBlockedByPolicy() {
        assertDoesNotThrow(() -> guard.enforce(fingerprint(0, 0, 0, 1)));
    }

    @Test
    void rejectsMembershipInAFixedServerRole() {
        DatabaseGuardException exception = assertThrows(
                DatabaseGuardException.class,
                () -> guard.enforce(fingerprint(1, 0))
        );

        assertEquals("SERVER_ROLE_NOT_ALLOWED", exception.getCode());
    }

    @Test
    void databaseNameCheckIsCaseExactInJava() {
        DatabaseFingerprint wrongCase = new DatabaseFingerprint(
                "PAINT_RUS",
                "8.00.0760",
                80,
                "SQL_Ukrainian_CP1251_CI_AS",
                1251,
                0,
                0,
                false,
                false,
                0,
                0,
                0,
                0,
                Instant.EPOCH
        );

        DatabaseGuardException exception = assertThrows(
                DatabaseGuardException.class,
                () -> guard.enforce(wrongCase)
        );

        assertEquals("WRONG_DATABASE", exception.getCode());
    }

    private static DatabaseFingerprint fingerprint(int fixedServerRoles, int otherDatabases) {
        return fingerprint(fixedServerRoles, otherDatabases, 0, 0);
    }

    private static DatabaseFingerprint fingerprint(
            int fixedServerRoles,
            int accessibleOtherDatabases,
            int otherDatabases,
            int linkedOrRemoteServers
    ) {
        return new DatabaseFingerprint(
                "Paint_Rus",
                "8.00.0760",
                80,
                "SQL_Ukrainian_CP1251_CI_AS",
                1251,
                0,
                fixedServerRoles,
                false,
                false,
                accessibleOtherDatabases,
                0,
                otherDatabases,
                linkedOrRemoteServers,
                Instant.EPOCH
        );
    }

    private static DatabaseFingerprint fingerprint(
            String productVersion,
            boolean serverCrossDatabaseChaining,
            boolean databaseCrossDatabaseChaining
    ) {
        return new DatabaseFingerprint(
                "Paint_Rus",
                productVersion,
                80,
                "SQL_Ukrainian_CP1251_CI_AS",
                1251,
                0,
                0,
                serverCrossDatabaseChaining,
                databaseCrossDatabaseChaining,
                0,
                0,
                0,
                0,
                Instant.EPOCH
        );
    }
}
