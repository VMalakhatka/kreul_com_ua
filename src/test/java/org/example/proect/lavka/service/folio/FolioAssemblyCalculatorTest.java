package org.example.proect.lavka.service.folio;

import org.springframework.jdbc.core.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioAssemblyCalculatorTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);final Connection connection=mock(Connection.class);
    final FolioCurrencyPriceServiceTest.Manager tx=new FolioCurrencyPriceServiceTest.Manager();
    final List<String> sql=new ArrayList<>();boolean ambiguous=false;
    FolioAssemblyCalculator calculator() throws SQLException {
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Object>>any())).thenAnswer(i->((ConnectionCallback<?>)i.getArgument(0)).doInConnection(connection));
        when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(connection.createStatement()).thenAnswer(i->{
            var s=mock(Statement.class);when(s.executeUpdate(anyString())).thenAnswer(x->{sql.add(x.getArgument(0));return 0;});
            when(s.executeQuery(anyString())).thenAnswer(x->{String text=x.getArgument(0);sql.add(text);var rs=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);when(rs.getMetaData()).thenReturn(meta);
                if(ambiguous&&text.contains("SELECT ARTIC_rebenok FROM #fm_input"))when(rs.next()).thenReturn(true,false);return rs;});return s;
        });return new FolioAssemblyCalculator(jdbc,tx);
    }
    List<Map<String,String>> input(){return List.of(Map.of("ARTIC_roditel","A","ARTIC_rebenok","B","KOL_rod_razob","2","kol_reb_pol","1","CENA_REB","3","SLOG","0"));}
    @Test void scopedScratchTablesAndReadOrderArePreserved()throws Exception {
        var result=calculator().calculate(input());assertEquals(List.of("ASSEMBLY_18","ASSEMBLY_9","ASSEMBLY_91","ASSEMBLY_INPUT"),new ArrayList<>(result.keySet()));assertEquals(1,tx.commits);
        for(String command:sql){assertFalse(command.contains("Paint_Rus"));assertFalse(command.matches("(?is).*(UPDATE|INSERT INTO|DELETE FROM|DROP TABLE) dbo\\..*"));}
        int read18=-1,step2=-1;for(int i=0;i<sql.size();i++){if(sql.get(i).startsWith("SELECT ARTIC,KOL_R,PRICE,ARTIC_REB"))read18=i;if(sql.get(i).startsWith("UPDATE i SET kol_reb_pol="))step2=i;}assertTrue(read18>=0&&step2>read18);
        assertEquals(3,sql.stream().filter(q->q.contains("DROP TABLE #fm_")).count());
    }
    @Test void duplicateOutputChildrenRollBackAndCleanEveryScratchTable()throws Exception {
        ambiguous=true;var calc=calculator();var e=assertThrows(IllegalArgumentException.class,()->calc.calculate(input()));assertEquals("ASSEMBLY_DUPLICATE_CHILD",e.getMessage());assertEquals(1,tx.rollbacks);
        assertFalse(sql.stream().anyMatch(q->q.startsWith("UPDATE t SET PRICE")));assertEquals(3,sql.stream().filter(q->q.contains("DROP TABLE #fm_")).count());
    }
}
