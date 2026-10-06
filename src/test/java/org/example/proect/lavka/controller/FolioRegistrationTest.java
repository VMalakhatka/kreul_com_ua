package org.example.proect.lavka.controller;

import org.example.proect.lavka.service.folio.FolioPartnerService;
import org.example.proect.lavka.dto.folio.FolioRegistrationCustomer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioRegistrationTest {
    @Test void disabledAndUnauthorizedRequestsNeverReadContacts() {
        var service = mock(FolioPartnerService.class);
        var controller = new FolioPartnerController(service);
        assertEquals(503, controller.registration("TEST", null).getStatusCode().value());
        ReflectionTestUtils.setField(controller, "importToken", "test-only-not-a-secret-1234567890123456");
        assertEquals(401, controller.registration("TEST", null).getStatusCode().value());
        assertEquals(401, controller.registration("TEST", "wrong").getStatusCode().value());
        verifyNoInteractions(service);
    }
    @Test void exactCustomerRequiresTokenAndDoesNotCacheContacts() {
        var service = mock(FolioPartnerService.class);
        var controller = new FolioPartnerController(service);
        String token = "test-only-not-a-secret-1234567890123456";
        ReflectionTestUtils.setField(controller, "importToken", token);
        var customer = new FolioRegistrationCustomer("ТЕСТ", "Test", "H", "test@example.invalid", null, null, null, null, null, null, null, null, null, null);
        when(service.registrationCustomer("ТЕСТ")).thenReturn(customer);
        var response = controller.registration("ТЕСТ", token);
        assertEquals(customer, response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(400, controller.registration("123456789", token).getStatusCode().value());
        assertEquals(404, controller.registration("UNKNOWN", token).getStatusCode().value());
        verify(service, never()).registrationCustomer("123456789");
    }
}
