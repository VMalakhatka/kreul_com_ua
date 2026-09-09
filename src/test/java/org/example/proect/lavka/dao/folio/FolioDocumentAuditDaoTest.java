package org.example.proect.lavka.dao.folio;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioDocumentAuditDaoTest {
    @Test void readsBothDirectionsAllWarehousesWithBoundedParameterizedKeyset() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        new FolioDocumentAuditDao(jdbc).page(LocalDate.of(2026,8,1),LocalDate.of(2026,9,1),7,100,201);
        var sql=ArgumentCaptor.forClass(String.class);
        var setter=ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).query(sql.capture(),setter.capture(),any(RowMapper.class));
        assertThat(sql.getValue()).contains("SELECT TOP 201", "WITH (NOLOCK)", "ORDER BY p.UNICUM_PLT", "p.IST_INF", "p.TYPE_POR");
        String where=sql.getValue().substring(sql.getValue().indexOf("WHERE"));
        assertThat(where).doesNotContain("TYPE_POR", "ID_SCLAD", "VID_DOC", "STND_UCHET", "OFFSET", "JOIN");
        var statement=mock(PreparedStatement.class);
        setter.getValue().setValues(statement);
        verify(statement).setQueryTimeout(30);
        verify(statement).setObject(1,Timestamp.valueOf("2026-08-01 00:00:00"));
        verify(statement).setObject(2,Timestamp.valueOf("2026-09-01 00:00:00"));
        verify(statement).setObject(3,7L);
        verify(statement).setObject(4,100L);
    }
    @Test void rejectsUnboundedTopBeforeDatabaseAccess() {
        var jdbc=mock(JdbcTemplate.class);
        assertThatThrownBy(() -> new FolioDocumentAuditDao(jdbc).page(LocalDate.now(),LocalDate.now(),0,1,502))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
