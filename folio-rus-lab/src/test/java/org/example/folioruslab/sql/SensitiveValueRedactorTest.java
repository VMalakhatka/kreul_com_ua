package org.example.folioruslab.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveValueRedactorTest {

    private final SensitiveValueRedactor redactor = new SensitiveValueRedactor();

    @ParameterizedTest
    @ValueSource(strings = {
            "password",
            "USER_PASSWORD",
            "password_hash",
            "passwd",
            "db_pwd",
            "access_token",
            "TOKEN_EXPIRES_AT",
            "apiKey",
            "api_key_value",
            "connection-string",
            "private_key_pem"
    })
    void recognizesSensitiveColumnNames(String columnName) {
        assertTrue(redactor.isSensitiveColumn(columnName), columnName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "partner_name",
            "article_code",
            "document_number",
            "amount",
            "created_at",
            "credentialed_partner"
    })
    void doesNotTreatOrdinaryBusinessColumnsAsSecrets(String columnName) {
        assertFalse(redactor.isSensitiveColumn(columnName), columnName);
    }

    @Test
    void nullColumnAndNullValueAreNotSensitive() {
        assertFalse(redactor.isSensitiveColumn(null));
        assertNull(redactor.sanitizeValue(null));
    }

    @Test
    void redactsUnquotedPasswordAndTokenAssignments() {
        String diagnostic = "password=sample-pass; token: sample-api-token, pwd = another-value";

        assertEquals(
                "password=[REDACTED]; token=[REDACTED], pwd=[REDACTED]",
                redactor.sanitizeValue(diagnostic));
    }

    @Test
    void fullyRedactsQuotedAssignmentsIncludingSpaces() {
        String diagnostic = "password='two word secret'; token: \"three word token\"; status=failed";

        String sanitized = redactor.sanitizeValue(diagnostic);

        assertFalse(sanitized.contains("two word secret"), sanitized);
        assertFalse(sanitized.contains("three word token"), sanitized);
        assertEquals(
                "password=[REDACTED]; token=[REDACTED]; status=failed",
                sanitized);
    }

    @Test
    void redactsWholeJdbcUrlIncludingParameters() {
        String diagnostic = "Connection failed for "
                + "jdbc:jtds:sqlserver://10.20.30.40:1433/Paint_Rus;user=sample-login;password=sample-pass";

        assertEquals("Connection failed for [REDACTED]", redactor.sanitizeValue(diagnostic));
    }

    @Test
    void redactsUriCredentialsAndHostAddress() {
        String diagnostic = "Cannot call https://sample-user:sample-pass@10.20.30.40:8443/status";

        assertEquals(
                "Cannot call https://[REDACTED]@[REDACTED]:8443/status",
                redactor.sanitizeValue(diagnostic));
    }

    @Test
    void redactsIpv4AddressesButKeepsBusinessNumbers() {
        String diagnostic = "SQL host 192.168.10.25; invoice 2026-00125; amount 1250.45";

        assertEquals(
                "SQL host [REDACTED]; invoice 2026-00125; amount 1250.45",
                redactor.sanitizeValue(diagnostic));
    }

    @Test
    void redactsUncPath() {
        String diagnostic = "Export failed at \\\\folio-server\\reports\\private\\document.rpt";

        assertEquals("Export failed at [REDACTED]", redactor.sanitizeValue(diagnostic));
    }

    @Test
    void redactsQuotedUncPathContainingSpacesWithoutLeavingPathFragments() {
        String diagnostic = "Export failed at \"\\\\folio-server\\private share\\client data.rpt\"";

        assertEquals("Export failed at \"[REDACTED]\"", redactor.sanitizeValue(diagnostic));
    }

    @Test
    void leavesOrdinaryBusinessTextUnchanged() {
        String businessText = "Клиент ТОВ Фарба; накладная РН-123; "
                + "артикул KR-1234; сумма 1250,45; дата 12.08.2026";

        assertEquals(businessText, redactor.sanitizeValue(businessText));
    }

    @Test
    void sanitizesBeforeTruncatingDiagnosticAndAddsEllipsis() {
        String diagnostic = "password=do-not-leak " + "x".repeat(2_500);

        String sanitized = redactor.sanitizeDiagnostic(diagnostic);

        assertEquals(2_001, sanitized.length());
        assertTrue(sanitized.startsWith("password=[REDACTED] "));
        assertTrue(sanitized.endsWith("…"));
        assertFalse(sanitized.contains("do-not-leak"));
    }

    @Test
    void suppliesSafeFallbackForNullDiagnostic() {
        assertEquals("SQL Server rejected the request", redactor.sanitizeDiagnostic(null));
    }
}
