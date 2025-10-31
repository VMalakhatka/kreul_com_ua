package org.example.proect.lavka.dto.sync;

import java.util.List;

public record SyncRunResponse(
        boolean ok,
        int processed,
        int created,
        int updated,
        int drafted,
        String nextAfter,
        boolean last,
        List<String> errors
) {}