package org.example.proect.lavka.controller;
import org.example.proect.lavka.dao.folio.FolioProductCardDao;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FolioProductCardControllerTest {
    @Test void failsClosedAndRejectsBadSelectionBeforeDao() {
        var dao=mock(FolioProductCardDao.class); var p=new LavkaApiProperties();
        var c=new FolioProductCardController(dao,p);var req=new FolioProductCardController.Request("compare",List.of());
        p.setToken("");assertEquals(503,c.read(req,"x").getStatusCode().value());
        p.setToken("test-only");assertEquals(401,c.read(req,null).getStatusCode().value());
        assertEquals(400,c.read(new FolioProductCardController.Request("write",List.of()),"test-only").getStatusCode().value());
        verifyNoInteractions(dao);
    }
    @Test void safeErrorsAndNoStoreOnSuccess() {
        var dao=mock(FolioProductCardDao.class);var p=new LavkaApiProperties();p.setToken("test-only");var c=new FolioProductCardController(dao,p);
        var req=new FolioProductCardController.Request("direct",List.of("001","001"));
        when(dao.read("direct",List.of("001"))).thenThrow(new RuntimeException("private SQL detail"));
        var failure=c.read(req,"test-only");assertEquals(503,failure.getStatusCode().value());
        assertFalse(failure.getBody().toString().contains("private"));
        doReturn(new FolioProductCardDao.Result(true,"Paint_Ua","direct",new FolioProductCardDao.Data(List.of(),List.of()),null,0,null,List.of("001"))).when(dao).read("direct",List.of("001"));
        var success=c.read(req,"test-only");assertEquals(200,success.getStatusCode().value());assertEquals("no-store",success.getHeaders().getCacheControl());
    }
    @Test void reportsPermissionDenialWithoutLeakingDatabaseDetails() {
        var dao=mock(FolioProductCardDao.class);var p=new LavkaApiProperties();p.setToken("test-only");
        when(dao.read("view",List.of())).thenThrow(new org.springframework.jdbc.UncategorizedSQLException("read","private SQL",new java.sql.SQLException("private server","42000",229)));
        var response=new FolioProductCardController(dao,p).read(new FolioProductCardController.Request("view",List.of()),"test-only");
        assertEquals(503,response.getStatusCode().value());assertTrue(response.getBody().toString().contains("CARD_QUERY_ACCESS_DENIED"));assertFalse(response.getBody().toString().contains("private"));
    }
}
