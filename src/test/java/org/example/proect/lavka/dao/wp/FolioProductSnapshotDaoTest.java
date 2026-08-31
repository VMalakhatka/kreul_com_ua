package org.example.proect.lavka.dao.wp;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class FolioProductSnapshotDaoTest {

    @Test
    void decimalValuesAreRoundedToThePublishedMariaDbScale() {
        assertThat(FolioProductSnapshotDao.scaled(new BigDecimal("86.09123456"), 4))
                .isEqualByComparingTo("86.0912");
        assertThat(FolioProductSnapshotDao.scaled(new BigDecimal("1.23456789"), 6))
                .isEqualByComparingTo("1.234568");
        assertThat(FolioProductSnapshotDao.scaled(new BigDecimal("-1.005"), 2))
                .isEqualByComparingTo("-1.01");
    }

    @Test
    void currentMetricInsertHasOneValueForEveryColumn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(new int[0][]).when(jdbc).batchUpdate(
                anyString(), anyCollection(), anyInt(), any());
        FolioProductSnapshotDao dao = new FolioProductSnapshotDao(jdbc);

        dao.stageCurrent(1L, "Paint_Rus", 5, LocalDateTime.now(),
                Collections.singletonList(null));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).batchUpdate(
                sql.capture(), anyCollection(), anyInt(), any());
        String statement = sql.getValue();
        int valuesStart = statement.indexOf("VALUES (");
        String columns = statement.substring(
                statement.indexOf('(') + 1, statement.lastIndexOf(')', valuesStart));
        String values = statement.substring(
                valuesStart + 8, statement.indexOf(")\nON DUPLICATE", valuesStart));

        assertThat(columns.split(",")).hasSameSizeAs(values.split(","));
        assertThat(values.chars().filter(character -> character == '?').count())
                .isEqualTo(72);
    }
}
