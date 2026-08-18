package org.example.folioruslab.procedure;

import java.util.List;

public record ProcedureFingerprint(
        String procedureName,
        int fragmentCount,
        int sourceCharacters,
        String normalizedSha256,
        String compactSha256,
        String semanticSha256,
        List<ProcedureSourceFragmentFingerprint> fragments
) {
}
