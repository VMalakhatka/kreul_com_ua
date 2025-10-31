package org.example.proect.lavka.controller;


import org.example.proect.lavka.dto.sync.SyncRunRequest;
import org.example.proect.lavka.dto.sync.SyncRunResponse;
import org.example.proect.lavka.service.StockSyncService;
import org.example.proect.lavka.service.SyncService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/sync")
public class SyncController {

    private final StockSyncService sync;
    private final SyncService syncService;

    public SyncController(StockSyncService sync, SyncService syncService) {
        this.sync = sync;
        this.syncService=syncService;
    }

    @PostMapping(
            path = "/run",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SyncRunResponse runOneBatch(@RequestBody SyncRunRequest req) {

        // пробрасываем всё в сервис
        return syncService.runOneBatch(
                req.limit(),
                req.pageSizeWoo(),
                req.cursorAfter(),
                req.dryRun()
        );
    }

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
