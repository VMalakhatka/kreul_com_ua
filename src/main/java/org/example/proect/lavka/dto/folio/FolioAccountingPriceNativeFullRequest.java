package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Size(max = 500) List<@Size(max = 20) String> skus,
        @Size(max = 32)
        @Schema(
                description = "Режим apply для native-range; SAFE_APPLY_ONLY выполняет один безопасный проход по SKU",
                allowableValues = {PREFLIGHT_AND_APPLY, SAFE_APPLY_ONLY},
                defaultValue = PREFLIGHT_AND_APPLY)
        String applyMode
) {
    public static final String PREFLIGHT_AND_APPLY = "PREFLIGHT_AND_APPLY";
    public static final String SAFE_APPLY_ONLY = "SAFE_APPLY_ONLY";

    public FolioAccountingPriceNativeFullRequest(
            Integer warehouseId, Boolean previewOnly, Boolean confirmApply) {
        this(warehouseId, previewOnly, confirmApply, null, null, null, null);
    }

    public FolioAccountingPriceNativeFullRequest(
            Integer warehouseId, Boolean previewOnly, Boolean confirmApply,
            String fromSku, String toSku, List<String> skus) {
        this(warehouseId, previewOnly, confirmApply, fromSku, toSku, skus, null);
    }

    public boolean isApplyConfirmed() {
        return Boolean.TRUE.equals(confirmApply);
    }

    public boolean hasSelection() {
        return fromSku != null || toSku != null || skus != null && !skus.isEmpty();
    }

    public String effectiveApplyMode() {
        return applyMode == null || applyMode.isBlank()
                ? PREFLIGHT_AND_APPLY
                : applyMode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public boolean isSafeApplyOnly() {
        return SAFE_APPLY_ONLY.equals(effectiveApplyMode());
    }
}
