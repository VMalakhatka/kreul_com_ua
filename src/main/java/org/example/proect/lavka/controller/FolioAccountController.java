package org.example.proect.lavka.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.AddFolioAccountItemRequest;
import org.example.proect.lavka.dto.folio.CreateFolioAccountRequest;
import org.example.proect.lavka.dto.folio.FolioAccountMutationResponse;
import org.example.proect.lavka.dto.folio.FolioAccountResponse;
import org.example.proect.lavka.dto.folio.FolioAccountSummaryResponse;
import org.example.proect.lavka.dto.folio.UpdateFolioAccountItemQuantityRequest;
import org.example.proect.lavka.service.folio.FolioAccountService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/accounts")
public class FolioAccountController {

    private final FolioAccountService service;

    @PostMapping
    public ResponseEntity<FolioAccountMutationResponse> create(@Valid @RequestBody CreateFolioAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FolioAccountMutationResponse(true, service.create(request)));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<FolioAccountResponse> get(@PathVariable long documentId) {
        return ResponseEntity.ok(service.get(documentId));
    }

    @GetMapping
    public ResponseEntity<List<FolioAccountSummaryResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String payerName,
            @RequestParam(required = false) List<Integer> warehouseIds) {
        return ResponseEntity.ok(service.list(dateFrom, dateTo, payerName, warehouseIds));
    }

    @PatchMapping("/{documentId}/items/{recno}/quantity")
    public ResponseEntity<FolioAccountMutationResponse> updateQuantity(@PathVariable long documentId,
                                                                       @PathVariable long recno,
                                                                       @Valid @RequestBody UpdateFolioAccountItemQuantityRequest request) {
        return ResponseEntity.ok(new FolioAccountMutationResponse(true, service.updateQuantity(documentId, recno, request)));
    }

    @PatchMapping("/{documentId}/items/{recno}")
    public ResponseEntity<FolioAccountMutationResponse> updateItem(@PathVariable long documentId,
                                                                   @PathVariable long recno,
                                                                   @Valid @RequestBody UpdateFolioAccountItemQuantityRequest request) {
        return updateQuantity(documentId, recno, request);
    }

    @PostMapping("/{documentId}/items")
    public ResponseEntity<FolioAccountMutationResponse> addLine(@PathVariable long documentId,
                                                                @Valid @RequestBody AddFolioAccountItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FolioAccountMutationResponse(true, service.addLine(documentId, request)));
    }

    @DeleteMapping("/{documentId}/items/{recno}")
    public ResponseEntity<FolioAccountMutationResponse> deleteLine(@PathVariable long documentId,
                                                                   @PathVariable long recno) {
        return ResponseEntity.ok(new FolioAccountMutationResponse(true, service.deleteLine(documentId, recno)));
    }

    @PostMapping("/{documentId}/cancel")
    public ResponseEntity<FolioAccountMutationResponse> cancel(@PathVariable long documentId) {
        return ResponseEntity.ok(new FolioAccountMutationResponse(true, service.cancel(documentId)));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Object> deleteAccount(@PathVariable long documentId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(java.util.Map.of(
                        "success", false,
                        "error", java.util.Map.of(
                                "code", "ACCOUNT_DELETE_NOT_IMPLEMENTED",
                                "message", "Удаление всего счёта отключено до подтверждения поведения ФОЛИО"
                        ),
                        "accountId", documentId
                ));
    }
}
