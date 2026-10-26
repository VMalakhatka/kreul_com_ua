package org.example.proect.lavka.dao.folio;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class FolioCustomerDocumentFilterTest {
    @Test void filtersPrecedePaginationAndCursorRemainsGrouped() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        doAnswer(call->{
            String sql=call.getArgument(0);
            assertThat(sql).contains("TOP 51", "WAREHOUSE_ID IN (?,?)", "CHARINDEX(?,d.OPERATION_KIND)>0 AND (d.DOCUMENT_DATE", "n.L_CP1_PLAT AS SOURCE_INFO", "n.L_CP2_PLAT AS ADDITIONAL_INFO");
            assertThat(sql).doesNotContain("ПЕРЕМЕЩЕНИЕ", "OFFSET", "ROW_NUMBER");
            assertThat(call.getArguments()).contains(1,7,"ПЕРЕМЕЩЕНИЕ");
            return List.of();
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        new FolioCustomerDocumentDao(jdbc).findDocuments("TEST",LocalDate.of(2026,6,1),LocalDate.of(2026,6,30),
            List.of(FolioCustomerDocumentType.EXPENSE),51,new FolioCustomerDocumentDao.DocumentCursor(LocalDateTime.of(2026,6,20,0,0),1,123),false,List.of(1,7),"ПЕРЕМЕЩЕНИЕ");
        verify(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
}
