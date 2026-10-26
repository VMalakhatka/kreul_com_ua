package org.example.proect.lavka.controller;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.folio.FolioDocumentValuesService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class FolioDocumentValuesControllerTest {
    @Test void costReadsRequireServerTokenAndDisableCaching() {
        var service=mock(FolioDocumentValuesService.class); var props=mock(LavkaApiProperties.class);
        var controller=new FolioDocumentValuesController(service,props);
        assertThat(controller.read("EXPENSE",123,"TEST",null).getStatusCode().value()).isEqualTo(503);
        when(props.getToken()).thenReturn("synthetic-test-token");
        assertThat(controller.read("EXPENSE",123,"TEST",null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.read("EXPENSE",123,"TEST","wrong").getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(service);
        assertThat(controller.read("EXPENSE",123,"TEST","synthetic-test-token").getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(service).read("TEST","EXPENSE",123);
    }
}
