package org.example.folioruslab.accounting;

import java.time.LocalDateTime;
import java.util.List;

public record SafeAccountingPricePreviewStatus(
        boolean ok,
        boolean accepted,
        boolean running,
        String jobId,
        String status,
        Integer warehouseId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        int totalProducts,
        int processedProducts,
        int cleanProducts,
        int problemProducts,
        int negativeStockProducts,
        Integer progressPercent,
        String currentSku,
        boolean problemsTruncated,
        List<SafeAccountingPriceProblem> problems,
        String error
) {
    public SafeAccountingPricePreviewStatus {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static SafeAccountingPricePreviewStatus idle() {
        return new SafeAccountingPricePreviewStatus(
                true, false, false, null, "IDLE", null,
                null, null, 0, 0, 0, 0, 0,
                null, null, false, List.of(), null
        );
    }
}
