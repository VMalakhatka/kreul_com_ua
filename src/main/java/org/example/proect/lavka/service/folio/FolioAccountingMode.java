package org.example.proect.lavka.service.folio;

import java.util.Set;

/** Decodes the compact accounting-price mode stored in {@code SCLAD_R.N_2}. */
public final class FolioAccountingMode {

    private static final Set<Integer> VERIFIED_AVERAGE_CODES = Set.of(1000, 1100);

    private FolioAccountingMode() {
    }

    public static Decoded decode(Integer rawCode) {
        if (rawCode == null || rawCode < 1000) {
            return new Decoded(rawCode, 3, 0, false, "NO_RECALCULATION");
        }
        int value = Math.abs(rawCode);
        int calculationMode = value % 10;
        int periodMode = (value / 10) % 10;
        boolean includeTax = ((value / 100) % 10) != 0;
        String name = switch (calculationMode) {
            case 0 -> "AVERAGE";
            case 1 -> "LIFO";
            case 2 -> "FIFO";
            case 3 -> "NO_RECALCULATION";
            case 4 -> "FIXED";
            case 5 -> "BATCH";
            default -> "UNKNOWN";
        };
        return new Decoded(rawCode, calculationMode, periodMode, includeTax, name);
    }

    /**
     * The source snapshot reads already stored Folio accounting amounts. Both
     * average-price variants are therefore supported: without tax (1000) and
     * with tax (1100).
     */
    public static boolean supportsProductSnapshot(Integer rawCode) {
        return rawCode != null && VERIFIED_AVERAGE_CODES.contains(rawCode);
    }

    /** Safe native procedures must be golden-master verified for each code. */
    public static boolean supportsSafeNativeRecalculation(Integer rawCode) {
        return rawCode != null && VERIFIED_AVERAGE_CODES.contains(rawCode);
    }

    public record Decoded(
            Integer rawCode,
            int calculationMode,
            int periodMode,
            boolean includeTax,
            String name
    ) {
    }
}
