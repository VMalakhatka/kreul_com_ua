package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FolioCustomerDebtorsResponseJsonTest {

    @Test
    void serializesDateAsIsoStringAndUsesDistinctSummarySchemaName() throws Exception {
        var response = new FolioCustomerDebtorsResponse(
                true,
                LocalDate.of(2026, 8, 14),
                new FolioCustomerDebtorsResponse.DebtorsFilters(
                        new BigDecimal("100.00"), "", List.of("П", "Д", "К"),
                        50, 0, "payableNow_desc"
                ),
                new FolioCustomerDebtorsResponse.DebtorsSummary(
                        0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO
                ),
                List.of(),
                List.of(),
                List.of()
        );

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"asOfDate\":\"2026-08-14\"");
        assertThat(json).contains("\"debtors\":[]");
        assertThat(FolioCustomerDebtorsResponse.DebtorsSummary.class.getSimpleName())
                .isEqualTo("DebtorsSummary")
                .isNotEqualTo(FolioCustomerBalanceResponse.Summary.class.getSimpleName());
    }
}
