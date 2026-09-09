package org.example.proect.lavka.controller;

import org.example.proect.lavka.service.folio.*;
import org.example.proect.lavka.dao.folio.FolioDocumentAuditDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FolioDocumentAuditControllerTest {
    @Test void disabledUsesSharedValidationCodeAndSourceFailureUsesErrorCode() throws Exception {
        var dao=mock(FolioDocumentAuditDao.class);
        var disabled=MockMvcBuilders.standaloneSetup(new FolioDocumentAuditController(
                new FolioDocumentAuditService(dao,new FolioDocumentAuditRules(),false)))
                .setControllerAdvice(new org.example.proect.lavka.exception.GlobalExceptionHandler()).build();
        disabled.perform(get("/admin/folio/document-audit").param("dateFrom","2026-08-01").param("dateTo","2026-08-31"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOCUMENT_AUDIT_DISABLED"));
        verifyNoInteractions(dao);
        when(dao.upperId(any(),any())).thenThrow(new org.springframework.dao.QueryTimeoutException("synthetic failure"));
        var enabled=MockMvcBuilders.standaloneSetup(new FolioDocumentAuditController(
                new FolioDocumentAuditService(dao,new FolioDocumentAuditRules(),true))).build();
        enabled.perform(get("/admin/folio/document-audit").param("dateFrom","2026-08-01").param("dateTo","2026-08-31"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_AUDIT_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.coverage.pageComplete").value(false));
    }
    @Test void defaultsReturnHonestEmptyPageAndMalformedDateFailsBeforeDao() throws Exception {
        var dao=mock(FolioDocumentAuditDao.class);
        var mvc=MockMvcBuilders.standaloneSetup(new FolioDocumentAuditController(
                new FolioDocumentAuditService(dao,new FolioDocumentAuditRules(),true))).build();
        mvc.perform(get("/admin/folio/document-audit").param("dateFrom","2026-08-01").param("dateTo","2026-08-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.pageSize").value(200))
                .andExpect(jsonPath("$.coverage.rulesComplete").value(false));
        verify(dao).upperId(LocalDate.of(2026,8,1),LocalDate.of(2026,9,1));
        clearInvocations(dao);
        mvc.perform(get("/admin/folio/document-audit").param("dateFrom","bad").param("dateTo","2026-08-31"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dao);
    }
}
