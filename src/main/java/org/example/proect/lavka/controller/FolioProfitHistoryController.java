package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.*;
import org.example.proect.lavka.service.folio.FolioProfitHistoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/folio/profit-report/saved")
public class FolioProfitHistoryController {
    private final FolioProfitHistoryService service;
    public FolioProfitHistoryController(FolioProfitHistoryService service) { this.service=service; }
    @GetMapping public Range range(@RequestParam String fromMonth,@RequestParam String toMonth) { return service.range(fromMonth,toMonth); }
    @GetMapping("/{month}") public Revision get(@PathVariable String month,@RequestParam(required=false) Long revisionId) { return service.get(month,revisionId); }
    @GetMapping("/{month}/revisions") public History revisions(@PathVariable String month,@RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) Long beforeRevisionId) { return service.revisions(month,limit,beforeRevisionId); }
    @PostMapping("/{month}/calculate") public Revision calculate(@PathVariable String month,@RequestBody CalculateRequest request) { return service.calculate(month,request); }
}
