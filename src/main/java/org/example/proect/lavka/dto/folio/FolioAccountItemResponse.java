package org.example.proect.lavka.dto.folio;

import java.math.BigDecimal;

public record FolioAccountItemResponse(
        Long recno,
        Integer lineNumber,
        String sku,
        Integer warehouseId,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount,
        String organizationShortName,
        String documentNumber,
        String documentNumberSuffix,
        String typeDoc,
        Boolean notCash,
        String priceContractType,
        BigDecimal currencyPrice,
        String currencyCode,
        BigDecimal currencyAmount,
        Boolean valutaRouble,
        BigDecimal retailAmount,
        String folioOperationKind,
        BigDecimal ball1,
        BigDecimal ball2,
        BigDecimal ball3,
        BigDecimal ball4,
        BigDecimal ball5
) {
}
