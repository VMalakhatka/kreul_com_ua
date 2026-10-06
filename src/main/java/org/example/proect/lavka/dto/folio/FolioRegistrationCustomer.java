package org.example.proect.lavka.dto.folio;
import java.math.BigDecimal;
/** Read-only registration data; organization type does not define a Woo price role. */
public record FolioRegistrationCustomer(String id, String name, String type, String email,
        String phone, String alternatePhone, String address, String postcode,
        String deliveryAddress, BigDecimal discountPercent) {}
