package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolioAccountingPriceFullStatusResponse(
        boolean ok,
        boolean accepted,
        boolean running,
        String jobId,
        String status,
        FolioAccountingPriceFullRecalculationRequest request,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime startedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime completedAt,
        int totalProducts,
        int processedProducts,
        int eligibleProducts,
        int recalculatedProducts,
        int priceChangedProducts,
        int skippedProducts,
        String currentSku,
        int warningCount,
        boolean warningsTruncated,
        List<FolioAccountingPriceRecalculationResponse.Issue> warnings,
        String error
) {
}
