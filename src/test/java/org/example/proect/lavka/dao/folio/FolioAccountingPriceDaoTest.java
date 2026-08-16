package org.example.proect.lavka.dao.folio;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioAccountingPriceDaoTest {

    @Test
    void mutexMaterialisesServerTransactionBeforeTransactionOwnedAppLock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(any(String.class))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(0);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));

        new FolioAccountingPriceDao(jdbc).acquireRecalculationMutex(5_000);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("IF @@TRANCOUNT = 0")
                .contains("BEGIN TRANSACTION")
                .contains("sp_getapplock")
                .contains("@LockOwner = 'Transaction'");
    }

    @Test
    void mutexRejectsConnectionOutsideManagedTransaction() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));

        assertThatThrownBy(() ->
                new FolioAccountingPriceDao(jdbc).acquireRecalculationMutex(5_000))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("managed JDBC transaction");

        verify(connection, never()).prepareStatement(any(String.class));
    }

    @Test
    void quarantineUsesOneReadAndOneUpdateForMultipleProducts() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("COD_ARTIC")).thenReturn("SKU-A", "SKU-B");
        when(resultSet.getString("TIP_TOVR")).thenReturn("1", "2");
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(resultSet);
            handler.processRow(resultSet);
            return null;
        }).when(jdbc).query(
                anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(2);

        Map<String, String> original = new FolioAccountingPriceDao(jdbc)
                .quarantineNativeSkus(5, Set.of("SKU-A", "SKU-B"), "9");

        assertThat(original).containsExactlyInAnyOrderEntriesOf(
                Map.of("SKU-A", "1", "SKU-B", "2"));
        verify(jdbc).query(
                anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
        verify(jdbc).update(anyString(), any(PreparedStatementSetter.class));
    }

    @Test
    void restoreUsesSingleCaseUpdateAndPreservesIndividualProductTypes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(2);
        Map<String, String> original = new LinkedHashMap<>();
        original.put("SKU-A", "1");
        original.put("SKU-B", null);

        new FolioAccountingPriceDao(jdbc).restoreNativeSkus(5, original);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(PreparedStatementSetter.class));
        assertThat(sql.getValue())
                .contains("CASE COD_ARTIC")
                .contains("COD_ARTIC IN (?, ?)");
    }

}
