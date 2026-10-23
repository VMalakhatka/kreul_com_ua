package org.example.proect.lavka.controller;

import org.example.proect.lavka.dto.sync.SyncRunRequest;
import org.example.proect.lavka.dto.sync.SyncRunResponse;
import org.example.proect.lavka.service.SyncService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/sync/products")
public class AdminSyncProductsController {

    private final SyncService syncService;
    private final org.example.proect.lavka.service.sync.FullSyncJobService jobs;

    public AdminSyncProductsController(SyncService syncService, org.example.proect.lavka.service.sync.FullSyncJobService jobs) {
        this.syncService = syncService;
        this.jobs = jobs;
    }

    @PostMapping(
            path = "/force-refresh",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public SyncRunResponse forceRefreshProducts(@RequestBody SyncRunRequest req) {
        return jobs.legacy(() -> syncService.forceRefreshOneBatch(
                req.limit(),
                req.pageSizeWoo(),
                req.cursorAfter(),
                req.dryRun()
        ));
    }
}
