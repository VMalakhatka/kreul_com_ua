package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Physical end-of-day history, bounded to one SKU and the snapshot horizon. */
public final class FolioProductAvailabilityHistory {
    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.000001");
    private FolioProductAvailabilityHistory() { }

    public record Month(String sku, LocalDate monthStart, long knownMask,
                        long availableMask, long negativeMask, String quality,
                        BigDecimal reconciliationDifference) { }

    public static List<Month> build(ProductCard card, Map<LocalDate, BigDecimal> daily,
                                    LocalDate start, LocalDate capturedDate) {
        BigDecimal reconstructed = card.openingQuantityAtHorizon();
        boolean invalid = card.hiddenForAccounting();
        for (var entry : daily.entrySet()) {
            if (entry.getKey().isBefore(start) || entry.getKey().isAfter(capturedDate)) invalid = true;
            reconstructed = reconstructed.add(entry.getValue());
        }
        BigDecimal difference = reconstructed.subtract(card.physicalQuantity());
        boolean complete = !invalid && difference.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0;
        BigDecimal quantity = card.openingQuantityAtHorizon();
        List<Month> result = new ArrayList<>(36);
        // The capture date is not yet a completed day. Never project its closing balance.
        for (LocalDate month = start.withDayOfMonth(1); !month.isAfter(capturedDate);
             month = month.plusMonths(1)) {
            long known = 0, available = 0, negative = 0;
            LocalDate end = month.plusMonths(1);
            for (LocalDate day = month.isBefore(start) ? start : month;
                 day.isBefore(end) && day.isBefore(capturedDate); day = day.plusDays(1)) {
                BigDecimal delta = daily.get(day);
                if (delta != null) quantity = quantity.add(delta);
                long bit = 1L << (day.getDayOfMonth() - 1);
                if (complete) {
                    known |= bit;
                    if (quantity.signum() > 0) available |= bit;
                    if (quantity.signum() < 0) negative |= bit;
                }
            }
            result.add(new Month(card.sku(), month, known, available, negative,
                    complete ? "RECONCILED" : "DATA_INCOMPLETE", difference));
        }
        return List.copyOf(result);
    }
}
