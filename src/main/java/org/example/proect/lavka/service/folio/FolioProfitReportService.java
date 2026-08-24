package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.GrossMarginRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryMovementRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryOpeningRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.CityResult;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.Controls;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.DocumentLine;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.ExpenseSummary;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.Inputs;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.InventoryResult;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.Warning;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.WarehouseInventoryResult;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.Category;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.City;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.ClassifiedPayment;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.Treatment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FolioProfitReportService {

    private static final String RULE_VERSION = "2026-08-18.2";
    private static final String REPORT_CURRENCY = "UAH";
    private static final Pattern EXPLICIT_PERIOD = Pattern.compile("(?<!\\d)(\\d{4})\\s+(0[1-9]|1[0-2])(?!\\d)");
    private static final DateTimeFormatter FOLIO_PERIOD = DateTimeFormatter.ofPattern("yyyy MM");
    private static final ZoneId REPORT_ZONE = ZoneId.of("Europe/Kyiv");
    private static final BigDecimal QUANTITY_EPSILON = new BigDecimal("0.000001");
    private static final int MAX_STOCK_WAREHOUSES_PER_CITY = 50;

    private final FolioProfitReportDao dao;
    private final FolioProfitClassifier classifier;
    private final FolioProfitReportProperties properties;

    public FolioProfitReportService(
            FolioProfitReportDao dao,
            FolioProfitClassifier classifier,
            FolioProfitReportProperties properties) {
        this.dao = dao;
        this.classifier = classifier;
        this.properties = properties;
    }

    @Transactional(transactionManager = "mssqlTransactionManager", readOnly = true)
    public FolioProfitReportResponse calculate(Request request, boolean includeDocuments) {
        if (!properties.isEnabled()) {
            throw validation("PROFIT_REPORT_DISABLED", "Отчёт прибыли отключён настройкой сервиса");
        }

        YearMonth month = parseMonth(request.month());
        BigDecimal taxShare = fractionOrDefault(request.odesaTaxShare(), properties.getDefaultOdesaTaxShare(),
                "ODESA_TAX_SHARE_INVALID", "Доля налогов Одессы должна быть от 0 до 1");
        BigDecimal rubRate = positiveOrDefault(request.rubToUahRate(), properties.getDefaultRubToUahRate(),
                "RUB_RATE_INVALID", "Курс RUB/UAH должен быть больше нуля");
        BigDecimal mkIncome = optionalNonNegative(request.odesaMasterClassIncome(), "MASTER_CLASS_INCOME_INVALID");
        BigDecimal mkReturn = optionalNonNegative(request.odesaMasterClassReturn(), "MASTER_CLASS_RETURN_INVALID");
        BigDecimal additionalSalary = optionalNonNegative(
                request.odesaAdditionalSalary(), "ODESA_ADDITIONAL_SALARY_INVALID");
        List<Integer> kyivStockWarehouseIds = warehouseIdsOrDefault(
                request.kyivStockWarehouseIds(), properties.getKyivWarehouseIds(), "kyivStockWarehouseIds");
        List<Integer> odesaStockWarehouseIds = warehouseIdsOrDefault(
                request.odesaStockWarehouseIds(), List.of(properties.getOdesaWarehouseId()),
                "odesaStockWarehouseIds");
        assertWarehousesDoNotOverlap(kyivStockWarehouseIds, odesaStockWarehouseIds);

        List<Warning> warnings = new ArrayList<>();
        List<ResolvedPayment> resolved = resolvePayments(month, rubRate, warnings);
        List<GrossMarginRow> grossRows = dao.findGrossMargins(month.atDay(1), month.plusMonths(1).atDay(1));
        InventoryComputation inventory = calculateInventory(
                month, kyivStockWarehouseIds, odesaStockWarehouseIds);

        Map<SummaryKey, SummaryAccumulator> summaries = new LinkedHashMap<>();
        Map<String, BigDecimal> taxPools = new LinkedHashMap<>();
        BigDecimal selectedAmount = BigDecimal.ZERO;
        BigDecimal capitalizedTotal = BigDecimal.ZERO;
        BigDecimal excludedTotal = BigDecimal.ZERO;
        BigDecimal unclassifiedTotal = BigDecimal.ZERO;
        int unclassifiedCount = 0;

        for (ResolvedPayment payment : resolved) {
            ClassifiedPayment classified = payment.classified();
            selectedAmount = selectedAmount.add(classified.reportAmount());
            if (classified.treatment() == Treatment.TAX_POOL) {
                boolean allocated = allocateTax(classified, taxShare, summaries, taxPools, warnings);
                if (!allocated) {
                    unclassifiedTotal = unclassifiedTotal.add(classified.reportAmount());
                    unclassifiedCount++;
                }
            } else {
                addSummary(summaries, classified.city(), classified.category(), classified.treatment(),
                        classified.reportAmount(), profitImpact(classified), 1);
            }
            if (classified.treatment() == Treatment.CAPITALIZED_IN_INVENTORY) {
                capitalizedTotal = capitalizedTotal.add(classified.reportAmount());
            } else if (classified.treatment() == Treatment.EXCLUDED) {
                excludedTotal = excludedTotal.add(classified.reportAmount());
            } else if (classified.treatment() == Treatment.UNCLASSIFIED) {
                unclassifiedTotal = unclassifiedTotal.add(classified.reportAmount());
                unclassifiedCount++;
            }
        }

        if (request.odesaAdditionalSalary() != null) {
            addSummary(summaries, City.ODESA, Category.SALARY, Treatment.OPERATING_EXPENSE,
                    additionalSalary, additionalSalary, 0);
        }

        if (unclassifiedCount > 0) {
            warnings.add(warning("UNCLASSIFIED_DOCUMENTS",
                    "Есть документы без подтверждённого правила; они не уменьшают прибыль",
                    Map.of("count", unclassifiedCount, "amount", money(unclassifiedTotal))));
        }
        if (request.odesaMasterClassIncome() == null || request.odesaMasterClassReturn() == null) {
            warnings.add(warning("MASTER_CLASS_MANUAL_INPUT_REQUIRED",
                    "МК и возврат МК пока не подтверждены отдельным источником ФОЛИО; передайте оба значения в запросе",
                    Map.of("incomeProvided", request.odesaMasterClassIncome() != null,
                            "returnProvided", request.odesaMasterClassReturn() != null)));
        }
        if (request.odesaAdditionalSalary() == null) {
            warnings.add(warning("ODESA_ADDITIONAL_WORKS_UNCONFIRMED",
                    "5 000 грн дополнительных работ Одессы не добавлены: передайте odesaAdditionalSalary после подтверждения",
                    Map.of("candidateAmount", new BigDecimal("5000.00"))));
        }
        warnings.add(warning("IMPORT_TRANSPORT_CAPITALIZED",
                "Импортный транспорт показан отдельно и не вычтен повторно, поскольку он уже включён в учётную цену товара",
                Map.of("amount", money(capitalizedTotal))));
        warnings.add(warning("LEGACY_FLOAT_ROUNDING",
                "Валовая прибыль рассчитана по значениям ФОЛИО; возможна разница в копейки со старым Excel из-за FLOAT",
                Map.of()));
        warnings.add(warning("NOLOCK_READ",
                "Отчёт не блокирует работу ФОЛИО; при одновременном проведении документов показания могут кратковременно изменяться",
                Map.of()));
        warnings.add(warning("INVENTORY_CHANGE_INFORMATIONAL",
                "Изменение учётной стоимости склада показано отдельно и не прибавляется к прибыли автоматически",
                Map.of()));
        if (inventory.negativeClosingPositionCount() > 0) {
            warnings.add(warning("NEGATIVE_CLOSING_INVENTORY",
                    "На конец месяца есть складские позиции с отрицательным расчётным остатком",
                    Map.of("count", inventory.negativeClosingPositionCount())));
        }
        if (inventory.zeroValueClosingPositionCount() > 0) {
            warnings.add(warning("ZERO_VALUE_CLOSING_INVENTORY",
                    "На конец месяца есть ненулевые складские позиции с нулевой учётной стоимостью",
                    Map.of("count", inventory.zeroValueClosingPositionCount())));
        }

        List<ExpenseSummary> expenseRows = summaries.entrySet().stream()
                .map(entry -> toSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ExpenseSummary::city).thenComparing(ExpenseSummary::category))
                .toList();

        BigDecimal kyivExpenses = cityOperatingExpenses(summaries, City.KYIV);
        BigDecimal odesaExpenses = cityOperatingExpenses(summaries, City.ODESA);
        BigDecimal kyivBaseGross = grossFor(grossRows, properties.getKyivWarehouseIds());
        BigDecimal odesaBaseGross = grossFor(grossRows, List.of(properties.getOdesaWarehouseId()));
        BigDecimal odesaManualGross = money(mkIncome.subtract(mkReturn));

        List<CityResult> cities = List.of(
                cityResult(City.KYIV, kyivBaseGross, BigDecimal.ZERO, kyivExpenses),
                cityResult(City.ODESA, odesaBaseGross, odesaManualGross, odesaExpenses)
        );

        List<DocumentLine> documents = includeDocuments
                ? resolved.stream().limit(properties.getMaxAuditDocuments()).map(this::toDocumentLine).toList()
                : List.of();
        boolean auditTruncated = includeDocuments && resolved.size() > properties.getMaxAuditDocuments();
        BigDecimal operatingTotal = money(kyivExpenses.add(odesaExpenses));
        boolean complete = unclassifiedCount == 0
                && request.odesaMasterClassIncome() != null
                && request.odesaMasterClassReturn() != null
                && request.odesaAdditionalSalary() != null
                && inventory.negativeClosingPositionCount() == 0
                && inventory.zeroValueClosingPositionCount() == 0;

        return new FolioProfitReportResponse(
                true,
                month.toString(),
                OffsetDateTime.now(REPORT_ZONE),
                complete,
                RULE_VERSION,
                new Inputs(taxShare, "REGISTERED_EMPLOYEE_SHARE", rubRate,
                        request.odesaMasterClassIncome(), request.odesaMasterClassReturn(),
                        request.odesaAdditionalSalary(),
                        List.copyOf(properties.getKyivWarehouseIds()), List.of(properties.getOdesaWarehouseId()),
                        kyivStockWarehouseIds, odesaStockWarehouseIds),
                cities,
                inventory.results(),
                expenseRows,
                documents,
                new Controls(resolved.size(), money(selectedAmount), operatingTotal, money(capitalizedTotal),
                        money(excludedTotal), money(unclassifiedTotal), unclassifiedCount, auditTruncated,
                        Map.copyOf(taxPools)),
                List.copyOf(warnings)
        );
    }

    private InventoryComputation calculateInventory(
            YearMonth month,
            List<Integer> kyivWarehouseIds,
            List<Integer> odesaWarehouseIds) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>(kyivWarehouseIds);
        selected.addAll(odesaWarehouseIds);
        List<Integer> allWarehouseIds = List.copyOf(selected);
        Map<Integer, String> warehouseNames = dao.findWarehouseNames(allWarehouseIds);
        List<Integer> missing = allWarehouseIds.stream()
                .filter(id -> !warehouseNames.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw validation("STOCK_WAREHOUSE_NOT_FOUND", "Не найдены склады ФОЛИО: " + missing);
        }

        Map<WarehouseSkuKey, InventoryPosition> positions = new LinkedHashMap<>();
        for (InventoryOpeningRow row : dao.findInventoryOpenings(allWarehouseIds)) {
            InventoryPosition position = positions.computeIfAbsent(
                    new WarehouseSkuKey(row.warehouseId(), safe(row.sku())), ignored -> new InventoryPosition());
            BigDecimal initialValue = row.initialQuantity().multiply(row.initialAccountingPrice());
            position.openingQuantity = position.openingQuantity.add(row.initialQuantity());
            position.closingQuantity = position.closingQuantity.add(row.initialQuantity());
            position.openingValue = position.openingValue.add(initialValue);
            position.closingValue = position.closingValue.add(initialValue);
        }
        for (InventoryMovementRow row : dao.findInventoryMovements(
                allWarehouseIds, month.atDay(1), month.plusMonths(1).atDay(1))) {
            InventoryPosition position = positions.computeIfAbsent(
                    new WarehouseSkuKey(row.warehouseId(), safe(row.sku())), ignored -> new InventoryPosition());
            position.openingQuantity = position.openingQuantity.add(row.openingQuantityDelta());
            position.closingQuantity = position.closingQuantity.add(row.closingQuantityDelta());
            position.openingValue = position.openingValue.add(row.openingAccountingValueDelta());
            position.closingValue = position.closingValue.add(row.closingAccountingValueDelta());
        }

        InventoryResult kyiv = inventoryResult(
                City.KYIV, kyivWarehouseIds, warehouseNames, positions);
        InventoryResult odesa = inventoryResult(
                City.ODESA, odesaWarehouseIds, warehouseNames, positions);
        return new InventoryComputation(
                List.of(kyiv, odesa),
                kyiv.negativeClosingPositionCount() + odesa.negativeClosingPositionCount(),
                kyiv.zeroValueClosingPositionCount() + odesa.zeroValueClosingPositionCount());
    }

    private static InventoryResult inventoryResult(
            City city,
            List<Integer> warehouseIds,
            Map<Integer, String> warehouseNames,
            Map<WarehouseSkuKey, InventoryPosition> positions) {
        List<WarehouseInventoryResult> warehouses = warehouseIds.stream()
                .map(id -> warehouseInventory(id, warehouseNames.get(id), positions))
                .toList();
        BigDecimal opening = warehouses.stream()
                .map(WarehouseInventoryResult::openingAccountingValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = warehouses.stream()
                .map(WarehouseInventoryResult::closingAccountingValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InventoryResult(
                city.name(), warehouseIds, money(opening), money(closing), money(closing.subtract(opening)),
                warehouses.stream().mapToInt(WarehouseInventoryResult::openingPositionCount).sum(),
                warehouses.stream().mapToInt(WarehouseInventoryResult::closingPositionCount).sum(),
                warehouses.stream().mapToInt(WarehouseInventoryResult::negativeClosingPositionCount).sum(),
                warehouses.stream().mapToInt(WarehouseInventoryResult::zeroValueClosingPositionCount).sum(),
                warehouses);
    }

    private static WarehouseInventoryResult warehouseInventory(
            int warehouseId,
            String warehouseName,
            Map<WarehouseSkuKey, InventoryPosition> positions) {
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal closing = BigDecimal.ZERO;
        int openingPositions = 0;
        int closingPositions = 0;
        int negativeClosingPositions = 0;
        int zeroValueClosingPositions = 0;
        for (Map.Entry<WarehouseSkuKey, InventoryPosition> entry : positions.entrySet()) {
            if (entry.getKey().warehouseId() != warehouseId) {
                continue;
            }
            InventoryPosition position = entry.getValue();
            opening = opening.add(position.openingValue);
            closing = closing.add(position.closingValue);
            if (nonZeroQuantity(position.openingQuantity)) {
                openingPositions++;
            }
            if (nonZeroQuantity(position.closingQuantity)) {
                closingPositions++;
                if (position.closingQuantity.compareTo(QUANTITY_EPSILON.negate()) < 0) {
                    negativeClosingPositions++;
                }
                if (position.closingValue.abs().compareTo(new BigDecimal("0.005")) < 0) {
                    zeroValueClosingPositions++;
                }
            }
        }
        return new WarehouseInventoryResult(
                warehouseId, warehouseName, money(opening), money(closing), money(closing.subtract(opening)),
                openingPositions, closingPositions, negativeClosingPositions, zeroValueClosingPositions);
    }

    private static boolean nonZeroQuantity(BigDecimal value) {
        return value.abs().compareTo(QUANTITY_EPSILON) > 0;
    }

    private static List<Integer> warehouseIdsOrDefault(
            List<Integer> requested,
            List<Integer> fallback,
            String field) {
        List<Integer> source = requested == null ? fallback : requested;
        if (source == null || source.isEmpty()) {
            throw validation("STOCK_WAREHOUSES_REQUIRED", field + " не может быть пустым");
        }
        if (source.size() > MAX_STOCK_WAREHOUSES_PER_CITY) {
            throw validation("TOO_MANY_STOCK_WAREHOUSES",
                    field + " содержит больше " + MAX_STOCK_WAREHOUSES_PER_CITY + " складов");
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (Integer warehouseId : source) {
            if (warehouseId == null || warehouseId <= 0) {
                throw validation("STOCK_WAREHOUSE_INVALID", field + " содержит некорректный номер склада");
            }
            normalized.add(warehouseId);
        }
        return List.copyOf(normalized);
    }

    private static void assertWarehousesDoNotOverlap(List<Integer> kyiv, List<Integer> odesa) {
        Set<Integer> overlap = new LinkedHashSet<>(kyiv);
        overlap.retainAll(odesa);
        if (!overlap.isEmpty()) {
            throw validation("STOCK_WAREHOUSES_OVERLAP",
                    "Один склад нельзя одновременно отнести к Киеву и Одессе: " + overlap);
        }
    }

    private List<ResolvedPayment> resolvePayments(YearMonth target, BigDecimal rubRate, List<Warning> warnings) {
        List<PaymentRow> candidates = dao.findPaymentCandidates(
                target.atDay(1), target.plusMonths(1).atDay(1), target.format(FOLIO_PERIOD));
        List<ResolvedPayment> result = new ArrayList<>();
        for (PaymentRow row : candidates) {
            PeriodResolution period = resolvePeriod(row);
            if (period.ambiguous()) {
                warnings.add(warning("AMBIGUOUS_EXPLICIT_PERIOD",
                        "В примечании документа найдено несколько разных отчётных месяцев; документ исключён",
                        Map.of("paymentId", row.paymentId(), "documentNumber", safe(row.documentNumber()))));
                continue;
            }
            if (!target.equals(period.month())) {
                continue;
            }
            result.add(new ResolvedPayment(row, classifier.classify(row, rubRate), period));
        }
        return result;
    }

    private static PeriodResolution resolvePeriod(PaymentRow row) {
        Matcher matcher = EXPLICIT_PERIOD.matcher(safe(row.note()));
        Set<YearMonth> periods = new LinkedHashSet<>();
        while (matcher.find()) {
            periods.add(YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
        }
        if (periods.size() > 1) {
            return new PeriodResolution(periods.iterator().next(), "EXPLICIT_NOTE", true);
        }
        if (periods.size() == 1) {
            return new PeriodResolution(periods.iterator().next(), "EXPLICIT_NOTE", false);
        }
        return new PeriodResolution(YearMonth.from(row.documentDate()), "DOCUMENT_DATE", false);
    }

    private boolean allocateTax(
            ClassifiedPayment classified,
            BigDecimal odesaShare,
            Map<SummaryKey, SummaryAccumulator> summaries,
            Map<String, BigDecimal> pools,
            List<Warning> warnings) {
        PaymentRow row = classified.source();
        String identifiers = upper(row.purposeCode()) + " " + upper(row.expenseCode()) + " "
                + upper(row.name()) + " " + upper(row.documentClass());
        BigDecimal amount = classified.reportAmount();
        if (containsAny(identifiers, "МАЛАФОП", "MALAFOP")) {
            BigDecimal odesa = money(amount.multiply(odesaShare));
            BigDecimal kyiv = money(amount.subtract(odesa));
            addSummary(summaries, City.KYIV, Category.TAXES, Treatment.OPERATING_EXPENSE, kyiv, kyiv, 1);
            addSummary(summaries, City.ODESA, Category.TAXES, Treatment.OPERATING_EXPENSE, odesa, odesa, 1);
            pools.merge("MALAFOP", amount, BigDecimal::add);
            return true;
        }
        if (containsAny(identifiers, "КОНДФОП", "KONDFOP")) {
            addSummary(summaries, City.KYIV, Category.TAXES, Treatment.OPERATING_EXPENSE, amount, amount, 1);
            pools.merge("KONDFOP", amount, BigDecimal::add);
            return true;
        }
        addSummary(summaries, City.NONE, Category.UNCLASSIFIED, Treatment.UNCLASSIFIED,
                amount, BigDecimal.ZERO, 1);
        pools.merge("UNKNOWN", amount, BigDecimal::add);
        warnings.add(warning("UNKNOWN_TAX_POOL",
                "Налоговый документ не удалось связать с ФОП; он не уменьшает прибыль",
                Map.of("paymentId", row.paymentId(), "amount", amount)));
        return false;
    }

    private static BigDecimal grossFor(List<GrossMarginRow> rows, List<Integer> warehouses) {
        BigDecimal total = rows.stream()
                .filter(GrossMarginRow::accounted)
                .filter(row -> !row.returnDocument())
                .filter(row -> !"Я".equalsIgnoreCase(safe(row.organizationType())))
                .filter(row -> warehouses.contains(row.warehouseId()))
                .map(GrossMarginRow::grossMargin)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return money(total);
    }

    private static BigDecimal cityOperatingExpenses(Map<SummaryKey, SummaryAccumulator> summaries, City city) {
        return money(summaries.entrySet().stream()
                .filter(entry -> entry.getKey().city() == city)
                .map(entry -> entry.getValue().profitImpact)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CityResult cityResult(City city, BigDecimal baseGross, BigDecimal manualGross, BigDecimal expenses) {
        BigDecimal gross = money(baseGross.add(manualGross));
        return new CityResult(city.name(), money(baseGross), money(manualGross), gross,
                money(expenses), money(gross.subtract(expenses)));
    }

    private ExpenseSummary toSummary(SummaryKey key, SummaryAccumulator value) {
        return new ExpenseSummary(key.city().name(), key.category().name(), classifier.label(key.category()),
                key.treatment().name(), money(value.amount), money(value.profitImpact), value.documentCount);
    }

    private DocumentLine toDocumentLine(ResolvedPayment resolved) {
        PaymentRow row = resolved.source();
        ClassifiedPayment classified = resolved.classified();
        return new DocumentLine(
                row.paymentId(), row.documentNumber(), row.documentDate(), resolved.period().month().toString(),
                resolved.period().source(), row.bank() ? "BANK" : "CASH", row.warehouseId(),
                row.purposeCode(), row.expenseCode(), row.name(), row.documentClass(), money(row.amount()),
                classified.sourceCurrency(), classified.reportAmount(), REPORT_CURRENCY, classified.city().name(),
                classified.category().name(), classified.treatment().name(),
                classified.treatment() == Treatment.OPERATING_EXPENSE || classified.treatment() == Treatment.TAX_POOL,
                classified.reason());
    }

    private static BigDecimal profitImpact(ClassifiedPayment classified) {
        return classified.treatment() == Treatment.OPERATING_EXPENSE
                ? classified.reportAmount()
                : BigDecimal.ZERO;
    }

    private static void addSummary(
            Map<SummaryKey, SummaryAccumulator> summaries,
            City city,
            Category category,
            Treatment treatment,
            BigDecimal amount,
            BigDecimal profitImpact,
            int documentCount) {
        SummaryAccumulator accumulator = summaries.computeIfAbsent(
                new SummaryKey(city, category, treatment), ignored -> new SummaryAccumulator());
        accumulator.amount = accumulator.amount.add(amount);
        accumulator.profitImpact = accumulator.profitImpact.add(profitImpact);
        accumulator.documentCount += documentCount;
    }

    private static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw validation("MONTH_INVALID", "Параметр month обязателен в формате YYYY-MM");
        }
    }

    private static BigDecimal fractionOrDefault(
            BigDecimal requested, BigDecimal fallback, String code, String message) {
        BigDecimal value = requested == null ? fallback : requested;
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw validation(code, message);
        }
        return value;
    }

    private static BigDecimal positiveOrDefault(
            BigDecimal requested, BigDecimal fallback, String code, String message) {
        BigDecimal value = requested == null ? fallback : requested;
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw validation(code, message);
        }
        return value;
    }

    private static BigDecimal optionalNonNegative(BigDecimal value, String code) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw validation(code, "Значение не может быть отрицательным");
        }
        return money(value);
    }

    private static FolioAccountValidationException validation(String code, String message) {
        return new FolioAccountValidationException(code, message);
    }

    private static Warning warning(String code, String message, Map<String, Object> details) {
        return new Warning(code, message, details);
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String value) {
        return safe(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record Request(
            String month,
            BigDecimal odesaTaxShare,
            BigDecimal rubToUahRate,
            BigDecimal odesaMasterClassIncome,
            BigDecimal odesaMasterClassReturn,
            BigDecimal odesaAdditionalSalary,
            List<Integer> kyivStockWarehouseIds,
            List<Integer> odesaStockWarehouseIds
    ) {
    }

    private record ResolvedPayment(PaymentRow source, ClassifiedPayment classified, PeriodResolution period) {
    }

    private record PeriodResolution(YearMonth month, String source, boolean ambiguous) {
    }

    private record SummaryKey(City city, Category category, Treatment treatment) {
    }

    private record WarehouseSkuKey(int warehouseId, String sku) {
    }

    private record InventoryComputation(
            List<InventoryResult> results,
            int negativeClosingPositionCount,
            int zeroValueClosingPositionCount) {
    }

    private static final class InventoryPosition {
        private BigDecimal openingQuantity = BigDecimal.ZERO;
        private BigDecimal closingQuantity = BigDecimal.ZERO;
        private BigDecimal openingValue = BigDecimal.ZERO;
        private BigDecimal closingValue = BigDecimal.ZERO;
    }

    private static final class SummaryAccumulator {
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal profitImpact = BigDecimal.ZERO;
        private int documentCount;
    }
}
