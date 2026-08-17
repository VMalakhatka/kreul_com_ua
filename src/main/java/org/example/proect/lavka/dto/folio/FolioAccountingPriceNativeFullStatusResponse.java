package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolioAccountingPriceNativeFullStatusResponse(
        boolean ok,
        boolean accepted,
        boolean running,
        String jobId,
        String status,
        String phase,
        FolioAccountingPriceNativeFullRequest request,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime startedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime completedAt,
        String database,
        FolioAccountingPriceRecalculationResponse.AccountingMethod accountingMethod,
        int procedureCalls,
        int preflightChunks,
        int committedChunks,
        int progressUnits,
        int totalUnits,
        Integer progressPercent,
        String currentArt,
        String nextArt,
        String lastCommittedArt,
        String checkpointArt,
        Integer returnCode,
        int warningCount,
        boolean warningsTruncated,
        List<FolioAccountingPriceRecalculationResponse.Issue> warnings,
        ChunkDiagnostics failedChunk,
        String error
) {
    public record ChunkDiagnostics(
            String inputArt,
            String outputArt,
            String nextArt,
            Integer returnCode,
            Integer currentUnits,
            Integer totalUnits,
            String problemDate,
            int resultRowCount,
            int transactionCountBefore,
            int transactionCountAfter,
            String validationError
    ) {
    }
}
