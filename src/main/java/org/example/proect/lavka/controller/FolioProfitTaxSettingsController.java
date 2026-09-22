package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.example.proect.lavka.service.folio.FolioProfitTaxSettingsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/folio/profit-report/tax-settings")
public class FolioProfitTaxSettingsController {
    private final FolioProfitTaxSettingsService service;
    public FolioProfitTaxSettingsController(FolioProfitTaxSettingsService service) {this.service=service;}
    @GetMapping public FolioProfitTaxSettings get() {return service.get();}
    @PutMapping public FolioProfitTaxSettings put(@RequestBody FolioProfitTaxSettings settings) {return service.put(settings);}
}
