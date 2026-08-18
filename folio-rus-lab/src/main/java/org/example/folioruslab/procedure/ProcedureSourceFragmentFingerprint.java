package org.example.folioruslab.procedure;

public record ProcedureSourceFragmentFingerprint(
        int fragmentNumber,
        int sourceCharacters,
        String normalizedSha256,
        String compactSha256,
        String semanticSha256
) {
}
