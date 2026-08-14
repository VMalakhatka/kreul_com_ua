package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.example.proect.lavka.dto.folio.FolioCustomerDebtorsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FolioCustomerDebtorsService {

    static final BigDecimal DEFAULT_MIN_PAYABLE = new BigDecimal("100.00");
    static final String DEFAULT_SORT = "payableNow_desc";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_SEARCH_LENGTH = 100;
    private static final List<String> DEFAULT_TYPES = List.of("П", "Д", "К");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final FolioCustomerBalanceDao dao;
    private final Clock clock;

    @Autowired
    public FolioCustomerDebtorsService(FolioCustomerBalanceDao dao) {
        this(dao, Clock.systemDefaultZone());
    }

    FolioCustomerDebtorsService(FolioCustomerBalanceDao dao, Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    public FolioCustomerDebtorsResponse get(BigDecimal minPayable,
                                             String q,
                                             String types,
                                             Integer limit,
                                             Integer offset,
                                             String sort) {
        BigDecimal normalizedMinPayable = normalizeMinPayable(minPayable);
        String normalizedQuery = normalizeQuery(q);
        TypeFilter normalizedTypes = normalizeTypes(types);
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = normalizeOffset(offset);
        String normalizedSort = normalizeSort(sort);
        LocalDate asOfDate = LocalDate.now(clock);

        var balances = dao.loadForPartners(
                normalizedQuery,
                normalizedTypes.databaseValues(),
                FolioCustomerBalanceService.FOLIO_MIN_DATE,
                asOfDate,
                true
        );

        List<FolioCustomerDebtorsResponse.DebtorItem> matched = new ArrayList<>();
        for (var result : balances) {
            var calculation = FolioCustomerBalanceCalculator.calculate(result.balance(), asOfDate, false);
            FolioCustomerBalanceResponse.Summary financial = calculation.summary();
            if (financial.payableNow().compareTo(normalizedMinPayable) <= 0) {
                continue;
            }
            var partner = result.partner();
            matched.add(new FolioCustomerDebtorsResponse.DebtorItem(
                    new FolioCustomerDebtorsResponse.DebtorPartner(
                            partner.shortName(),
                            partner.name(),
                            partner.type(),
                            partner.city(),
                            partner.phone()
                    ),
                    financial.commonDebt(),
                    financial.deferredAmount(),
                    financial.overdueDeferredAmount(),
                    financial.prepaymentAmount(),
                    financial.payableNow(),
                    List.of()
            ));
        }

        matched.sort(Comparator
                .comparing(FolioCustomerDebtorsResponse.DebtorItem::payableNow).reversed()
                .thenComparing(item -> item.partner().shortName()));

        FolioCustomerDebtorsResponse.DebtorsSummary summary = summarize(matched);
        int from = Math.min(normalizedOffset, matched.size());
        int to = Math.min(from + normalizedLimit, matched.size());
        List<FolioCustomerDebtorsResponse.DebtorItem> page = List.copyOf(matched.subList(from, to));
        summary = new FolioCustomerDebtorsResponse.DebtorsSummary(
                summary.matchedClients(),
                page.size(),
                summary.commonDebtTotal(),
                summary.deferredAmountTotal(),
                summary.overdueDeferredAmountTotal(),
                summary.prepaymentAmountTotal(),
                summary.payableNowTotal()
        );

        return new FolioCustomerDebtorsResponse(
                true,
                asOfDate,
                new FolioCustomerDebtorsResponse.DebtorsFilters(
                        normalizedMinPayable,
                        normalizedQuery,
                        normalizedTypes.responseValues(),
                        normalizedLimit,
                        normalizedOffset,
                        normalizedSort
                ),
                summary,
                page,
                standardWarnings(asOfDate),
                List.of()
        );
    }

    private static FolioCustomerDebtorsResponse.DebtorsSummary summarize(
            List<FolioCustomerDebtorsResponse.DebtorItem> matched) {
        BigDecimal commonDebt = ZERO;
        BigDecimal deferred = ZERO;
        BigDecimal overdueDeferred = ZERO;
        BigDecimal prepayment = ZERO;
        BigDecimal payableNow = ZERO;
        for (var item : matched) {
            commonDebt = commonDebt.add(item.commonDebt());
            deferred = deferred.add(item.deferredAmount());
            overdueDeferred = overdueDeferred.add(item.overdueDeferredAmount());
            prepayment = prepayment.add(item.prepaymentAmount());
            payableNow = payableNow.add(item.payableNow());
        }
        return new FolioCustomerDebtorsResponse.DebtorsSummary(
                matched.size(),
                matched.size(),
                money(commonDebt),
                money(deferred),
                money(overdueDeferred),
                money(prepayment),
                money(payableNow)
        );
    }

    private static List<FolioCustomerDebtorsResponse.DebtorsIssue> standardWarnings(LocalDate asOfDate) {
        return List.of(
                new FolioCustomerDebtorsResponse.DebtorsIssue(
                        "FOLIO_NOLOCK_READ",
                        "I_DOLG_DOC uses NOLOCK; concurrent Folio edits can make the report internally non-snapshot",
                        Map.of()
                ),
                new FolioCustomerDebtorsResponse.DebtorsIssue(
                        "ACTIVE_LEDGER_ONLY",
                        "The standard procedure does not include archived Folio documents",
                        Map.of()
                ),
                new FolioCustomerDebtorsResponse.DebtorsIssue(
                        "LEGACY_DATE_TO_MIDNIGHT",
                        "I_DOLG_DOC treats the report date as an inclusive midnight boundary",
                        Map.of("asOfDate", asOfDate.toString())
                )
        );
    }

    private static BigDecimal normalizeMinPayable(BigDecimal value) {
        BigDecimal normalized = value == null ? DEFAULT_MIN_PAYABLE : value;
        if (normalized.signum() < 0) {
            throw validation("invalid_min_payable", "minPayable must be zero or greater");
        }
        if (normalized.scale() > 2) {
            throw validation("invalid_min_payable", "minPayable must have no more than two decimal places");
        }
        return money(normalized);
    }

    private static String normalizeQuery(String q) {
        String value = q == null ? "" : q.trim();
        if (value.length() > MAX_SEARCH_LENGTH) {
            throw validation("query_too_long", "q must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }
        return value;
    }

    private static TypeFilter normalizeTypes(String types) {
        if (types == null || types.trim().isEmpty()) {
            return new TypeFilter(DEFAULT_TYPES, DEFAULT_TYPES);
        }
        if ("all".equalsIgnoreCase(types.trim())) {
            return new TypeFilter(List.of(), List.of("all"));
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(types.split(",", -1))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .forEach(value -> {
                    if (value.isEmpty() || value.codePointCount(0, value.length()) != 1) {
                        throw validation("invalid_partner_type", "types must contain one-character Folio codes or all");
                    }
                    values.add(value);
                });
        if (values.isEmpty() || values.size() > 20) {
            throw validation("invalid_partner_type", "types must contain between 1 and 20 Folio codes");
        }
        List<String> result = List.copyOf(values);
        return new TypeFilter(result, result);
    }

    private static int normalizeLimit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1 || value > MAX_LIMIT) {
            throw validation("invalid_limit", "limit must be between 1 and " + MAX_LIMIT);
        }
        return value;
    }

    private static int normalizeOffset(Integer offset) {
        int value = offset == null ? 0 : offset;
        if (value < 0) {
            throw validation("invalid_offset", "offset must be zero or greater");
        }
        return value;
    }

    private static String normalizeSort(String sort) {
        String value = sort == null || sort.trim().isEmpty() ? DEFAULT_SORT : sort.trim();
        if (!DEFAULT_SORT.equals(value)) {
            throw validation("unsupported_sort", "sort must be " + DEFAULT_SORT);
        }
        return value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static FolioAccountValidationException validation(String code, String message) {
        return new FolioAccountValidationException(code, message);
    }

    private record TypeFilter(List<String> databaseValues, List<String> responseValues) {
    }
}
