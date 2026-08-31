package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record FolioProductAnalyticsCapabilitiesRequest(
        @NotBlank String sourceDatabase,
        @NotEmpty List<@Positive Integer> warehouseIds) {
}
