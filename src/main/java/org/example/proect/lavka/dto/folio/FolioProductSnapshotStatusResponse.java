package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolioProductSnapshotStatusResponse(
        boolean ok,
        boolean accepted,
        boolean running,
        Long generationId,
        String status,
        String phase,
        String sourceDatabase,
        Integer warehouseId,
        Integer horizonMonths,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime startedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
        LocalDateTime completedAt,
        int totalProducts,
        long movementRows,
        int monthlyMetricRows,
        int unverifiedProducts,
        int dirtyProducts,
        int newProducts,
        int removedProducts,
        String warehouseDigest,
        String error
) { }
