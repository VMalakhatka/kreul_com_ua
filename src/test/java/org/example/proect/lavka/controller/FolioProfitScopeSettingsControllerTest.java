package org.example.proect.lavka.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitScopeCatalogDao;
import org.example.proect.lavka.dao.wp.FolioProfitScopeSettingsDao;
import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog.Warehouse;
import org.example.proect.lavka.exception.GlobalExceptionHandler;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.example.proect.lavka.service.folio.FolioProfitScopeSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FolioProfitScopeSettingsControllerTest {
    @Test void settingsCatalogSaveConflictAndBadInputHaveDocumentedHttpShape() throws Exception {
        var dao=mock(FolioProfitScopeSettingsDao.class);var source=mock(FolioProfitScopeCatalogDao.class);
        var json=new ObjectMapper();var properties=new FolioProfitReportProperties();
        when(source.warehouses()).thenReturn(List.of(new Warehouse(1,"Киев"),new Warehouse(5,"Одесса"),new Warehouse(7,"Опт"),new Warehouse(12,"Хранение")));
        when(source.groupPaths()).thenReturn(List.of(List.of("A","B")));
        var mvc=MockMvcBuilders.standaloneSetup(new FolioProfitScopeSettingsController(new FolioProfitScopeSettingsService(dao,source,json,properties)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String base="/admin/folio/profit-report";
        mvc.perform(get(base+"/scope-settings")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.kyivWarehouseIds[1]").value(7)).andExpect(jsonPath("$.excludedInventoryGroupIds").isEmpty());
        verifyNoInteractions(source);
        mvc.perform(get(base+"/scope-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].path[0]").value("A"))
                .andExpect(jsonPath("$.subgroups[0].level").value(2)).andExpect(jsonPath("$.subgroups[0].id").isString());
        var body=json.writeValueAsString(FolioProfitScopeSettingsService.defaults(properties));
        when(dao.compareAndSet(eq(0L),anyString())).thenReturn(true,false);
        mvc.perform(put(base+"/scope-settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(base+"/scope-settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFIT_SCOPE_SETTINGS_VERSION_CONFLICT"));
        mvc.perform(put(base+"/scope-settings").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFIT_SCOPE_SETTINGS_INVALID"));
        when(source.warehouses()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        mvc.perform(get(base+"/scope-catalog")).andExpect(status().isServiceUnavailable());
    }
}
