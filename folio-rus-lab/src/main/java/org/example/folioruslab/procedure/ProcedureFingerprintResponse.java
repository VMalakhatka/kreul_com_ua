package org.example.folioruslab.procedure;

import java.time.OffsetDateTime;
import java.util.List;

public record ProcedureFingerprintResponse(
        String database,
        OffsetDateTime capturedAt,
        List<ProcedureFingerprint> procedures
) {
}
