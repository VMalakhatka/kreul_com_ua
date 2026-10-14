package org.example.proect.lavka.dao.folio;
import org.example.proect.lavka.service.folio.FolioAssemblyGraph;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolioCurrentNetworkStockDaoTest {
    @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }
    @Test void rejectsReadWithoutAnActualSerializableReadOnlyTransaction() {
        var jdbc = mock(JdbcTemplate.class); var assembly = mock(FolioAssemblyDao.class);
        assertThrows(IllegalStateException.class, () -> new FolioCurrentNetworkStockDao(jdbc, assembly).read("Paint_Ua", List.of(7), List.of(7), List.of("P"), LocalDate.of(2026, 10, 14)));
        verifyNoInteractions(jdbc, assembly);
    }
    @Test void retainsAShorterRemainingTransactionDeadline() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
        var jdbc = mock(JdbcTemplate.class); var assembly = mock(FolioAssemblyDao.class);
        when(assembly.read("Paint_Ua", List.of(7), List.of("P"))).thenReturn(FolioAssemblyGraph.build(List.of("P"), List.of(), Map.of("P", Set.of(0))));
        doAnswer(inv -> {
            var con = mock(Connection.class); var ps = mock(PreparedStatement.class);
            when(con.prepareStatement(anyString())).thenReturn(ps); when(ps.getQueryTimeout()).thenReturn(3);
            inv.<PreparedStatementCreator>getArgument(0).createPreparedStatement(con);
            inv.<PreparedStatementSetter>getArgument(1).setValues(ps); verify(ps).setQueryTimeout(3);
            return null;
        }).when(jdbc).query(any(PreparedStatementCreator.class), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
        var capture = new FolioCurrentNetworkStockDao(jdbc, assembly).read("Paint_Ua", List.of(7), List.of(7), List.of("P"), LocalDate.of(2026, 10, 14));
        assertEquals("SKU_NOT_PRESENT", capture.cards().get(0).status());
    }
    @Test void emitsAnExplicitMissingCardAndRestoresOnlyCurrentInternalSourceReserves() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
        var jdbc = mock(JdbcTemplate.class); var assembly = mock(FolioAssemblyDao.class);
        when(assembly.read("Paint_Ua", List.of(7), List.of("P"))).thenReturn(FolioAssemblyGraph.build(List.of("P"), List.of(), Map.of("P", Set.of(0))));
        var stock = mock(ResultSet.class);
        when(stock.getString("COD_ARTIC")).thenReturn("P"); when(stock.getInt("ID_SCLAD")).thenReturn(7);
        when(stock.getBigDecimal("KON_KOLCH")).thenReturn(new BigDecimal("19"));
        when(stock.getBigDecimal("REZ_KOLCH")).thenReturn(new BigDecimal("-11"));
        when(stock.getBigDecimal("MIN_TVRZAP")).thenReturn(BigDecimal.ONE); when(stock.getBigDecimal("MAX_TVRZAP")).thenReturn(new BigDecimal("9999"));
        var account = mock(ResultSet.class);
        when(account.getString("NAME_PREDM")).thenReturn("P"); when(account.getInt("ID_SCLAD")).thenReturn(7);
        when(account.getLong("UNICUM_NUM")).thenReturn(123L); when(account.getString("TYPDOCM_PR")).thenReturn("С"); when(account.getString("TYPE_DOC")).thenReturn("С");
        when(account.getString("OPERATION_KIND")).thenReturn("*ПЕРЕМЕЩЕНИЕ"); when(account.getBoolean("LINE_ACCOUNTED")).thenReturn(true); when(account.getBoolean("HEADER_ACCOUNTED")).thenReturn(true);
        when(account.getBigDecimal("KOLC_PREDM")).thenReturn(new BigDecimal("6"));
        var preorder = mock(ResultSet.class);
        when(preorder.getString("NAME_PREDM")).thenReturn("P"); when(preorder.getInt("ID_SCLAD")).thenReturn(7);
        when(preorder.getLong("UNICUM_NUM")).thenReturn(555L); when(preorder.getLong("RECNO")).thenReturn(777L);
        when(preorder.getBigDecimal("KOLC_PREDM")).thenReturn(new BigDecimal("4"));
        List<String> queries = new ArrayList<>();
        doAnswer(inv -> {
            var con = mock(Connection.class); var ps = mock(PreparedStatement.class); when(ps.getQueryTimeout()).thenReturn(60);
            when(con.prepareStatement(anyString())).thenAnswer(call -> { queries.add(call.getArgument(0)); return ps; });
            inv.<PreparedStatementCreator>getArgument(0).createPreparedStatement(con);
            inv.<PreparedStatementSetter>getArgument(1).setValues(ps);
            verify(ps, times(2)).setQueryTimeout(15);
            var extractor = inv.<ResultSetExtractor<Void>>getArgument(2);
            if (queries.size() == 1) { when(stock.next()).thenReturn(true, false); extractor.extractData(stock); }
            else if (queries.size() == 2) {
                when(account.next()).thenReturn(true, false); extractor.extractData(account);
                when(account.getString("OPERATION_KIND")).thenReturn("*РОЗНИЦА");
                when(account.next()).thenReturn(true, false); extractor.extractData(account);
            } else { when(preorder.next()).thenReturn(true, false); extractor.extractData(preorder); }
            return null;
        }).when(jdbc).query(any(PreparedStatementCreator.class), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
        var capture = new FolioCurrentNetworkStockDao(jdbc, assembly).read("Paint_Ua", List.of(7,9), List.of(7), List.of("P"), LocalDate.of(2026, 10, 14));
        assertEquals(2, capture.cards().size()); assertEquals(2, capture.version()); assertEquals("FOLIO_SERIALIZABLE_READ", capture.method());
        var card = capture.cards().get(0); assertEquals(new BigDecimal("30"), card.reservedQuantity());
        assertEquals(1, card.internalTransferAccounts().size()); assertEquals(new BigDecimal("6"), card.internalTransferAccounts().get(0).quantity());
        assertEquals(7, card.internalTransferAccounts().get(0).sourceWarehouseId());
        assertEquals("SKU_NOT_PRESENT", capture.cards().get(1).status()); assertNull(capture.cards().get(1).availableQuantity());
        assertEquals(1, capture.preorderReservations().size()); assertEquals(new BigDecimal("4"), capture.preorderReservations().get(0).quantity());
        assertTrue(queries.stream().allMatch(q -> q.startsWith("SELECT") && q.contains("HOLDLOCK") && !q.contains("NOLOCK")));
        assertFalse(queries.get(1).contains("DATE_PREDM"));
        assertTrue(queries.stream().allMatch(q -> q.contains("CAST(? AS varchar(100))")));
        assertTrue(queries.get(1).contains("m.TYPDOCM_PR=CAST(? AS varchar(1))"));
        assertTrue(queries.get(2).contains(FolioPreorderRule.PREDICATE));
    }
}
