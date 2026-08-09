package org.example.proect.lavka.dto.folio.media;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

public final class FolioProductMediaCommon {

    private FolioProductMediaCommon() {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiMessage(
            String code,
            String message,
            Map<String, Object> details
    ) {
        public ApiMessage(String code, String message) {
            this(code, message, Map.of());
        }
    }

    public record RecordId(
            String table,
            String key
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record S3Match(
            String fullKey,
            long sizeBytes,
            String etag,
            Instant lastModified
    ) {
    }
}
