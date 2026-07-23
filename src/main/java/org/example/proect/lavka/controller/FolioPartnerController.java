package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
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

    @GetMapping
    public ResponseEntity<FolioPartnersResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return ResponseEntity.ok(service.search(q, types, limit, offset));
    }
}
