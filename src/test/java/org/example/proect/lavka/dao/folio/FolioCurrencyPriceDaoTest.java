package org.example.proect.lavka.dao.folio;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FolioCurrencyPriceDaoTest {
 @Test void updatesAreBoundAndPreserveBothAccessScopes() throws Exception {
  var jdbc=mock(JdbcTemplate.class);var dao=new FolioCurrencyPriceDao(jdbc);var sqls=new ArrayList<String>();
  when(jdbc.update(any(PreparedStatementCreator.class))).thenAnswer(inv->{var con=mock(Connection.class);var ps=mock(PreparedStatement.class);when(con.prepareStatement(anyString())).thenAnswer(c->{sqls.add(c.getArgument(0));return ps;});inv.<PreparedStatementCreator>getArgument(0).createPreparedStatement(con);verify(ps).setBigDecimal(1,new java.math.BigDecimal("1.8987"));return 1;});
  var row=new FolioCurrencyPriceDao.Input("ML-47068","1.8987","3");dao.updateCards(row);dao.updateRetail(row);
  assertEquals("UPDATE dbo.SCL_ARTC SET CENA_VALT=?,COD_VALT=? WHERE COD_ARTIC=?",sqls.get(0));
  assertEquals("UPDATE dbo.SCL_PRIC SET RUB_PRICE=? WHERE COD_ARTIC=? AND NAME_PRICE='ЯЯ_Р_ВАЛЮТ' AND ID_SCLAD=7",sqls.get(1));
 }
 @Test void wrongDatabaseIsRejectedBeforeMetadataOrReads(){var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject("SELECT DB_NAME()",String.class)).thenReturn("Paint_Rus");assertThrows(IllegalStateException.class,()->new FolioCurrencyPriceDao(jdbc).preflight());verify(jdbc).queryForObject("SELECT DB_NAME()",String.class);verifyNoMoreInteractions(jdbc);}
}
