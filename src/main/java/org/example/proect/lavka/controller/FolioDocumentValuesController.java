package org.example.proect.lavka.controller;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.folio.FolioDocumentValuesService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Private manager route; customer document responses deliberately omit cost. */
@RestController
@RequestMapping("/admin/folio/customer-documents")
public class FolioDocumentValuesController {
    private final FolioDocumentValuesService service;
    private final LavkaApiProperties properties;
    public FolioDocumentValuesController(FolioDocumentValuesService service,LavkaApiProperties properties) { this.service=service; this.properties=properties; }
    @GetMapping("/{documentType}/{documentId}/values")
    public ResponseEntity<FolioDocumentValuesService.Result> read(@PathVariable String documentType,
            @PathVariable long documentId,@RequestParam String partnerShortName,
            @RequestHeader(value="X-Auth-Token",required=false) String supplied) {
        String expected=properties.getToken();
        if(expected==null || expected.isBlank()) return ResponseEntity.status(503).build();
        if(supplied==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(partnerShortName,documentType,documentId));
    }
}
