package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.stock.StockQueryRequest;
import org.example.proect.lavka.dto.stock.StockQueryResponse;
import org.example.proect.lavka.service.stock.StockQueryQuantytiService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/stock")
@RequiredArgsConstructor
@Validated
public class StockController {

    private final StockQueryQuantytiService stockService;

    // POST /admin/stock/stock/query
    @PostMapping(value = "/stock/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public StockQueryResponse queryStocks(@RequestBody StockQueryRequest req) {
        return stockService.resolve(req);
    }
}