package org.example.proect.lavka.controller;
import org.example.proect.lavka.dao.folio.FolioCurrentNetworkStockDao;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioCurrentNetworkStockControllerTest {
    @Test void checksCredentialsAndFullSourceScopeBeforeReading() {
        var dao = mock(FolioCurrentNetworkStockDao.class); var props = new LavkaApiProperties();
        var c = new FolioCurrentNetworkStockController(dao, props);
        var request = new FolioCurrentNetworkStockController.Request("Paint_Ua", List.of(9,7,1,7), List.of(7,1), List.of(" p ","P"));
        assertEquals(503, c.read(request, null).getStatusCode().value()); props.setToken("test-token");
        assertEquals(401, c.read(request, "bad").getStatusCode().value());
        assertEquals(400, c.read(new FolioCurrentNetworkStockController.Request("OTHER", List.of(7), List.of(7), List.of("P")), "test-token").getStatusCode().value());
        assertEquals(400, c.read(new FolioCurrentNetworkStockController.Request("Paint_Ua", List.of(7), List.of(1,7), List.of("P")), "test-token").getStatusCode().value());
        assertEquals(400, c.read(new FolioCurrentNetworkStockController.Request("Paint_Ua", List.of(1), List.of(1), List.of("P")), "test-token").getStatusCode().value());
        verifyNoInteractions(dao);
        c.read(request, "test-token"); verify(dao).read("Paint_Ua", List.of(1,7,9), List.of(1,7), List.of("P"));
    }
}
