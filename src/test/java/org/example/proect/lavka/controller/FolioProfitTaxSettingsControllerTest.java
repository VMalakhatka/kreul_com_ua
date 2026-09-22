package org.example.proect.lavka.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.wp.FolioProfitTaxSettingsDao;
import org.example.proect.lavka.service.folio.FolioProfitTaxSettingsService;
import org.example.proect.lavka.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FolioProfitTaxSettingsControllerTest {
    private final FolioProfitTaxSettingsDao dao=mock(FolioProfitTaxSettingsDao.class);
    private MockMvc mvc;
    private static final String URL="/admin/folio/profit-report/tax-settings";
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new FolioProfitTaxSettingsController(new FolioProfitTaxSettingsService(dao,new ObjectMapper())))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void getReturnsBareSettingsWithDefaultsAndNoWrites() throws Exception {
        when(dao.get()).thenReturn(Optional.empty());
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.retailFirmCodes[0]").value("МИХНФОП"))
                .andExpect(jsonPath("$.wholesaleFirmCodes[0]").value("КУЗНФОП"));
        verify(dao).get(); verifyNoMoreInteractions(dao);
    }
    @Test void putChangesBothListsAndRejectsStaleVersionWithContractCode() throws Exception {
        String body="{\"version\":0,\"retailFirmCodes\":[\"ФОП1\"],\"wholesaleFirmCodes\":[]}";
        when(dao.compareAndSet(anyLong(),anyString(),anyString())).thenReturn(true,false);
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.retailFirmCodes[0]").value("ФОП1"));
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFIT_TAX_SETTINGS_VERSION_CONFLICT"));
    }
    @Test void overlapAndMissingListsAreBadRequest() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"retailFirmCodes\":[\"ФОП1\"],\"wholesaleFirmCodes\":[\"ФОП1\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFIT_TAX_FIRM_OVERLAP"));
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFIT_TAX_SETTINGS_INVALID"));
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1.5}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dao);
    }
}
