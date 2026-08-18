package org.example.folioruslab.procedure;

import org.example.folioruslab.db.FolioRusConnectionFactory;
import org.example.folioruslab.db.PaintRusDatabaseGuard;
import org.example.folioruslab.sql.LabOperationGate;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcedureFingerprintServiceTest {

    @Test
    void returnsOnlyHashesForTheTwoApprovedProcedures() throws Exception {
        FolioRusConnectionFactory connectionFactory = mock(FolioRusConnectionFactory.class);
        PaintRusDatabaseGuard databaseGuard = mock(PaintRusDatabaseGuard.class);
        LabOperationGate operationGate = mock(LabOperationGate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement firstStatement = mock(PreparedStatement.class);
        PreparedStatement secondStatement = mock(PreparedStatement.class);
        ResultSet firstRows = rows("CREATE PROC one\r\nAS\r\nSELECT 1", "\r\nRETURN 0");
        ResultSet secondRows = rows("CREATE PROC two\nAS\nSELECT 2");

        when(operationGate.tryAcquire()).thenReturn(true);
        when(connectionFactory.open()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(firstStatement, secondStatement);
        when(firstStatement.executeQuery()).thenReturn(firstRows);
        when(secondStatement.executeQuery()).thenReturn(secondRows);

        ProcedureFingerprintService service = new ProcedureFingerprintService(
                connectionFactory,
                databaseGuard,
                operationGate
        );

        ProcedureFingerprintResponse response = service.capture();

        assertThat(response.database()).isEqualTo("Paint_Rus");
        assertThat(response.procedures()).extracting(ProcedureFingerprint::procedureName)
                .containsExactly("I_UCHET_1_TOVAR", "I_UCHET_TOVAR");
        assertThat(response.procedures().get(0).fragmentCount()).isEqualTo(2);
        assertThat(response.procedures().get(0).normalizedSha256())
                .isEqualTo(ProcedureFingerprintService.sha256(
                        "CREATE PROC one\nAS\nSELECT 1\nRETURN 0"
                ));
        assertThat(response.procedures().get(0).compactSha256())
                .isEqualTo(ProcedureFingerprintService.sha256(
                        "CREATEPROCONEASSELECT1RETURN0"
                ));
        assertThat(response.procedures().get(0).fragments())
                .extracting(ProcedureSourceFragmentFingerprint::fragmentNumber)
                .containsExactly(1, 2);
        assertThat(ProcedureFingerprintService.semantic(
                "select /* ignored */ 'A b' -- ignored\n [Mixed Name]"
        )).isEqualTo("SELECT'A b'[Mixed Name]");

        var ordered = inOrder(firstStatement, secondStatement);
        ordered.verify(firstStatement).setString(1, "I_UCHET_1_TOVAR");
        ordered.verify(secondStatement).setString(1, "I_UCHET_TOVAR");
        verify(connection).setReadOnly(true);
        verify(operationGate).release();
    }

    private static ResultSet rows(String... fragments) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        Boolean[] next = new Boolean[fragments.length + 1];
        Integer[] colIds = new Integer[fragments.length];
        for (int index = 0; index < fragments.length; index++) {
            next[index] = true;
            colIds[index] = index + 1;
        }
        next[fragments.length] = false;
        when(rows.next()).thenReturn(next[0], java.util.Arrays.copyOfRange(next, 1, next.length));
        when(rows.getInt("colid")).thenReturn(
                colIds[0],
                java.util.Arrays.copyOfRange(colIds, 1, colIds.length)
        );
        when(rows.getString("text")).thenReturn(
                fragments[0],
                java.util.Arrays.copyOfRange(fragments, 1, fragments.length)
        );
        return rows;
    }
}
