package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.service.folio.FolioProfitReportService;
import org.example.proect.lavka.service.folio.FolioProfitReportService.Request;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/admin/folio/profit-report")
public class FolioProfitReportController {

    private final FolioProfitReportService service;

    public FolioProfitReportController(FolioProfitReportService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<FolioProfitReportResponse> report(
            @RequestParam String month,
            @RequestParam(required = false) BigDecimal odesaTaxShare,
            @RequestParam(required = false) BigDecimal rubToUahRate,
            @RequestParam(required = false) BigDecimal odesaMasterClassIncome,
            @RequestParam(required = false) BigDecimal odesaMasterClassReturn,
            @RequestParam(required = false) BigDecimal odesaAdditionalSalary) {
        return ResponseEntity.ok(service.calculate(request(month, odesaTaxShare, rubToUahRate,
                odesaMasterClassIncome, odesaMasterClassReturn, odesaAdditionalSalary), false));
    }

    @GetMapping("/audit")
    public ResponseEntity<FolioProfitReportResponse> audit(
            @RequestParam String month,
            @RequestParam(required = false) BigDecimal odesaTaxShare,
            @RequestParam(required = false) BigDecimal rubToUahRate,
            @RequestParam(required = false) BigDecimal odesaMasterClassIncome,
            @RequestParam(required = false) BigDecimal odesaMasterClassReturn,
            @RequestParam(required = false) BigDecimal odesaAdditionalSalary) {
        return ResponseEntity.ok(service.calculate(request(month, odesaTaxShare, rubToUahRate,
                odesaMasterClassIncome, odesaMasterClassReturn, odesaAdditionalSalary), true));
    }

    private static Request request(
            String month,
            BigDecimal odesaTaxShare,
            BigDecimal rubToUahRate,
            BigDecimal odesaMasterClassIncome,
            BigDecimal odesaMasterClassReturn,
            BigDecimal odesaAdditionalSalary) {
        return new Request(month, odesaTaxShare, rubToUahRate,
                odesaMasterClassIncome, odesaMasterClassReturn, odesaAdditionalSalary);
    }
}
