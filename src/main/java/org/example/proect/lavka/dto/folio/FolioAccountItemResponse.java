package org.example.proect.lavka.dto.folio;

import java.math.BigDecimal;

public record FolioAccountItemResponse(
        Long recno,
        Integer lineNumber,
        String sku,
        Integer warehouseId,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount
) {
}
