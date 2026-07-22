package org.example.proect.lavka.dto.folio;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateFolioAccountItemRequest(
        @NotBlank String sku,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal currencyPrice,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal currencyAmount,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal retailAmount
) {
}
