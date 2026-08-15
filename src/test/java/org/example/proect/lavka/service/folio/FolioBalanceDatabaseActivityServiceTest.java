package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioBalanceDatabaseActivityDao;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FolioBalanceDatabaseActivityServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void reportsBlockedBeforeRunning() throws Exception {
        FolioBalanceDatabaseActivityDao dao = mock(FolioBalanceDatabaseActivityDao.class);
        when(dao.inspect()).thenReturn(new FolioBalanceDatabaseActivityDao.Inspection(
                2, 1, 1, 0, 0
        ));

        var response = new FolioBalanceDatabaseActivityService(dao, CLOCK).inspect();

        assertThat(response.ok()).isTrue();
        assertThat(response.state()).isEqualTo("BLOCKED");
        assertThat(response.detectedSessions()).isEqualTo(2);
        assertThat(response.blockedSessions()).isEqualTo(1);
    }

    @Test
    void doesNotExposeDatabaseExceptionToResponse() throws Exception {
        FolioBalanceDatabaseActivityDao dao = mock(FolioBalanceDatabaseActivityDao.class);
        when(dao.inspect()).thenThrow(new SQLException("login=secret; host=private"));

        var response = new FolioBalanceDatabaseActivityService(dao, CLOCK).inspect();

        assertThat(response.ok()).isFalse();
        assertThat(response.state()).isEqualTo("UNAVAILABLE");
        assertThat(response.warnings()).extracting(issue -> issue.message())
                .allMatch(message -> !message.contains("secret") && !message.contains("private"));
    }
}
