package org.example.proect.lavka.dto.folio.media;

import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.ApiMessage;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.RecordId;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.S3Match;

import java.util.List;

public record FolioProductMediaSearchResponse(
        boolean ok,
        Query query,
        long total,
        List<Item> items,
        List<ApiMessage> warnings,
        List<ApiMessage> errors
) {
    public record Query(
            String sku,
            String filename,
            String role,
            String match,
            int limit,
            int offset
    ) {
    }

    public record Item(
            String role,
            String sku,
            String productName,
            String filename,
            String matchType,
            Long plusArtic,
            Integer sortOrder,
            RecordId recordId,
            S3State s3,
            List<ApiMessage> warnings,
            List<ApiMessage> errors
    ) {
    }

    public record S3State(
            boolean indexed,
            List<S3Match> matches
    ) {
    }
}
