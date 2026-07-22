package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateFolioAccountItemQuantityRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity
) {
}
