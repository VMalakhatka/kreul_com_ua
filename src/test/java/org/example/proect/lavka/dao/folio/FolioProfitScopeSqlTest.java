package org.example.proect.lavka.dao.folio;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitScopeSqlTest {
    @Test void bothInventoryReadsUseSameParameterizedPrefixAndPreserveMovementAggregation() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProfitReportDao(jdbc);
        var paths=List.of(List.of("A' OR 1=1 --"),List.of("B","Общая"),List.of("C","","Z"));
        dao.findInventoryOpenings(List.of(1,7),paths);
        dao.findInventoryMovements(List.of(1,7),LocalDate.of(2026,7,1),LocalDate.of(2026,8,1),paths);
        var sql=ArgumentCaptor.forClass(String.class);var setters=ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc,times(2)).query(sql.capture(),setters.capture(),any(RowMapper.class));
        var opening=sql.getAllValues().get(0);var movement=sql.getAllValues().get(1);
        for(String query:sql.getAllValues()) {
            assertThat(query).contains("NGROUP_TVR","NGROUP_TV2","NGROUP_TV3","ISNULL(","VARBINARY(255)")
                    .doesNotContain("A' OR","Общая","ROW_NUMBER","OFFSET","OPENJSON");
        }
        assertThat(opening).contains("WHERE a.ID_SCLAD IN (?,?) AND NOT (");
        assertThat(movement).contains("NOT EXISTS", "a.ID_SCLAD=m.ID_SCLAD AND a.COD_ARTIC=m.NAME_PREDM",
                "GROUP BY m.ID_SCLAD, m.NAME_PREDM","m.STND_UCHET = 1","m.TYPDOCM_PR IN (?, ?)");
        var ps=mock(PreparedStatement.class);setters.getAllValues().get(0).setValues(ps);
        verify(ps).setObject(1,1);verify(ps).setObject(2,7);
        verify(ps).setString(3,"A' OR 1=1 --");verify(ps).setString(4,"B");verify(ps).setString(5,"Общая");
        verify(ps).setString(6,"C");verify(ps).setString(7,"");verify(ps).setString(8,"Z");
        var pm=mock(PreparedStatement.class);setters.getAllValues().get(1).setValues(pm);
        verify(pm).setObject(12,1);verify(pm).setObject(13,7);
        verify(pm).setString(14,"A' OR 1=1 --");verify(pm).setString(19,"Z");verify(pm).setQueryTimeout(30);
    }
    @Test void emptyExclusionsDoNotAddAnyHiddenProductOrGroupFilter() {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProfitReportDao(jdbc);
        dao.findInventoryOpenings(List.of(5));
        dao.findInventoryMovements(List.of(5),LocalDate.of(2026,7,1),LocalDate.of(2026,8,1));
        dao.findGrossMargins(LocalDate.of(2026,7,1),LocalDate.of(2026,8,1));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc,times(3)).query(sql.capture(),any(PreparedStatementSetter.class),any(RowMapper.class));
        assertThat(sql.getAllValues()).allSatisfy(q->assertThat(q).doesNotContain("NGROUP_TV","NOT EXISTS","TIP_TOVR"));
    }
    @Test @SuppressWarnings("unchecked")
    void catalogUsesRealWarehouseAndCardPathsWithLegacyCompatibleSqlAndTimeout() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProfitScopeCatalogDao(jdbc);
        dao.warehouses();dao.groupPaths();
        var sql=ArgumentCaptor.forClass(String.class);var setters=ArgumentCaptor.forClass(PreparedStatementSetter.class);
        var mappers=ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc,times(2)).query(sql.capture(),setters.capture(),mappers.capture());
        assertThat(sql.getAllValues().get(0)).contains("dbo.SCLAD_R","ID_SCLAD","NAME_SCLAD").doesNotContain("C_1","UPDATE");
        assertThat(sql.getAllValues().get(1)).contains("dbo.SCL_ARTC","SELECT DISTINCT","NGROUP_TVR","NGROUP_TV6","VARBINARY(255)")
                .doesNotContain("CRM_","OFFSET","ROW_NUMBER");
        var statement=mock(PreparedStatement.class);setters.getAllValues().get(1).setValues(statement);verify(statement).setQueryTimeout(30);
        var rs=mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("A");when(rs.getString(2)).thenReturn("");when(rs.getString(3)).thenReturn("C");
        for(int i=4;i<=6;i++) when(rs.getString(i)).thenReturn("");
        assertThat((List<String>)mappers.getAllValues().get(1).mapRow(rs,0)).containsExactly("A","","C");
    }
}
