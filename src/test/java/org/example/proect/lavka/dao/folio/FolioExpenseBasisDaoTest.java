package org.example.proect.lavka.dao.folio;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class FolioExpenseBasisDaoTest {
    @Test void readsStructuredReferenceFromExactExpenseHeader() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        doAnswer(call->{
            String sql=call.getArgument(0);
            assertThat(sql).contains("OSNOVANIE,NUMSCHETPL,SCH_DATE", "UNICUM_NUM=?", "BRIEFORG=?", "TYPE_DOC=?");
            java.sql.ResultSet row=mock(java.sql.ResultSet.class);
            when(row.getString("OSNOVANIE")).thenReturn("note");
            when(row.getString("NUMSCHETPL")).thenReturn("123456Woo");
            when(row.getTimestamp("SCH_DATE")).thenReturn(java.sql.Timestamp.valueOf("2026-06-18 00:00:00"));
            RowMapper<FolioExpenseBasisDao.ExpenseReference> mapper=call.getArgument(1);
            return java.util.List.of(mapper.mapRow(row,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var ref=new FolioExpenseBasisDao(jdbc).expenseBasis("TEST",1).get(0);
        assertThat(ref.accountNumber()).isEqualTo("123456Woo");
        assertThat(ref.accountDate()).isEqualTo(LocalDate.of(2026,6,18));
    }
    @Test void exactBoundedArchiveQueryUsesParameters() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        doAnswer(call->{
            String sql=call.getArgument(0);
            assertThat(sql).contains("TOP 2", "dbo.SCL_ARCN", "BRIEFORG=?", "N_PLAT_POR=?", "DOPN_SCHET", "DATE_P_POR>=?", "DATE_P_POR<?");
            assertThat(sql).doesNotContain("OFFSET", "ROW_NUMBER", "TEST", "123456");
            return java.util.List.of();
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        new FolioExpenseBasisDao(jdbc).accounts("TEST",new BigDecimal("123456"),"Woo",LocalDate.of(2026,6,18),true);
        verify(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
}
