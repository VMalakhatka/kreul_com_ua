package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.folio.*;
import org.example.proect.lavka.service.folio.FolioProfitScopeSettingsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/folio/profit-report")
public class FolioProfitScopeSettingsController {
    private final FolioProfitScopeSettingsService service;
    public FolioProfitScopeSettingsController(FolioProfitScopeSettingsService service) {this.service=service;}
    @GetMapping("/scope-settings") public FolioProfitScopeSettings get() {return service.get();}
    @PutMapping("/scope-settings") public FolioProfitScopeSettings put(@RequestBody FolioProfitScopeSettings request) {return service.put(request);}
    @GetMapping("/scope-catalog") public FolioProfitScopeCatalog catalog() {return service.catalog();}
}
