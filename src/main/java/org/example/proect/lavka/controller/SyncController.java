package org.example.proect.lavka.controller;


import org.example.proect.lavka.service.StockSyncService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final StockSyncService sync;
    public SyncController(StockSyncService sync) { this.sync = sync; }

    /** (Опционально) Батч-инициализация SKU из твоей БД */
    @PostMapping("/init-sku")
    public Map<String, Object> initSku() {
        return sync.initSkusFromDb();
    }

    @PostMapping("/stock")
    public Map<String, Object> syncStock(@RequestParam String supplier,
                                         @RequestParam int stockId,
                                         @RequestParam(defaultValue = "false") boolean dry) {
        if (dry) return sync.dryRunBySupplierAndStock(supplier, stockId);
        return sync.syncBySupplierAndStock(supplier, stockId);
    }

}
