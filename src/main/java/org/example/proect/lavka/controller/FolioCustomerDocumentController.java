package org.example.proect.lavka.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentDetailResponse;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentsResponse;
import org.example.proect.lavka.service.folio.FolioCustomerDocumentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/customer-documents")
public class FolioCustomerDocumentController {

    private final FolioCustomerDocumentService service;

    @GetMapping
    @Operation(
            summary = "Документы клиента ФОЛИО",
            description = "Read-only список активных счетов, расходных накладных и платежей клиента за период"
    )
    public ResponseEntity<FolioCustomerDocumentsResponse> list(
            @Parameter(description = "Точное краткое имя _PARTNER.N_USER", required = true)
            @RequestParam String partnerShortName,
            @Parameter(description = "Начало периода; по умолчанию последний год")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @Parameter(description = "Конец периода включительно; по умолчанию текущая бизнес-дата")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @Parameter(description = "ACCOUNT,EXPENSE,PAYMENT через запятую или all")
            @RequestParam(required = false) String types,
            @Parameter(description = "Размер страницы от 1 до 100")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Непрозрачный nextCursor из предыдущего ответа")
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(service.list(
                partnerShortName, dateFrom, dateTo, types, limit, cursor
        ));
    }

    @GetMapping("/{documentType}/{documentId}")
    @Operation(
            summary = "Детали документа клиента ФОЛИО",
            description = "Возвращает шапку, товарные строки и связанные платежи либо детализацию распределения платежа"
    )
    public ResponseEntity<FolioCustomerDocumentDetailResponse> get(
            @Parameter(description = "ACCOUNT, EXPENSE или PAYMENT", required = true)
            @PathVariable String documentType,
            @PathVariable long documentId,
            @Parameter(description = "Точное краткое имя _PARTNER.N_USER", required = true)
            @RequestParam String partnerShortName) {
        return ResponseEntity.ok(service.get(partnerShortName, documentType, documentId));
    }
}
