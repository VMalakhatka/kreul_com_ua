package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FolioOrderAccountResponse(
        boolean ok,
        @JsonProperty("preview_only") boolean previewOnly,
        @JsonProperty("woo_order_id") Long wooOrderId,
        List<Document> documents,
        List<ApiMessage> warnings,
        List<ApiMessage> errors
) {
    public record Document(
            @JsonProperty("document_id") Long documentId,
            @JsonProperty("document_number") String documentNumber,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("document_status") String documentStatus,
            @JsonProperty("folio_warehouse_id") Integer folioWarehouseId,
            @JsonProperty("accounting_enabled") boolean accountingEnabled,
            @JsonProperty("source_external_request_id") String sourceExternalRequestId,
            @JsonProperty("document_created_at") LocalDateTime documentCreatedAt,
            List<Item> items
    ) {
    }

    public record Item(
            @JsonProperty("order_item_id") Long orderItemId,
            String sku,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal amount,
            @JsonProperty("folio_warehouse_id") Integer folioWarehouseId,
            @JsonProperty("allocation_status") String allocationStatus
    ) {
    }

    public record ApiMessage(
            String code,
            String message,
            Map<String, Object> details
    ) {
    }
}
