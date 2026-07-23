package org.example.proect.lavka.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.FolioOrderAccountRequest;
import org.example.proect.lavka.dto.folio.FolioOrderAccountResponse;
import org.example.proect.lavka.service.folio.FolioOrderAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/order-accounts")
public class FolioOrderAccountController {

    private final FolioOrderAccountService service;

    @PostMapping
    public ResponseEntity<FolioOrderAccountResponse> create(@Valid @RequestBody FolioOrderAccountRequest request) {
        FolioOrderAccountResponse response = service.createFromWooOrder(request);
        return ResponseEntity.status(response.previewOnly() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }
}
