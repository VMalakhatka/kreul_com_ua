package org.example.proect.lavka.controller;

import org.example.proect.lavka.service.folio.FolioProfitReportService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FolioProfitReportControllerTest {
    @ParameterizedTest @ValueSource(strings = {"", "/audit"})
    void bothRoutesAcceptIdenticalKyivAndOdesaOverrides(String suffix) throws Exception {
        var service = mock(FolioProfitReportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FolioProfitReportController(service)).build();
        mvc.perform(get("/admin/folio/profit-report" + suffix).param("month", "2026-07")
                .param("kyivAdditionalSalary","123.45").param("odesaAdditionalSalary","0"))
                .andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(FolioProfitReportService.Request.class);
        verify(service).calculate(request.capture(),eq(!suffix.isEmpty()));
        assertThat(request.getValue().kyivAdditionalSalary()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(request.getValue().odesaAdditionalSalary()).isZero();
    }
}
