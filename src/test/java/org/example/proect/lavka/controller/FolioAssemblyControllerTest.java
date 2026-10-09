package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioAssemblyDao;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.service.folio.FolioAssemblyGraph;
import java.util.Map;

class FolioAssemblyControllerTest {
    @Test void unauthenticatedAndInvalidRequestsNeverQueryFolio() {
        var dao = mock(FolioAssemblyDao.class); var props = new LavkaApiProperties();
        var controller = new FolioAssemblyController(dao, props);
        var request = new FolioAssemblyController.Request("Paint_Ua",List.of(1),List.of("P"));
        assertEquals(503, controller.graph(request,null).getStatusCode().value());
        props.setToken("test-only-token");
        assertEquals(401, controller.graph(request,"wrong").getStatusCode().value());
        assertEquals(400, controller.graph(new FolioAssemblyController.Request("OTHER",List.of(1),List.of("P")),"test-only-token").getStatusCode().value());
        verifyNoInteractions(dao);
        controller.graph(request,"test-only-token");
        verify(dao).read("Paint_Ua",List.of(1),List.of("P"));
    }
    @Test void successfulGraphUsesTheWordPressResponseEnvelope() throws Exception {
        var dao = mock(FolioAssemblyDao.class); var props = new LavkaApiProperties();
        props.setToken("test-only-token");
        when(dao.read("Paint_Ua", List.of(7), List.of("P"))).thenReturn(
                FolioAssemblyGraph.build(List.of("P"), List.of(), Map.of("P", java.util.Set.of(0))));
        var response = new FolioAssemblyController(dao, props).graph(
                new FolioAssemblyController.Request("Paint_Ua", List.of(7), List.of("P")), "test-only-token");
        var json = new ObjectMapper().valueToTree(response.getBody());
        assertTrue(json.get("ok").asBoolean()); assertEquals(2, json.get("version").asInt());
        assertEquals(1, json.get("nodes").size());
    }
    @Test void runtimeTokenPropertyIsExplicitlyBound() throws Exception {
        var properties = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) { properties.load(input); }
        assertEquals("${LAVKA_TOKEN:}", properties.getProperty("lavka.token"));
    }
}
