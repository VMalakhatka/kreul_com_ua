package org.example.proect.lavka.dao.folio;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FolioProfitReportDaoTest {
    @Test void candidateQueryIsBroadForUnicodePeriodsAndNeverUsesCityWarehouseFilter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new FolioProfitReportDao(jdbc).findPaymentCandidates(LocalDate.of(2026,6,1),
                LocalDate.of(2026,9,1), "2026 07");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class),
                eq(Timestamp.valueOf("2026-06-01 00:00:00")),
                eq(Timestamp.valueOf("2026-09-01 00:00:00")), eq("%2026%07%"));
        assertThat(sql.getValue()).contains("p.TYPE_POR = 0", "OR p.DOCUMN_POR LIKE ?", "p.IST_INF", "p.UNICUM_PLT");
        assertThat(sql.getValue().substring(sql.getValue().indexOf("WHERE")))
                .doesNotContain("ID_SCLAD", "IST_INF", "ROW_NUMBER", "OFFSET");
    }
}
