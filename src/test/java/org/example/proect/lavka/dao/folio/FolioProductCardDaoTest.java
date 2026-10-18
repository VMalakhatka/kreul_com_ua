package org.example.proect.lavka.dao.folio;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FolioProductCardDaoTest {
    @Test void comparesValuesNullsAndDuplicateMultiplicity() {
        var cols=List.of(new FolioProductCardDao.Column("sku","text"),new FolioProductCardDao.Column("price","number"));
        var a=new FolioProductCardDao.Data(cols,List.of(Arrays.asList("001",null),List.of("002","1.00")));
        var b=new FolioProductCardDao.Data(cols,List.of(List.of("002","1"),Arrays.asList("001",null)));
        assertTrue(FolioProductCardDao.equivalent(a,b));
        assertFalse(FolioProductCardDao.equivalent(a,new FolioProductCardDao.Data(cols,List.of(List.of("002","1"),List.of("001","0")))));
        assertFalse(FolioProductCardDao.equivalent(a,new FolioProductCardDao.Data(cols,List.of(List.of("002","1"),List.of("002","1")))));
    }
    @Test void bothQueriesAreBoundSelectsWithIdenticalOrderedSchema() throws Exception {
        var jdbc=mock(JdbcTemplate.class); when(jdbc.queryForObject("SELECT DB_NAME()",String.class)).thenReturn("Paint_Ua");
        var sqls=new ArrayList<String>();
        when(jdbc.query(any(PreparedStatementCreator.class),any(ResultSetExtractor.class))).thenAnswer(inv -> {
            var con=mock(Connection.class);var ps=mock(PreparedStatement.class);
            when(con.prepareStatement(anyString())).thenAnswer(call->{sqls.add(call.getArgument(0));return ps;});
            inv.<PreparedStatementCreator>getArgument(0).createPreparedStatement(con);
            verify(ps).setString(1,"x' OR 1=1--"); verify(ps).setQueryTimeout(45);
            return new FolioProductCardDao.Data(List.of(),List.of());
        });
        var result=new FolioProductCardDao(jdbc).read("compare",List.of("x' OR 1=1--"));
        assertEquals(Boolean.TRUE,result.identical());assertEquals(List.of("x' OR 1=1--"),result.missingSkus());
        assertEquals(2,sqls.size());
        assertTrue(sqls.stream().allMatch(s->s.startsWith("SELECT TOP 50001")&&s.contains("CAST(? AS varchar(100))")&&!s.contains("OR 1=1--")));
        assertTrue(sqls.get(0).contains("p1.RUB_PRICE AS [NACENKA_OPT_UA]"));
        assertTrue(sqls.get(0).contains("s6.ID_SCLAD=15"));
        assertTrue(sqls.get(1).contains("dbo.card_tov v"));
    }
    @Test void rejectsWrongDatabaseAndInvalidSelectionBeforeCardRead() {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProductCardDao(jdbc);
        assertThrows(IllegalArgumentException.class,()->dao.read("unknown",List.of())); verifyNoInteractions(jdbc);
        when(jdbc.queryForObject("SELECT DB_NAME()",String.class)).thenReturn("Paint_Rus");
        assertThrows(IllegalArgumentException.class,()->dao.read("direct",List.of()));
        verify(jdbc).queryForObject("SELECT DB_NAME()",String.class);verifyNoMoreInteractions(jdbc);
    }
}
