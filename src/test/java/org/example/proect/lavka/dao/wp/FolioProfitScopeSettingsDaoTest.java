package org.example.proect.lavka.dao.wp;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitScopeSettingsDaoTest {
    @Test void concurrentFirstInsertIsConflictNotOverwrite() {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProfitScopeSettingsDao(jdbc);
        when(jdbc.update(startsWith("INSERT INTO folio_profit_scope_settings"),eq("payload")))
                .thenReturn(1).thenThrow(new DuplicateKeyException("race"));
        assertThat(dao.compareAndSet(0,"payload")).isTrue();
        assertThat(dao.compareAndSet(0,"payload")).isFalse();
        verify(jdbc,times(2)).update(startsWith("INSERT INTO folio_profit_scope_settings"),eq("payload"));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void updateUsesExpectedVersionAndDoesNotRetryAfterConflict() {
        var jdbc=mock(JdbcTemplate.class);var dao=new FolioProfitScopeSettingsDao(jdbc);
        when(jdbc.update(contains("WHERE id=1 AND version=?"),eq("next"),eq(5L))).thenReturn(1,0);
        assertThat(dao.compareAndSet(5,"next")).isTrue();assertThat(dao.compareAndSet(5,"next")).isFalse();
        verify(jdbc,times(2)).update(contains("SET version=version+1"),eq("next"),eq(5L));
        verifyNoMoreInteractions(jdbc);
    }
}
