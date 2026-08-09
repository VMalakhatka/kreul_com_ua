package org.example.proect.lavka.dto.folio.media;

import java.util.List;

public record FolioProductMediaChangeRequest(
        String externalRequestId,
        Boolean previewOnly,
        String source,
        List<Change> changes
) {
    public record Change(
            String operation,
            String sku,
            String recordId,
            String expectedOldFilename,
            Integer expectedOldSortOrder,
            String filename,
            Integer sortOrder,
            S3Proof s3Proof
    ) {
    }

    public record S3Proof(
            String fullKey,
            Long sizeBytes,
            String etag
    ) {
    }
}
