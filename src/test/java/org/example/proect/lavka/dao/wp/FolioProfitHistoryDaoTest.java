package org.example.proect.lavka.dao.wp;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitHistoryDaoTest {
    @Test void latestResultIsSourceAndMonthScopedBoundedReadWithNoPublicationPreference() {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProfitHistoryDao(jdbc);
        assertThat(dao.latestResult("Paint_Ua","2026-09",99)).isEmpty();
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(org.springframework.jdbc.core.RowMapper.class),eq("Paint_Ua"),eq("2026-09"),eq(99L));
        assertThat(sql.getValue()).contains("source_database=? AND report_month=? AND id<=?",
                "status IN ('COMPLETED','PROVISIONAL')", "report_json IS NOT NULL",
                "TRIM(report_json) NOT IN ('','null')", "ORDER BY id DESC LIMIT 1")
                .doesNotContain("published_revision_id","UPDATE","INSERT","DELETE");
        verifyNoMoreInteractions(jdbc);
    }
    @Test void finalizationIsGuardedAndPublicationMonotonicInOneApplicationTransaction() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var dao=new FolioProfitHistoryDao(jdbc);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        dao.finish("Paint_Ua","2025-07",10,"COMPLETED","{}",true,null);
        var order=inOrder(jdbc);
        order.verify(jdbc).update(contains("AND status='RUNNING'"),eq("COMPLETED"),eq("{}"),eq(true),isNull(),eq("Paint_Ua"),eq("2025-07"),eq(10L));
        order.verify(jdbc).update(contains("published_revision_id<?"),eq(10L),eq("Paint_Ua"),eq("2025-07"),eq("COMPLETED"),eq(10L));
        var annotation=FolioProfitHistoryDao.class.getMethod("finish",String.class,String.class,long.class,String.class,String.class,boolean.class,String.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.transactionManager()).isEqualTo("wpTransactionManager");
    }
    @Test void failedRevisionNeverPublishesAndTerminalCannotBeOverwritten() {
        var jdbc=mock(JdbcTemplate.class); var dao=new FolioProfitHistoryDao(jdbc);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        dao.finish("Paint_Ua","2025-07",10,"FAILED",null,false,"ERROR");
        verify(jdbc,times(1)).update(anyString(),any(Object[].class));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(0);
        assertThatThrownBy(()->dao.finish("Paint_Ua","2025-07",10,"COMPLETED","{}",true,null))
                .isInstanceOf(IllegalStateException.class);
    }
}
