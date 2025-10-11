package org.example.proect.lavka.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.stock.*;
import org.example.proect.lavka.service.stock.NoMovementService;
import org.example.proect.lavka.service.stock.StockMovementSyncService;
import org.example.proect.lavka.service.stock.StockQueryQuantytiService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/stock")
@RequiredArgsConstructor
@Validated
public class StockController {

    private final StockQueryQuantytiService stockService;
    private final StockMovementSyncService stockMovementSyncService;
    private final NoMovementService noMovementService;

    // POST /admin/stock/stock/query
    @PostMapping(value = "/stock/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StockQueryResponse queryStocks(@RequestBody StockQueryRequest req) {
        return stockService.resolve(req);
    }
    // StockController.java (фрагмент)
    @PostMapping("/stock/movements")
    public ResponseEntity<?> movements(@RequestBody StockMovementRequest req) {
        var out = stockMovementSyncService.resolve(req);
        return ResponseEntity.ok(out);
    }

    // StockController.java (фрагмент)
    @PostMapping("/stock/no-movement")
    public StockNoMovementResponsePage noMovement(@RequestBody @Valid StockNoMovementRequest req) {
        return noMovementService.resolve(req);
    }

}