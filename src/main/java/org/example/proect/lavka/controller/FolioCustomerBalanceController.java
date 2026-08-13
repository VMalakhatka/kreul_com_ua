package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.example.proect.lavka.service.folio.FolioCustomerBalanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/customer-balance")
public class FolioCustomerBalanceController {

    private final FolioCustomerBalanceService service;

    @GetMapping
    public ResponseEntity<FolioCustomerBalanceResponse> get(
            @RequestParam String partnerShortName,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) List<Integer> warehouseIds,
            @RequestParam(required = false) Boolean includeServicePayments) {
        return ResponseEntity.ok(service.get(
                partnerShortName,
                dateFrom,
                warehouseIds,
                includeServicePayments
        ));
    }
}
