package org.example.proect.lavka.dao.folio;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class FolioDocumentValuesDaoTest {
    private FolioDocumentValuesDao.Values read(int lines,int knownCost,BigDecimal sum) throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); ResultSet rs=mock(ResultSet.class);
        when(rs.getInt("LINE_COUNT")).thenReturn(lines); when(rs.getInt("AMOUNT_COUNT")).thenReturn(lines); when(rs.getInt("COST_COUNT")).thenReturn(knownCost);
        when(rs.getBigDecimal("N_PLAT_POR")).thenReturn(new BigDecimal("50")); when(rs.getTimestamp("DATE_P_POR")).thenReturn(Timestamp.valueOf("2026-06-01 00:00:00"));
        when(rs.getBigDecimal("SUM_POR")).thenReturn(new BigDecimal("149.00")); when(rs.getBigDecimal("SHIPMENT_AMOUNT")).thenReturn(new BigDecimal("149.00")); when(rs.getBigDecimal("ACCOUNTING_AMOUNT")).thenReturn(sum);
        doAnswer(call->{
            String sql=call.getArgument(0);
            assertThat(sql).contains("SUM(SUM_UCHET)","COUNT(SUM_UCHET)","n.BRIEFORG=?","n.TYPE_DOC=?","WHERE UNICUM_NUM=?");
            assertThat(sql).doesNotContain("ISNULL(SUM_UCHET", "TEST", "UPDATE", "INSERT");
            RowMapper<FolioDocumentValuesDao.Values> mapper=call.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        return new FolioDocumentValuesDao(jdbc).read("TEST","EXPENSE","Р",123).get(0);
    }
    @Test void completeCostRetainsPrecision() throws Exception { var v=read(2,2,new BigDecimal("100.255")); assertThat(v.accountingAmount()).isEqualByComparingTo("100.26"); assertThat(v.shipmentAmount()).isEqualByComparingTo("149.00"); }
    @Test void partialCostIsUnknown() throws Exception { var v=read(2,1,new BigDecimal("50")); assertThat(v.accountingAmount()).isNull(); assertThat(v.missingAccountingLines()).isEqualTo(1); }
    @Test void emptyDocumentIsNotZeroCost() throws Exception { assertThat(read(0,0,null).accountingAmount()).isNull(); }
    @Test void explicitZeroCostIsRetained() throws Exception { assertThat(read(1,1,BigDecimal.ZERO).accountingAmount()).isEqualByComparingTo("0.00"); }
}
