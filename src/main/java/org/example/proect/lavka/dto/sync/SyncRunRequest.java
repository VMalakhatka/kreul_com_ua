package org.example.proect.lavka.dto.sync;

public record SyncRunRequest(
        Integer limit,
        Integer pageSizeWoo,
        String cursorAfter,
        Boolean dryRun
) {}