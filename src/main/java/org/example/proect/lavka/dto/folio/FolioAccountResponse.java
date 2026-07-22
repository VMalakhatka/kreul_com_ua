package org.example.proect.lavka.dto.folio;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record FolioAccountResponse(
        Long documentId,
        String documentNumber,
        LocalDateTime documentDate,
        String operationType,
        Integer warehouseId,
        BigDecimal totalAmount,
        boolean active,
        List<FolioAccountItemResponse> items
) {
}
