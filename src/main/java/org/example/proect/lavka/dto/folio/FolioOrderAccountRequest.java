package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record FolioOrderAccountRequest(
        @JsonProperty("preview_only") Boolean previewOnly,
        @JsonProperty("schema_version") @NotBlank String schemaVersion,
        String source,
        String intent,
        @JsonProperty("split_strategy") String splitStrategy,
        @JsonProperty("folio_account_header") @NotNull @Valid Header folioAccountHeader,
        @JsonProperty("woo_order") @NotNull @Valid WooOrder wooOrder,
        @JsonProperty("folio_client") @Valid FolioClient folioClient,
        @JsonProperty("folio_document_link") @Valid FolioDocumentLink folioDocumentLink,
        @Valid Billing billing,
        @NotEmpty List<@Valid Item> items
) {
    public record Header(
            @NotBlank String externalRequestId,
            String documentNumber,
            @NotNull LocalDateTime documentDate,
            Integer warehouseId,
            @NotBlank String operationType,
            Integer partnerId,
            String comment,
            @NotNull LocalDate controlDate,
            @NotBlank String folioOperationKind,
            @NotBlank String payerName,
            @NotBlank String receiverName,
            @NotBlank String payerShortName,
            @NotBlank String folioUser,
            @NotBlank String sourceInfo,
            @NotBlank String additionalInfo,
            String priceContractType,
            Boolean notCash,
            Boolean accountingEnabled,
            Boolean returnFlag,
            BigDecimal currencyAmount,
            BigDecimal retailAmount,
            String payerCity,
            String directorName,
            String accountantName,
            String payerPhone,
            String deliveryInfo
    ) {
    }

    public record WooOrder(
            @NotNull Long id,
            String number,
            String status,
            String currency,
            BigDecimal total
    ) {
    }

    public record FolioClient(
            @JsonProperty("user_id") Long userId,
            String id,
            @JsonProperty("short_name") String shortName,
            String name,
            String type
    ) {
    }

    public record FolioDocumentLink(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("document_number") String documentNumber,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("document_status") String documentStatus,
            @JsonProperty("document_created_at") String documentCreatedAt,
            @JsonProperty("document_payload_hash") String documentPayloadHash,
            @JsonProperty("document_last_error") String documentLastError
    ) {
    }

    public record Billing(
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            String company,
            String phone,
            String email,
            String city,
            @JsonProperty("address_1") String address1,
            @JsonProperty("address_2") String address2
    ) {
    }

    public record Item(
            @JsonProperty("order_item_id") Long orderItemId,
            @JsonProperty("product_id") Long productId,
            @NotBlank String sku,
            String name,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
            BigDecimal subtotal,
            BigDecimal total,
            @JsonProperty("unit_price") @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal unitPrice,
            @NotEmpty List<@Valid Allocation> allocations
    ) {
    }

    public record Allocation(
            @JsonProperty("woo_location_id") Long wooLocationId,
            @JsonProperty("woo_location_slug") String wooLocationSlug,
            @JsonProperty("woo_location_name") String wooLocationName,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
            @JsonProperty("allocation_source") String allocationSource,
            @JsonProperty("folio_warehouses") @NotEmpty List<@Valid WarehouseCandidate> folioWarehouses
    ) {
    }

    public record WarehouseCandidate(
            @NotBlank String id,
            Integer priority
    ) {
    }
}
