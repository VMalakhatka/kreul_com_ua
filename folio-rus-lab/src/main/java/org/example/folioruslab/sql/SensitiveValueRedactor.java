package org.example.folioruslab.sql;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class SensitiveValueRedactor {

    static final String REDACTED = "[REDACTED]";
    private static final int MAXIMUM_DIAGNOSTIC_LENGTH = 2_000;

    private static final Set<String> SENSITIVE_PARTS = Set.of(
            "PASSWORD", "PASSWD", "PWD", "TOKEN", "SECRET", "CREDENTIAL",
            "APIKEY", "API_KEY", "CONNECTIONSTRING", "CONNECTION_STRING",
            "PRIVATEKEY", "PRIVATE_KEY"
    );

    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|api[_-]?key)\\s*[:=]\\s*"
                    + "(?:'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s,;]+)"
    );
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[^\\s'\"<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URI_CREDENTIALS = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@"
    );
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])"
    );
    private static final Pattern SQL_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern QUOTED_UNC_PATH = Pattern.compile("([\"'])\\\\\\\\[^\\r\\n]*?\\1");
    private static final Pattern UNC_PATH = Pattern.compile("\\\\\\\\[^\\s'\"<>]+");

    boolean isSensitiveColumn(String label) {
        if (label == null) {
            return false;
        }
        String normalized = label.toUpperCase(Locale.ROOT).replace('-', '_');
        for (String part : SENSITIVE_PARTS) {
            if (normalized.equals(part)
                    || normalized.startsWith(part + "_")
                    || normalized.endsWith("_" + part)) {
                return true;
            }
        }
        return false;
    }

    String sanitizeValue(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = ASSIGNED_SECRET.matcher(value).replaceAll("$1=" + REDACTED);
        sanitized = JDBC_URL.matcher(sanitized).replaceAll(REDACTED);
        sanitized = URI_CREDENTIALS.matcher(sanitized).replaceAll("$1" + REDACTED + "@");
        sanitized = QUOTED_UNC_PATH.matcher(sanitized).replaceAll("$1" + REDACTED + "$1");
        sanitized = UNC_PATH.matcher(sanitized).replaceAll(REDACTED);
        sanitized = IPV4.matcher(sanitized).replaceAll(REDACTED);
        return sanitized;
    }

    String sanitizeDiagnostic(String message) {
        String sanitized = sanitizeValue(message == null ? "SQL Server rejected the request" : message);
        sanitized = SQL_LITERAL.matcher(sanitized).replaceAll("'[REDACTED]'");
        if (sanitized.length() > MAXIMUM_DIAGNOSTIC_LENGTH) {
            return sanitized.substring(0, MAXIMUM_DIAGNOSTIC_LENGTH) + "…";
        }
        return sanitized;
    }
}
