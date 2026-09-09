package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioDocumentAuditDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioDocumentAuditServiceTest {
    private FolioDocumentAuditDao dao;
    private FolioDocumentAuditService service;
    private final LocalDate from=LocalDate.of(2026,8,1), to=LocalDate.of(2026,8,31), until=LocalDate.of(2026,9,1);
    @BeforeEach void setup() { dao=mock(FolioDocumentAuditDao.class); service=new FolioDocumentAuditService(dao,new FolioDocumentAuditRules(),true); }

    @Test void paginatesWithoutLosingUnknownRowsOrClaimingCompleteRules() throws Exception {
        when(dao.upperId(from,until)).thenReturn(10L);
        when(dao.count(from,until,10)).thenReturn(2L);
        var first=FolioDocumentAuditRulesTest.salary(null,"2026 07");
        var last=new FolioDocumentAuditDao.Row(10,"1",from,1,true,true,java.math.BigDecimal.TEN,null,
                "UNKNOWN","Synthetic unknown",null,"UNKNOWN",null,null);
        when(dao.page(from,until,0,10,2)).thenReturn(List.of(first,last));
        var response=service.audit(from,to,1,0,null,null);
        assertThat(response.page().nextAfterPaymentId()).isEqualTo(1);
        assertThat(response.page().hasMore()).isTrue();
        assertThat(response.page().upperPaymentId()).isEqualTo(10);
        assertThat(response.summary().scope()).isEqualTo("PAGE");
        assertThat(response.summary().byStatus().get("ERROR")).isEqualTo(1);
        assertThat(response.coverage().rulesComplete()).isFalse();
        when(dao.page(from,until,1,10,2)).thenReturn(List.of(last));
        var second=service.audit(from,to,1,1,10L,FolioDocumentAuditRules.VERSION);
        assertThat(second.page().hasMore()).isFalse();
        assertThat(second.page().nextAfterPaymentId()).isNull();
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).category().code()).isEqualTo("UNCLASSIFIED");
        assertThat(second.items().get(0).document().direction()).isEqualTo("INCOMING");
        verify(dao,times(1)).upperId(from,until);
        String fixture=System.getProperty("folio.document.audit.fixture.output");
        if(fixture!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture),new ObjectMapper().findAndRegisterModules().writeValueAsString(response));
    }
    @Test void emptyRangeIsDistinctFromSourceFailure() {
        var empty=service.audit(from,to,200,0,null,null);
        assertThat(empty.ok()).isTrue();
        assertThat(empty.page().totalDocuments()).isZero();
        assertThat(empty.coverage().pageComplete()).isTrue();
        verify(dao,never()).count(any(),any(),anyLong());
        when(dao.upperId(from,until)).thenThrow(new QueryTimeoutException("sensitive raw SQL"));
        var failed=service.audit(from,to,200,0,null,null);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.page()).isNull();
        assertThat(failed.summary()).isNull();
        assertThat(failed.coverage().pageComplete()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("DOCUMENT_AUDIT_SOURCE_UNAVAILABLE");
        assertThat(failed.toString()).doesNotContain("sensitive raw SQL");
    }
    @Test void validatesDatesCursorSizeAndRuleVersionBeforeReading() {
        assertThatThrownBy(() -> service.audit(to,from,200,0,null,null)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(() -> service.audit(from,from.plusDays(366),200,0,null,null)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(() -> service.audit(from,to,501,0,null,null)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(() -> service.audit(from,to,200,5,null,null)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(() -> service.audit(from,to,200,5,4L,null)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(() -> service.audit(from,to,200,0,null,"old-version")).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao);
    }
    @Test void disabledAuditDoesNotReadDatabase() {
        service=new FolioDocumentAuditService(dao,new FolioDocumentAuditRules(),false);
        assertThatThrownBy(() -> service.audit(from,to,200,0,null,null)).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao);
    }
}
