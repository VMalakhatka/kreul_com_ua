package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FolioCustomerDocumentDetailResponse(
        boolean ok,
        Partner partner,
        Document document,
        List<Issue> warnings
) {
    public record Partner(String shortName, String name) {
    }

    public record Document(
            FolioCustomerDocumentType documentType,
            long documentId,
            String documentNumber,
            String documentNumberSuffix,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime documentDate,
            BigDecimal totalAmount,
            BigDecimal currencyAmount,
            String currencyCode,
            Integer warehouseId,
            Boolean accounted,
            Boolean nonCash,
            Boolean returnDocument,
            Boolean paymentDirectionRaw,
            String operationKind,
            String contractCode,
            String basis,
            String note,
            String payerName,
            String receiverName,
            String payerShortName,
            String folioUser,
            String sourceInfo,
            String additionalInfo,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime correctedAt,
            String correctedBy,
            BigDecimal unallocatedAmount,
            BigDecimal unallocatedCurrencyAmount,
            DocumentRequisites documentRequisites,
            PaymentRequisites paymentRequisites,
            List<Item> items,
            List<LinkedPayment> linkedPayments,
            List<PaymentAllocation> allocations,
            RepeatOrder repeatOrder,
            String source
    ) {
    }

    public record DocumentRequisites(
            String payerCity,
            String directorName,
            String accountantName,
            String payerPhone,
            String deliveryInfo
    ) {
    }

    public record PaymentRequisites(
            String payer,
            String recipient,
            String payerBank,
            String recipientBank,
            String paymentPurpose
    ) {
    }

    public record Item(
            long lineId,
            int lineNumber,
            String sku,
            String name,
            Integer warehouseId,
            BigDecimal requestedQuantity,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal amount,
            BigDecimal currencyPrice,
            BigDecimal currencyAmount,
            String currencyCode,
            BigDecimal retailAmount,
            Boolean accounted,
            Boolean returnLine,
            String batch,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime expiryDate,
            boolean repeatable
    ) {
    }

    public record LinkedPayment(
            long paymentId,
            String paymentNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime paymentDate,
            BigDecimal paymentAmount,
            BigDecimal allocatedAmount,
            Boolean nonCash,
            Boolean paymentDirectionRaw
    ) {
    }

    public record PaymentAllocation(
            long allocationId,
            Long documentId,
            FolioCustomerDocumentType documentType,
            String documentNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime documentDate,
            String sku,
            String name,
            Integer warehouseId,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal amount,
            String currencyCode,
            BigDecimal currencyAmount
    ) {
    }

    public record RepeatOrder(
            boolean allowed,
            String reason,
            List<RepeatItem> items
    ) {
    }

    public record RepeatItem(
            String sku,
            String name,
            BigDecimal quantity,
            BigDecimal historicalPrice,
            String currencyCode
    ) {
    }

    public record Issue(String code, String message, Map<String, Object> details) {
    }
}
