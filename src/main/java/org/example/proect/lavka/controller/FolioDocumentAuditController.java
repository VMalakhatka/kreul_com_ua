package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.folio.FolioDocumentAuditResponse;
import org.example.proect.lavka.service.folio.FolioDocumentAuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/admin/folio/document-audit")
public class FolioDocumentAuditController {
    private final FolioDocumentAuditService service;
    public FolioDocumentAuditController(FolioDocumentAuditService service) { this.service=service; }
    @GetMapping
    public ResponseEntity<FolioDocumentAuditResponse> audit(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue="200") int pageSize,
            @RequestParam(defaultValue="0") long afterPaymentId,
            @RequestParam(required=false) Long upperPaymentId,
            @RequestParam(required=false) String expectedRulesVersion) {
        var response = service.audit(dateFrom,dateTo,pageSize,afterPaymentId,upperPaymentId,expectedRulesVersion);
        return ResponseEntity.status(response.ok() ? 200 : 503).body(response);
    }
}
