package org.example.proect.lavka.dto.folio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public record CreateFolioAccountRequest(
        @NotBlank String externalRequestId,
        @NotBlank String documentNumber,
        @NotNull LocalDateTime documentDate,
        @NotNull Integer warehouseId,
        @NotBlank String operationType,
        Integer partnerId,
        String comment,
        @NotEmpty List<@Valid CreateFolioAccountItemRequest> items
) {
}
