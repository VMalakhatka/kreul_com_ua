package org.example.proect.lavka.dto.folio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record FolioAccountResponse(
        Long documentId,
        String documentNumber,
        LocalDateTime documentDate,
        String operationType,
        Integer warehouseId,
        BigDecimal totalAmount,
        String comment,
        LocalDate controlDate,
        String folioOperationKind,
        String payerName,
        String receiverName,
        String payerShortName,
        String folioUser,
        String sourceInfo,
        String additionalInfo,
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
        String deliveryInfo,
        LocalDateTime createdDate,
        LocalDateTime correctionDate,
        String correctedBy,
        boolean active,
        List<FolioAccountItemResponse> items
) {
}
