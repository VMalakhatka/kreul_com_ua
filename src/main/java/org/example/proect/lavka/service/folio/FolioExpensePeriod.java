package org.example.proect.lavka.service.folio;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Period-only evidence: never exposes arbitrary payment notes or credentials. */
final class FolioExpensePeriod {
    private static final Pattern VALID = Pattern.compile("(?<!\\d)(\\d{4})\\s+(0[1-9]|1[0-2])(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern RANGE = Pattern.compile("^\\s*[-–—/]\\s*\\d{1,2}(?!\\d)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("^\\s+\\d", Pattern.UNICODE_CHARACTER_CLASS);

    record Resolution(YearMonth month, String source, String status, String evidence, boolean excluded) {
        boolean problem() { return !status.equals("VALID") && !status.equals("NO_PERIOD"); }
    }

    static Resolution resolve(String note, LocalDate date) {
        String text = note == null ? "" : note;
        var periods = new LinkedHashSet<YearMonth>();
        var evidence = new LinkedHashSet<String>();
        boolean range = false;
        var matcher = VALID.matcher(text);
        StringBuilder unmatched = new StringBuilder(text);
        while (matcher.find()) {
            periods.add(YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
            String marker = matcher.group(1) + " " + matcher.group(2);
            var suffix = RANGE.matcher(text.substring(matcher.end()));
            if (suffix.find()) { range = true; marker += " [RANGE]"; }
            evidence.add(marker);
            for (int i = matcher.start(); i < matcher.end(); i++) unmatched.setCharAt(i, ' ');
        }
        var years = YEAR.matcher(unmatched);
        boolean unresolvedYear = false;
        while (years.find()) {
            boolean sameYearAlreadyResolved = periods.stream().anyMatch(p -> p.getYear() == Integer.parseInt(years.group()));
            boolean numericSuffix = NUMERIC_SUFFIX.matcher(unmatched.substring(years.end())).find();
            // A repeated year in explanatory text (e.g. "липень 2026") does not
            // invalidate an already explicit 2026 07. A second damaged numeric marker does.
            if (sameYearAlreadyResolved && !numericSuffix) continue;
            unresolvedYear = true; evidence.add(years.group() + " [UNRESOLVED]");
        }
        String safeEvidence = String.join("; ", evidence);
        if (periods.size() > 1) return new Resolution(periods.iterator().next(), "EXPLICIT_NOTE",
                "AMBIGUOUS_EXPLICIT_PERIOD", safeEvidence, true);
        YearMonth month = periods.isEmpty() ? YearMonth.from(date) : periods.iterator().next();
        String source = periods.isEmpty() ? "DOCUMENT_DATE" : "EXPLICIT_NOTE";
        String status = range ? "MIXED_EXPLICIT_PERIOD" : unresolvedYear ? "UNRESOLVED_PERIOD_MARKER"
                : periods.isEmpty() ? "NO_PERIOD" : "VALID";
        return new Resolution(month, source, status, safeEvidence, false);
    }
}
