package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolioAccountingPriceNativeFullRequest(
        @NotNull @Positive Integer warehouseId,
        @NotNull Boolean previewOnly,
        Boolean confirmApply,
        @Size(max = 20) String fromSku,
        @Size(max = 20) String toSku,
        @Size(max = 500) List<@Size(max = 20) String> skus
) {
    public FolioAccountingPriceNativeFullRequest(
            Integer warehouseId, Boolean previewOnly, Boolean confirmApply) {
        this(warehouseId, previewOnly, confirmApply, null, null, null);
    }

    public boolean isApplyConfirmed() {
        return Boolean.TRUE.equals(confirmApply);
    }

    public boolean hasSelection() {
        return fromSku != null || toSku != null || skus != null && !skus.isEmpty();
    }
}
