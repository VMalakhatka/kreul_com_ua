package org.example.proect.lavka.dto.folio.media;

import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.ApiMessage;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.RecordId;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaCommon.S3Match;

import java.util.List;

public record FolioProductMediaChangeResponse(
        boolean ok,
        boolean previewOnly,
        String externalRequestId,
        Summary summary,
        List<Result> results,
        List<ApiMessage> warnings,
        List<ApiMessage> errors
) {
    public record Summary(
            int requested,
            int ready,
            int noop,
            int blocked,
            int applied
    ) {
    }

    public record Result(
            int index,
            String operation,
            String status,
            String role,
            String sku,
            RecordId recordId,
            Value before,
            Value after,
            List<S3Match> s3Matches,
            List<ApiMessage> warnings,
            List<ApiMessage> errors
    ) {
    }

    public record Value(
            String filename,
            Integer sortOrder
    ) {
    }
}
