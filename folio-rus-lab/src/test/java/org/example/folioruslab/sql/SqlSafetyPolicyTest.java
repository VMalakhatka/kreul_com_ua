package org.example.folioruslab.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSafetyPolicyTest {

    private final SqlSafetyPolicy policy = new SqlSafetyPolicy();

    @ParameterizedTest(name = "allows local SQL: {0}")
    @ValueSource(strings = {
            "SELECT TOP 10 a.ARTC_KOD, a.NAME FROM dbo.ALL_ARTC a ORDER BY a.ARTC_KOD",
            "INSERT INTO dbo.FOLIO_LAB_DOCUMENT (DOC_ID, AMOUNT) VALUES (1, 12.50)",
            "UPDATE dbo.FOLIO_LAB_DOCUMENT SET AMOUNT = 13.25 WHERE DOC_ID = 1",
            "DELETE FROM [dbo].[FOLIO_LAB_DOCUMENT] WHERE DOC_ID = 1",
            "CREATE TABLE dbo.FOLIO_LAB_DOCUMENT (DOC_ID int NOT NULL, AMOUNT decimal(18, 2) NULL); "
                    + "ALTER TABLE dbo.FOLIO_LAB_DOCUMENT ADD NOTE varchar(100) NULL; "
                    + "DROP TABLE dbo.FOLIO_LAB_DOCUMENT",
            "CREATE TABLE #before (ID int NOT NULL); INSERT INTO #before (ID) VALUES (1); "
                    + "SELECT ID FROM #before; DROP TABLE #before",
            "EXEC dbo.card_tov_export @KOD = 1",
            "EXECUTE [dbo].[INSERT_NAKL2] @KOD = 1, @KOL = 2",
            "EXECUTE @rc = dbo.I_UCHET_TOVAR @id_sclad = 5, @art = @art OUTPUT",
            "EXEC @результат = [dbo].[ЛокальнаяПроцедура] @артикул = @артикул OUTPUT"
    })
    void allowsDatabaseLocalSelectDmlDdlTempTablesAndProcedures(String sql) {
        assertDoesNotThrow(() -> policy.validate(sql, ExecutionMode.ROLLBACK));
    }

    @ParameterizedTest(name = "blocks {1}: {0}")
    @MethodSource("alwaysBlockedSql")
    void blocksCommandsThatCanEscapeOrReconfigureTheLaboratory(String sql, String expectedViolation) {
        SqlPolicyViolationException exception = assertThrows(
                SqlPolicyViolationException.class,
                () -> policy.validate(sql, ExecutionMode.SELF_MANAGED));

        assertTrue(
                exception.getViolations().contains(expectedViolation),
                () -> "Expected " + expectedViolation + " but got " + exception.getViolations());
    }

    private static Stream<Arguments> alwaysBlockedSql() {
        return Stream.of(
                Arguments.of("USE Paint_Ua", "DATABASE_CONTEXT_CHANGE"),
                Arguments.of("SELECT * FROM Paint_Ua.dbo.ALL_ARTC", "CROSS_DATABASE_IDENTIFIER"),
                Arguments.of("SELECT * FROM БазаРус.dbo.ALL_ARTC", "CROSS_DATABASE_IDENTIFIER"),
                Arguments.of("SELECT * FROM remote_server.Paint_Ua.dbo.ALL_ARTC", "CROSS_DATABASE_IDENTIFIER"),
                Arguments.of("SELECT * FROM Paint_Ua..ALL_ARTC", "CROSS_DATABASE_EMPTY_OWNER"),
                Arguments.of("SELECT * FROM LinkedServer...ALL_ARTC", "CROSS_SERVER_TRIPLE_EMPTY"),
                Arguments.of("EXEC [Linked Server] . . . [SomeProc]", "CROSS_SERVER_TRIPLE_EMPTY"),
                Arguments.of("EXEC Сервер...Процедура", "CROSS_SERVER_TRIPLE_EMPTY"),
                Arguments.of("SELECT * FROM [Linked]]Server]...[SomeTable]", "CROSS_SERVER_TRIPLE_EMPTY"),
                Arguments.of("SELECT * FROM \"Linked\"\"Server\"...\"SomeTable\"", "CROSS_SERVER_TRIPLE_EMPTY"),
                Arguments.of("EXEC master..xp_cmdshell N'whoami'", "EXTENDED_PROCEDURE"),
                Arguments.of("EXEC(N'SELECT 1')", "DYNAMIC_SQL"),
                Arguments.of("EXECUTE (N'SELECT 1')", "DYNAMIC_SQL"),
                Arguments.of("EXEC sys.sp_executesql N'SELECT 1'", "DYNAMIC_SQL"),
                Arguments.of("EXECUTE @statement", "DYNAMIC_SQL_VARIABLE"),
                Arguments.of("EXECUTE @procedureName @documentId = 1", "DYNAMIC_SQL_VARIABLE"),
                Arguments.of("EXECUTE @команда", "DYNAMIC_SQL_VARIABLE"),
                Arguments.of("EXEC @процедура @документ = 1", "DYNAMIC_SQL_VARIABLE"),
                Arguments.of("EXEC @rc = @procedureName", "DYNAMIC_SQL_VARIABLE"),
                Arguments.of("EXECUTE @результат = @процедура @документ = 1", "DYNAMIC_SQL_VARIABLE"),
                Arguments.of("SELECT * FROM OPENROWSET('SQLOLEDB', 'server'; 'user'; 'password', 'SELECT 1')",
                        "EXTERNAL_DATA_ACCESS"),
                Arguments.of("BULK INSERT dbo.FOLIO_LAB_IMPORT FROM 'input.csv'", "BULK_ACCESS"),
                Arguments.of("BACKUP DATABASE Paint_Rus TO DISK = 'backup.bak'", "BACKUP_OR_RESTORE"),
                Arguments.of("RESTORE DATABASE Paint_Rus FROM DISK = 'backup.bak'", "BACKUP_OR_RESTORE"),
                Arguments.of("DUMP DATABASE Paint_Rus TO DISK = 'backup.bak'", "BACKUP_OR_RESTORE"),
                Arguments.of("EXEC sp_configure 'show advanced options', 1", "SERVER_CONFIGURATION"),
                Arguments.of("EXEC sp_prepare @handle OUTPUT, NULL, N'SELECT 1'", "SERVER_SIDE_DYNAMIC_SQL"),
                Arguments.of("EXEC sp_cursoropen @cursor OUTPUT, N'SELECT 1'", "SERVER_SIDE_DYNAMIC_SQL"),
                Arguments.of("EXEC sp_sqlexec N'USE master'", "SERVER_SIDE_DYNAMIC_SQL"),
                Arguments.of("EXEC sp_MSforeachdb N'SELECT DB_NAME()'", "SERVER_SIDE_DYNAMIC_SQL"),
                Arguments.of("EXEC sp_MSforeachtable N'EXEC(@sql)'", "SERVER_SIDE_DYNAMIC_SQL"),
                Arguments.of("EXEC sp_dropwebtask @procname='job'", "SERVER_TASK_OR_WEB_PROCEDURE"),
                Arguments.of("EXEC sp_xml_preparedocument @h OUTPUT, @xml", "SERVER_TASK_OR_WEB_PROCEDURE"),
                Arguments.of("EXEC sp_addtask @name='job'", "SERVER_TASK_OR_WEB_PROCEDURE"),
                Arguments.of("EXEC sp_droptask @name='job'", "SERVER_TASK_OR_WEB_PROCEDURE"),
                Arguments.of("EXEC sp_updatetask @name='job'", "SERVER_TASK_OR_WEB_PROCEDURE"),
                Arguments.of("EXEC sp_reassigntask @name='job'", "SERVER_TASK_OR_WEB_PROCEDURE"),
                Arguments.of("EXEC sp_unknown_public_proc", "SYSTEM_PROCEDURE_NAMESPACE"),
                Arguments.of("EXEC [dbo].[sp_custom_wrapper]", "SYSTEM_PROCEDURE_NAMESPACE"),
                Arguments.of("EXEC [ｓｐ＿ａｄｄｔａｓｋ] @name='job'", "SYSTEM_PROCEDURE_NAMESPACE"),
                Arguments.of("EXEC xp_unknown", "SYSTEM_PROCEDURE_NAMESPACE"),
                Arguments.of("EXEC dt_addtosourcecontrol", "SYSTEM_PROCEDURE_NAMESPACE"),
                Arguments.of("EXEC sp_addextendedproc 'local_xp', 'library.dll'", "SERVER_ADMIN_PROCEDURE"),
                Arguments.of("EXEC sp_adduser 'some_login'", "PERMISSION_CHANGE"),
                Arguments.of("EXEC sp_bindsession 'token'", "BOUND_SESSION_OR_DISTRIBUTED_TRANSACTION"),
                Arguments.of("EXEC sp_releaseapplock @Resource = 'lab'", "APPLICATION_LOCK_CONTROL"),
                Arguments.of("BEGIN DISTRIBUTED TRANSACTION cross_server", "BOUND_SESSION_OR_DISTRIBUTED_TRANSACTION"),
                Arguments.of("EXEC sp_helptext 'dbo.INSERT_NAKL2'", "SOURCE_CODE_EXPORT"),
                Arguments.of("SELECT text FROM dbo.syscomments", "SOURCE_CODE_EXPORT"),
                Arguments.of("SELECT ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES", "SOURCE_CODE_EXPORT"),
                Arguments.of("KILL 54", "SERVER_CONTROL"),
                Arguments.of("GRANT SELECT ON dbo.ALL_ARTC TO lab_reader", "PERMISSION_CHANGE"),
                Arguments.of("WAITFOR DELAY '00:00:10'", "WAITFOR"),
                Arguments.of("SELECT 1\nGO\nSELECT 2", "CLIENT_BATCH_SEPARATOR"),
                Arguments.of("SELECT 1\nGo 3\nSELECT 2", "CLIENT_BATCH_SEPARATOR")
        );
    }

    @ParameterizedTest(name = "blocks case/comment/bracket bypass: {0}")
    @MethodSource("obfuscatedBlockedSql")
    void stillBlocksMixedCaseCommentsAndBracketedIdentifiers(String sql, String expectedViolation) {
        SqlPolicyViolationException exception = assertThrows(
                SqlPolicyViolationException.class,
                () -> policy.validate(sql, ExecutionMode.ROLLBACK));

        assertTrue(
                exception.getViolations().contains(expectedViolation),
                () -> "Expected " + expectedViolation + " but got " + exception.getViolations());
    }

    private static Stream<Arguments> obfuscatedBlockedSql() {
        return Stream.of(
                Arguments.of("uSe/* keep token boundary */[Paint_Ua]", "DATABASE_CONTEXT_CHANGE"),
                Arguments.of("SeLeCt * FrOm [Paint_Ua] . [dbo] . [ALL_ARTC]", "CROSS_DATABASE_IDENTIFIER"),
                Arguments.of("EXEC [master]..[xp_cmdshell] N'whoami'", "CROSS_DATABASE_EMPTY_OWNER"),
                Arguments.of("eXeC/**/(N'SELECT 1')", "DYNAMIC_SQL"),
                Arguments.of("EXEC [sp_configure] N'show advanced options', 1", "SERVER_CONFIGURATION")
        );
    }

    @Test
    void ignoresDangerousWordsInsideStringsLineCommentsAndNestedBlockComments() {
        String sql = """
                SELECT 'USE Paint_Ua; EXEC master..xp_cmdshell; GO' AS harmless_text;
                -- BACKUP DATABASE Paint_Rus; GRANT CONTROL TO somebody
                /* outer comment
                   OPENROWSET('provider', 'connection', 'query')
                   /* nested comment with EXEC(@sql), WAITFOR and GO */
                   RESTORE DATABASE Paint_Rus
                */
                SELECT N'sp_executesql and BULK INSERT are text here' AS another_value
                """;

        assertDoesNotThrow(() -> policy.validate(sql, ExecutionMode.ROLLBACK));
    }

    @ParameterizedTest(name = "managed mode {0} blocks: {1}")
    @MethodSource("managedTransactionSql")
    void blocksExplicitTransactionControlInManagedModes(ExecutionMode mode, String sql) {
        SqlPolicyViolationException exception = assertThrows(
                SqlPolicyViolationException.class,
                () -> policy.validate(sql, mode),
                () -> "Expected managed mode to reject: " + sql);
        assertTrue(
                exception.getViolations().stream().anyMatch(code ->
                        code.equals("EXPLICIT_TRANSACTION_CONTROL")
                                || code.equals("TRANSACTION_SESSION_CHANGE")),
                () -> "Unexpected violations for " + sql + ": " + exception.getViolations());
    }

    private static Stream<Arguments> managedTransactionSql() {
        String[] transactionSql = {
                "BEGIN TRANSACTION user_transaction",
                "BEGIN DISTRIBUTED TRANSACTION user_transaction",
                "COMMIT",
                "COMMIT TRAN user_transaction",
                "COMMIT WORK",
                "ROLLBACK",
                "ROLLBACK TRANSACTION user_transaction",
                "ROLLBACK WORK",
                "SAVE TRAN savepoint_1",
                "SET IMPLICIT_TRANSACTIONS ON",
                "SET XACT_ABORT OFF"
        };
        return Stream.of(ExecutionMode.ROLLBACK, ExecutionMode.COMMIT)
                .flatMap(mode -> Stream.of(transactionSql).map(sql -> Arguments.of(mode, sql)));
    }

    @Test
    void allowsCallerManagedTransactionsOnlyInSelfManagedMode() {
        String sql = """
                SET XACT_ABORT ON;
                SET IMPLICIT_TRANSACTIONS OFF;
                BEGIN TRANSACTION user_transaction;
                UPDATE dbo.FOLIO_LAB_DOCUMENT SET AMOUNT = 15.00 WHERE DOC_ID = 1;
                SAVE TRANSACTION before_second_change;
                COMMIT TRANSACTION user_transaction
                """;

        assertDoesNotThrow(() -> policy.validate(sql, ExecutionMode.SELF_MANAGED));
    }
}
