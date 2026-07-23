package org.example.proect.lavka.dto.folio;

import java.util.Map;

public record FolioPartnerItemResponse(
        String id,
        String shortName,
        String name,
        String type,
        String typeLabel,
        String bankName,
        String bankAccount,
        String bankCode,
        String bankCity,
        String phone,
        String city,
        Map<String, Object> raw
) {
}
