package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FolioBalanceSnapshotStatusResponse(
        boolean ok,
        boolean refreshAccepted,
        boolean running,
        Long generationId,
        String status,
        String triggerSource,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate asOfDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime startedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime completedAt,
        Integer totalClients,
        String error,
        Building building,
        ActiveSnapshot activeSnapshot
) {
    public record Building(
            long generationId,
            String triggerSource,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOfDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime startedAt
    ) {
    }

    public record ActiveSnapshot(
            long generationId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOfDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime completedAt,
            int totalClients
    ) {
    }
}
