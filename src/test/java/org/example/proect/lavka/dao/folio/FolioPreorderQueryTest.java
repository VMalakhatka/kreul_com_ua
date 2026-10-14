package org.example.proect.lavka.dao.folio;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class FolioPreorderQueryTest {
    @Test
    void bindsInclusiveSixCalendarMonthWindowAndExclusiveNextDay() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        var dao = new FolioCustomerBalanceDao(mock(DataSource.class));
        dao.loadPreorders(connection, "TEST", LocalDate.of(2026, 10, 14), List.of(7, 1));
        verify(statement).setString(1, "TEST");
        verify(statement).setTimestamp(2, Timestamp.valueOf("2026-04-14 00:00:00"));
        verify(statement).setTimestamp(3, Timestamp.valueOf("2026-10-15 00:00:00"));
        verify(statement).setInt(4, 7);
        verify(statement).setInt(5, 1);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("n.TYPE_DOC = 'С'", "n.STND_UCHET = 0", "'*РАЗОВАЯ'", "LIKE 'ПРД%'",
                "n.DATE_P_POR >= ? AND n.DATE_P_POR < ?", "NOT EXISTS", "m.ID_SCLAD NOT IN (?,?)");
        assertThat(sql.getValue()).doesNotContain("SCL_PLAT", "SCL_PMOV");
    }

    @Test
    void sixMonthsClampsToLastDayOfShorterMonth() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        new FolioCustomerBalanceDao(mock(DataSource.class)).loadPreorders(
                connection, "A", LocalDate.of(2026, 8, 31), List.of());
        verify(statement).setTimestamp(2, Timestamp.valueOf("2026-02-28 00:00:00"));
        verify(statement).setTimestamp(3, Timestamp.valueOf("2026-09-01 00:00:00"));
    }
}
