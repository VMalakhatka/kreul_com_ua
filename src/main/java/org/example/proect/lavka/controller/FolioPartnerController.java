package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.example.proect.lavka.dto.folio.FolioRegistrationCustomer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RequestHeader;
import org.example.proect.lavka.dto.folio.FolioPartnersResponse;
import org.example.proect.lavka.service.folio.FolioPartnerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/partners")
public class FolioPartnerController {

    private final FolioPartnerService service;

    // Dedicated fail-closed authorization for personal registration data.
    @Value("${folio.customer-import.token:${FOLIO_CUSTOMER_IMPORT_TOKEN:}}")
    private String importToken = "";

    @GetMapping("/registration")
    public ResponseEntity<FolioRegistrationCustomer> registration(
            @RequestParam String id,
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        if (importToken.length() < 32) return ResponseEntity.status(503).build();
        if (token == null || !MessageDigest.isEqual(
                importToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        if (id == null || id.isBlank() || id.length() > 8) return ResponseEntity.badRequest().build();
        var customer = service.registrationCustomer(id.trim());
        return customer == null ? ResponseEntity.notFound().build() : ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(customer);
    }

    /** Read-only page lookup, protected like the registration contact endpoint. */
    @org.springframework.web.bind.annotation.PostMapping("/registration-emails")
    public ResponseEntity<java.util.Map<String, String>> registrationEmails(
            @org.springframework.web.bind.annotation.RequestBody java.util.List<String> ids,
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        if (importToken.length() < 32) return ResponseEntity.status(503).build();
        if (token == null || !MessageDigest.isEqual(importToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        if (ids == null || ids.isEmpty() || ids.size() > 25 || ids.stream().anyMatch(
                id -> id == null || id.isBlank() || id.length() > 8)) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.registrationEmails(ids.stream().map(String::trim).distinct().toList()));
    }

    @GetMapping
    public ResponseEntity<FolioPartnersResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return ResponseEntity.ok(service.search(q, types, limit, offset));
    }
}
