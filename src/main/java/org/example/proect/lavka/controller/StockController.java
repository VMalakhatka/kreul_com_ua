package org.example.proect.lavka.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.stock.*;
import org.example.proect.lavka.service.stock.NoMovementService;
import org.example.proect.lavka.service.stock.PriceService;
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
    private final PriceService priceService;

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

    // TODO:
// Folio stores DATE_PREDM without time (00:00:00 for all rows),
// therefore incremental synchronization currently works
// with whole-day granularity.
//
// In the future this endpoint should use the document
// timestamp/history table instead of DATE_PREDM to support
// true incremental synchronization.

    // StockController.java (фрагмент)
    @PostMapping("/stock/no-movement")
    public StockNoMovementResponsePage noMovement(@RequestBody @Valid StockNoMovementRequest req) {
        return noMovementService.resolve(req);
    }

    @PostMapping("/prices")
    public PriceQueryResponse prices(@RequestBody PriceQueryRequest req) {
        return priceService.resolve(req);
    }

}