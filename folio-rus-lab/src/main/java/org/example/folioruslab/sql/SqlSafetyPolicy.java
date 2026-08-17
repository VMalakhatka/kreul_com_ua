package org.example.folioruslab.sql;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class SqlSafetyPolicy {

    private static final String IDENTIFIER =
            "(?:\\[(?:\\]\\]|[^\\]])+\\]|\"(?:\"\"|[^\"])+\"|"
                    + "[\\p{L}_#][\\p{L}\\p{N}_@$#]*)";
    private static final String LOCAL_VARIABLE =
            "@[\\p{L}_#][\\p{L}\\p{N}_@$#]*";

    private static final List<Rule> ALWAYS_BLOCKED = List.of(
            rule("SYSTEM_PROCEDURE_NAMESPACE",
                    "\\b(?:SP_|XP_|DT_)[\\p{L}\\p{N}_@$#]*"),
            rule("DATABASE_CONTEXT_CHANGE", "\\bUSE(?=\\s|\\[|\")\\s*"),
            rule("CROSS_DATABASE_IDENTIFIER", IDENTIFIER + "\\s*\\.\\s*" + IDENTIFIER
                    + "\\s*\\.\\s*" + IDENTIFIER),
            rule("CROSS_DATABASE_EMPTY_OWNER", IDENTIFIER + "\\s*\\.\\s*\\.\\s*" + IDENTIFIER),
            rule("CROSS_SERVER_TRIPLE_EMPTY", IDENTIFIER
                    + "\\s*\\.\\s*\\.\\s*\\.\\s*" + IDENTIFIER),
            rule("DATABASE_DDL", "\\b(?:CREATE|ALTER|DROP)\\s+DATABASE\\b"),
            rule("BACKUP_OR_RESTORE",
                    "\\b(?:BACKUP|RESTORE)\\b|\\b(?:DUMP|LOAD)\\s+(?:DATABASE|TRAN(?:SACTION)?)\\b"),
            rule("SERVER_CONTROL", "\\b(?:KILL|SHUTDOWN|RECONFIGURE)\\b"),
            rule("DBCC", "\\bDBCC\\b"),
            rule("EXTERNAL_DATA_ACCESS", "\\b(?:OPENROWSET|OPENQUERY|OPENDATASOURCE)\\b"),
            rule("BULK_ACCESS", "\\bBULK\\s+INSERT\\b|\\bBULKADMIN\\b"),
            rule("EXTENDED_PROCEDURE", "\\bXP_[A-Za-z0-9_]+\\b|\\bSP_OA[A-Za-z0-9_]*\\b"),
            rule("SERVER_CONFIGURATION", "\\bSP_CONFIGURE\\b|\\bSP_SERVEROPTION\\b"),
            rule("SERVER_ADMIN_PROCEDURE",
                    "\\bSP_(?:SHUTDOWN|PASSWORD|DEFAULTDB|DEFAULTLANGUAGE|DENYLOGIN|GRANTLOGIN|"
                            + "ADDEXTENDEDPROC|DROPEXTENDEDPROC|ATTACH_DB|ATTACH_SINGLE_FILE_DB|DETACH_DB|"
                            + "ADDUMPDEVICE|DROPDEVICE|MAKEWEBTASK|RUNWEBTASK|TRACE_CREATE)\\b"),
            rule("SERVER_TASK_OR_WEB_PROCEDURE",
                    "\\bSP_(?:(?:ADD|DROP|UPDATE|REASSIGN)TASK|DROPWEBTASK|XML_PREPAREDOCUMENT)\\b"),
            rule("SERVER_SIDE_DYNAMIC_SQL",
                    "\\bSP_(?:PREPARE|PREPEXEC|PREPEXECRPC|EXECUTE|SQLEXEC|UNPREPARE|"
                            + "CURSOR[A-Za-z0-9_]*|MSFOREACH(?:DB|TABLE|_WORKER))\\b"),
            rule("LOGIN_OR_SERVER_ROLE", "\\b(?:CREATE|ALTER|DROP)\\s+(?:LOGIN|SERVER\\s+ROLE)\\b"),
            rule("LOGIN_OR_SERVER_ROLE_PROCEDURE",
                    "\\bSP_(?:ADDLOGIN|DROPLOGIN|ADDSRVROLEMEMBER|DROPSRVROLEMEMBER|ADDSERVER|DROPSERVER|"
                            + "ADDREMOTELOGIN|DROPREMOTELOGIN|ADDLINKEDSERVER|DROPSERVER)\\b"),
            rule("PERMISSION_CHANGE", "\\b(?:GRANT|DENY|REVOKE)\\b|\\bSETUSER\\b|"
                    + "\\bEXECUTE\\s+AS\\b|\\bALTER\\s+AUTHORIZATION\\b|"
                    + "\\b(?:CREATE|ALTER|DROP)\\s+(?:USER|ROLE)\\b|"
                    + "\\bSP_(?:ADDROLEMEMBER|DROPROLEMEMBER|GRANTDBACCESS|REVOKEDBACCESS|CHANGEDBOWNER|"
                    + "ADDUSER|DROPUSER|ADDROLE|DROPROLE|CHANGE_USERS_LOGIN|ADDALIAS|DROPALIAS|"
                    + "ADDGROUP|DROPGROUP|CHANGEGROUP|SETAPPROLE|UNSETAPPROLE)\\b"),
            rule("DATABASE_OPTION_CHANGE", "\\bSP_DBOPTION\\b"),
            rule("BOUND_SESSION_OR_DISTRIBUTED_TRANSACTION",
                    "\\bSP_(?:BINDSESSION|GETBINDTOKEN)\\b|"
                            + "\\bBEGIN\\s+DISTRIBUTED\\s+TRAN(?:SACTION)?\\b"),
            rule("APPLICATION_LOCK_CONTROL",
                    "\\bSP_(?:GETAPPLOCK|RELEASEAPPLOCK)\\b|"
                            + "\\bAPPLOCK_(?:MODE|TEST)\\b"),
            rule("SOURCE_CODE_EXPORT",
                    "\\bSP_HELPTEXT\\b|\\bSYSCOMMENTS\\b|"
                            + "\\bINFORMATION_SCHEMA\\s*\\.\\s*ROUTINES\\b"),
            rule("DYNAMIC_SQL", "\\bSP_EXECUTESQL\\b|\\bEXEC(?:UTE)?\\s*\\("),
            rule("DYNAMIC_SQL_VARIABLE", "\\bEXEC(?:UTE)?\\s+(?:" + LOCAL_VARIABLE
                    + "\\s*=\\s*)?" + LOCAL_VARIABLE
                    + "(?![\\p{L}\\p{N}_@$#]|\\s*=)"),
            rule("WAITFOR", "\\bWAITFOR\\b"),
            rule("CLIENT_BATCH_SEPARATOR", "(?m)^\\s*GO(?:\\s+[0-9]+)?\\s*$")
    );

    private static final List<Rule> MANAGED_TRANSACTION_BLOCKED = List.of(
            rule("EXPLICIT_TRANSACTION_CONTROL",
                    "\\bBEGIN\\s+(?:DISTRIBUTED\\s+)?TRAN(?:SACTION)?\\b|"
                            + "\\b(?:COMMIT|ROLLBACK)\\b|"
                            + "\\bSAVE\\s+TRAN(?:SACTION)?\\b"),
            rule("TRANSACTION_SESSION_CHANGE", "\\bSET\\s+(?:IMPLICIT_TRANSACTIONS|XACT_ABORT)\\b")
    );

    public void validate(String sql, ExecutionMode mode) {
        String code = Normalizer.normalize(
                stripCommentsAndStringLiterals(sql),
                Normalizer.Form.NFKC
        );
        Set<String> violations = new LinkedHashSet<>();

        collectViolations(code, ALWAYS_BLOCKED, violations);
        if (mode.isManaged()) {
            collectViolations(code, MANAGED_TRANSACTION_BLOCKED, violations);
        }

        if (!violations.isEmpty()) {
            throw new SqlPolicyViolationException(new ArrayList<>(violations));
        }
    }

    static String stripCommentsAndStringLiterals(String sql) {
        StringBuilder output = new StringBuilder(sql.length());
        int index = 0;
        int blockDepth = 0;
        State state = State.CODE;

        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (state == State.CODE) {
                if (current == '-' && next == '-') {
                    output.append("  ");
                    index += 2;
                    state = State.LINE_COMMENT;
                    continue;
                }
                if (current == '/' && next == '*') {
                    output.append("  ");
                    index += 2;
                    blockDepth = 1;
                    state = State.BLOCK_COMMENT;
                    continue;
                }
                if (current == '\'') {
                    output.append(' ');
                    index++;
                    state = State.STRING;
                    continue;
                }
                output.append(current);
                index++;
                continue;
            }

            if (state == State.LINE_COMMENT) {
                if (current == '\n' || current == '\r') {
                    output.append(current);
                    state = State.CODE;
                } else {
                    output.append(' ');
                }
                index++;
                continue;
            }

            if (state == State.BLOCK_COMMENT) {
                if (current == '/' && next == '*') {
                    output.append("  ");
                    index += 2;
                    blockDepth++;
                    continue;
                }
                if (current == '*' && next == '/') {
                    output.append("  ");
                    index += 2;
                    blockDepth--;
                    if (blockDepth == 0) {
                        state = State.CODE;
                    }
                    continue;
                }
                output.append(current == '\n' || current == '\r' ? current : ' ');
                index++;
                continue;
            }

            if (current == '\'' && next == '\'') {
                output.append("  ");
                index += 2;
            } else if (current == '\'') {
                output.append(' ');
                index++;
                state = State.CODE;
            } else {
                output.append(current == '\n' || current == '\r' ? current : ' ');
                index++;
            }
        }
        return output.toString();
    }

    private static void collectViolations(String code, List<Rule> rules, Set<String> violations) {
        for (Rule rule : rules) {
            if (rule.pattern().matcher(code).find()) {
                violations.add(rule.code());
            }
        }
    }

    private static Rule rule(String code, String regex) {
        return new Rule(code, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING
    }

    private record Rule(String code, Pattern pattern) {
    }
}
