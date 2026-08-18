package org.example.folioruslab.procedure;

import org.example.folioruslab.db.DatabaseGuardException;
import org.example.folioruslab.db.FolioRusConnectionFactory;
import org.example.folioruslab.db.PaintRusDatabaseGuard;
import org.example.folioruslab.sql.LabBusyException;
import org.example.folioruslab.sql.LabOperationGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public final class ProcedureFingerprintService {

    private static final Logger log = LoggerFactory.getLogger(ProcedureFingerprintService.class);
    private static final List<String> ALLOWED_PROCEDURES = List.of(
            "I_UCHET_1_TOVAR",
            "I_UCHET_TOVAR"
    );
    private static final String SOURCE_SQL = """
            SELECT c.colid, c.text
            FROM dbo.sysobjects o
            JOIN dbo.syscomments c ON c.id = o.id
            WHERE o.type = 'P' AND o.name = ?
            ORDER BY c.colid
            """;

    private final FolioRusConnectionFactory connectionFactory;
    private final PaintRusDatabaseGuard databaseGuard;
    private final LabOperationGate operationGate;

    public ProcedureFingerprintService(
            FolioRusConnectionFactory connectionFactory,
            PaintRusDatabaseGuard databaseGuard,
            LabOperationGate operationGate
    ) {
        this.connectionFactory = connectionFactory;
        this.databaseGuard = databaseGuard;
        this.operationGate = operationGate;
    }

    public ProcedureFingerprintResponse capture() {
        if (!operationGate.tryAcquire()) {
            throw new LabBusyException();
        }

        try (Connection connection = connectionFactory.open()) {
            databaseGuard.verify(connection);
            connection.setReadOnly(true);

            List<ProcedureFingerprint> fingerprints = new ArrayList<>();
            for (String procedureName : ALLOWED_PROCEDURES) {
                fingerprints.add(captureOne(connection, procedureName));
            }

            log.info(
                    "LAB_PROCEDURE_FINGERPRINTS_CAPTURED database=Paint_Rus procedures={}",
                    fingerprints.size()
            );
            return new ProcedureFingerprintResponse(
                    "Paint_Rus",
                    OffsetDateTime.now(ZoneOffset.UTC),
                    List.copyOf(fingerprints)
            );
        } catch (SQLException exception) {
            throw new DatabaseGuardException(
                    "PROCEDURE_FINGERPRINT_FAILED",
                    "The laboratory could not fingerprint the approved Paint_Rus procedures",
                    exception
            );
        } finally {
            operationGate.release();
        }
    }

    private static ProcedureFingerprint captureOne(
            Connection connection,
            String procedureName
    ) throws SQLException {
        StringBuilder source = new StringBuilder();
        List<ProcedureSourceFragmentFingerprint> fragmentFingerprints = new ArrayList<>();
        int fragments = 0;
        int expectedColId = 1;

        try (PreparedStatement statement = connection.prepareStatement(SOURCE_SQL)) {
            statement.setQueryTimeout(30);
            statement.setString(1, procedureName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int colId = rows.getInt("colid");
                    if (colId != expectedColId) {
                        throw new SQLException("Non-contiguous syscomments fragments for " + procedureName);
                    }
                    String fragment = rows.getString("text");
                    if (fragment == null) {
                        throw new SQLException("NULL syscomments fragment for " + procedureName);
                    }
                    source.append(fragment);
                    String normalizedFragment = normalizeLineEndings(fragment);
                    fragmentFingerprints.add(new ProcedureSourceFragmentFingerprint(
                            colId,
                            normalizedFragment.length(),
                            sha256(normalizedFragment),
                            sha256(compact(normalizedFragment)),
                            sha256(semantic(normalizedFragment))
                    ));
                    fragments++;
                    expectedColId++;
                }
            }
        }

        if (fragments == 0) {
            throw new SQLException("Approved procedure is missing: " + procedureName);
        }

        String normalized = normalizeLineEndings(source.toString());
        return new ProcedureFingerprint(
                procedureName,
                fragments,
                normalized.length(),
                sha256(normalized),
                sha256(compact(normalized)),
                sha256(semantic(normalized)),
                List.copyOf(fragmentFingerprints)
        );
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static String semantic(String value) {
        StringBuilder result = new StringBuilder(value.length());
        LexicalState state = LexicalState.NORMAL;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char next = index + 1 < value.length() ? value.charAt(index + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if (current == '-' && next == '-') {
                        state = LexicalState.LINE_COMMENT;
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = LexicalState.BLOCK_COMMENT;
                        index++;
                    } else if (current == '\'') {
                        result.append(current);
                        state = LexicalState.STRING;
                    } else if (current == '[') {
                        result.append(current);
                        state = LexicalState.BRACKET_IDENTIFIER;
                    } else if (current == '"') {
                        result.append(current);
                        state = LexicalState.QUOTED_IDENTIFIER;
                    } else if (!Character.isWhitespace(current)) {
                        result.append(Character.toUpperCase(current));
                    }
                }
                case STRING -> {
                    result.append(current);
                    if (current == '\'' && next == '\'') {
                        result.append(next);
                        index++;
                    } else if (current == '\'') {
                        state = LexicalState.NORMAL;
                    }
                }
                case BRACKET_IDENTIFIER -> {
                    result.append(current);
                    if (current == ']' && next == ']') {
                        result.append(next);
                        index++;
                    } else if (current == ']') {
                        state = LexicalState.NORMAL;
                    }
                }
                case QUOTED_IDENTIFIER -> {
                    result.append(current);
                    if (current == '"' && next == '"') {
                        result.append(next);
                        index++;
                    } else if (current == '"') {
                        state = LexicalState.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = LexicalState.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = LexicalState.NORMAL;
                        index++;
                    }
                }
            }
        }
        return result.toString();
    }

    private enum LexicalState {
        NORMAL,
        STRING,
        BRACKET_IDENTIFIER,
        QUOTED_IDENTIFIER,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
