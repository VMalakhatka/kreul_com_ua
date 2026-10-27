package org.example.proect.lavka.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.example.proect.lavka.dto.folio.FolioOrderAccountRequest;
import org.example.proect.lavka.dto.folio.FolioOrderAccountResponse;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.folio.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Credentials are server-to-server only; WordPress separately requires manager capability/nonce. */
@RestController
@RequestMapping("/admin/folio/manager-order-accounts/v2")
public class FolioManagerOrderController {
    private final FolioOrderAccountService service;
    private final LavkaApiProperties properties;
    public FolioManagerOrderController(FolioOrderAccountService service, LavkaApiProperties properties) { this.service=service; this.properties=properties; }
    public record Command(@NotNull @Valid FolioOrderAccountRequest order, @NotNull ManagerStockPool stockPool) {}
    @PostMapping
    public ResponseEntity<FolioOrderAccountResponse> create(@Valid @RequestBody Command command,
            @RequestHeader(value="X-Auth-Token",required=false) String supplied) {
        String expected=properties.getToken();
        if(expected==null || expected.isBlank()) return ResponseEntity.status(503).build();
        if(supplied==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        var result=service.createManagerOrder(command.order(),command.stockPool());
        return ResponseEntity.status(result.previewOnly()?200:201).body(result);
    }
}
