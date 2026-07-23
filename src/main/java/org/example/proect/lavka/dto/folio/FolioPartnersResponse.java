package org.example.proect.lavka.dto.folio;

import java.util.List;

public record FolioPartnersResponse(
        boolean ok,
        List<FolioPartnerItemResponse> items,
        long total,
        int limit,
        int offset
) {
}
