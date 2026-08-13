package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FolioCustomerBalanceResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void serializesAllBalanceDatesAsIsoStrings() throws Exception {
        var filters = new FolioCustomerBalanceResponse.Filters(
                LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 13),
                List.of(),
                "ALL_WAREHOUSES",
                true
        );
        var row = new FolioCustomerBalanceResponse.Row(
                1,
                LocalDate.of(2026, 8, 14),
                "Р",
                "777414",
                LocalDate.of(2026, 8, 11),
                "555053Woo o 11.08.2026",
                BigDecimal.ZERO,
                new BigDecimal("1687.27"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1687.27"),
                null,
                LocalDate.of(2026, 8, 11),
                false,
                false,
                false,
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                753000L,
                7,
                "Киев ОПТ",
                "РАСХОДНИКИ"
        );

        String filtersJson = objectMapper.writeValueAsString(filters);
        String rowJson = objectMapper.writeValueAsString(row);

        assertThat(filtersJson)
                .contains("\"dateFrom\":\"2026-07-07\"")
                .contains("\"dateTo\":\"2026-08-13\"")
                .contains("\"asOfDate\":\"2026-08-13\"");
        assertThat(rowJson)
                .contains("\"controlDate\":\"2026-08-14\"")
                .contains("\"documentDate\":\"2026-08-11\"")
                .contains("\"invoiceDate\":\"2026-08-11\"");
    }
}
