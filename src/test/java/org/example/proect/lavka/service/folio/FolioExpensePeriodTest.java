package org.example.proect.lavka.service.folio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class FolioExpensePeriodTest {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 18);

    @ParameterizedTest
    @ValueSource(strings = {"2026 07", "2026  07", "2026\t07", "2026\u00a007", "2026\n07", "2026\u202f07", "2026 07; 2026 07"})
    void resolvesSupportedWhitespaceAndRepeatedSameMonth(String text) {
        var p = FolioExpensePeriod.resolve(text, DATE);
        assertThat(p.month().toString()).isEqualTo("2026-07");
        assertThat(p.source()).isEqualTo("EXPLICIT_NOTE");
        assertThat(p.status()).isEqualTo("VALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026", "2026 13", "2026 00", "2026 7", "2026 071", "договор 05.07.2026", "договор 2026-07-05", "2026 07; договор 2025"})
    void unresolvedYearIsNotSuccessfulDateFallback(String text) {
        var p = FolioExpensePeriod.resolve(text, DATE);
        assertThat(p.status()).isEqualTo("UNRESOLVED_PERIOD_MARKER");
        assertThat(p.problem()).isTrue();
        assertThat(p.excluded()).isFalse();
        assertThat(p.evidence()).doesNotContain("договор");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026 07-08", "2026 07–08", "2026 07 — 08", "2026 07/08"})
    void mixedMonthRetainsLegacyFirstMonthButRequiresReview(String text) {
        var p = FolioExpensePeriod.resolve(text, DATE);
        assertThat(p.month().toString()).isEqualTo("2026-07");
        assertThat(p.status()).isEqualTo("MIXED_EXPLICIT_PERIOD");
        assertThat(p.excluded()).isFalse();
    }

    @Test void differentExplicitPeriodsAreExcludedAndNoYearUsesDocumentDate() {
        var p = FolioExpensePeriod.resolve("2025 12; 2026 01", DATE);
        assertThat(p.status()).isEqualTo("AMBIGUOUS_EXPLICIT_PERIOD");
        assertThat(p.excluded()).isTrue();
        for (String text : new String[] {null, "", "за июль", "за 07 месяц", "1202607"}) {
            assertThat(FolioExpensePeriod.resolve(text, DATE).status()).isEqualTo("NO_PERIOD");
            assertThat(FolioExpensePeriod.resolve(text, DATE).month().toString()).isEqualTo("2026-08");
        }
    }
}
