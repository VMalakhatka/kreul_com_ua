package org.example.proect.lavka.dto.folio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record FolioAccountSummaryResponse(
        Long documentId,
        String documentNumber,
        LocalDateTime documentDate,
        String operationType,
        Integer warehouseId,
        BigDecimal totalAmount,
        String payerName,
        String receiverName,
        String payerShortName,
        String sourceInfo,
        String additionalInfo,
        String folioOperationKind,
        LocalDate controlDate,
        Boolean active,
        LocalDateTime createdDate
) {
}
