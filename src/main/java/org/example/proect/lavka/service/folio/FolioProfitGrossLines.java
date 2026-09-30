package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao.GrossMarginRow;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.GrossProfitLine;
import java.math.BigDecimal;
import java.util.*;
import static org.example.proect.lavka.service.folio.FolioProfitClassifier.money;

/** _PARTNER_TYPES mapping verified read-only on Paint_Ua, 2026-09-30. S/H are Latin, П/Д/К Cyrillic. */
final class FolioProfitGrossLines {
    private record Kind(String id, String code, String label) {}
    private static final List<Kind> KINDS = List.of(
            new Kind("OWN_SHOPS", "S", "Мои магазины"),
            new Kind("PARTNERS", "П", "Партнеры"),
            new Kind("DEALERS", "Д", "Дилеры"),
            new Kind("CUSTOMERS", "К", "Покупатели"),
            new Kind("ART_SALONS", "H", "Художественные салоны"),
            new Kind("OTHER", null, "Прочие типы организаций"));

    static boolean included(GrossMarginRow row, List<Integer> warehouses) {
        return row.accounted() && !row.returnDocument() && !"Я".equals(type(row.organizationType()))
                && warehouses.contains(row.warehouseId());
    }
    static String type(String raw) { return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT); }
    static List<GrossProfitLine> rows(List<GrossMarginRow> source, List<Integer> kyiv, List<Integer> odesa) {
        var result = new ArrayList<GrossProfitLine>();
        city(result, "KYIV", source, kyiv);
        city(result, "ODESA", source, odesa);
        return List.copyOf(result);
    }
    private static void city(List<GrossProfitLine> target, String city, List<GrossMarginRow> source, List<Integer> warehouses) {
        List<GrossMarginRow> rows = source == null ? List.of() : source.stream().filter(r -> included(r, warehouses)).toList();
        BigDecimal roundedSoFar = BigDecimal.ZERO;
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int i = 0; i < KINDS.size(); i++) {
            Kind kind = KINDS.get(i);
            var matching = rows.stream().filter(r -> kind.code() == null
                    ? KINDS.stream().noneMatch(k -> type(r.organizationType()).equals(k.code()))
                    : kind.code().equals(type(r.organizationType()))).toList();
            // Cumulative cent rounding preserves the exact displayed sum of the grouped raw FLOAT-derived margins.
            cumulative = cumulative.add(matching.stream().map(GrossMarginRow::grossMargin)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal rounded = money(cumulative);
            BigDecimal amount = rounded.subtract(roundedSoFar);
            roundedSoFar = rounded;
            target.add(new GrossProfitLine(city + "_GROSS_" + kind.id(), (i + 1) * 10, city, kind.label(),
                    kind.code() == null ? matching.stream().map(r -> type(r.organizationType())).distinct().sorted().toList()
                            : List.of(kind.code()), List.copyOf(warehouses), source == null ? null : amount,
                    source == null ? null : matching.stream().mapToInt(GrossMarginRow::lineCount).sum()));
        }
    }
}
