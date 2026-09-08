package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReadBudget;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.SectionStatus;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.GrossMarginRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryMovementRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.InventoryOpeningRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.MasterClassMovementRow;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.CityResult;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.Controls;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.DocumentLine;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.ExpenseSummary;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.Inputs;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.InventoryResult;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.MasterClassDocumentLine;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.MasterClassSummary;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.Warning;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.WarehouseInventoryResult;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.PeriodDiagnostic;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.PeriodPolicy;
import org.example.proect.lavka.service.folio.FolioExpensePeriod.Resolution;
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

@Service
public class FolioProfitReportService {

    private static final String RULE_VERSION = "2026-09-08.2";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FolioProfitReportService.class);
    private static final String REPORT_CURRENCY = "UAH";
    private static final String MASTER_CLASS_SOURCE = "FOLIO_SCL_NAKL_SCL_MOVE";
    private static final String AMOUNT_SOURCE = "SCL_MOVE.SUM_PREDM";
    private static final String EXPENSE_DOCUMENT = "\u0420";
    private static final String RECEIPT_DOCUMENT = "\u041f";
    private static final String OWN_ORGANIZATION = "\u042f";
    private static final String RETURN_OPERATION = "\u0412\u041e\u0417\u0412\u0420\u0410\u0422";
    private static final List<String> MASTER_CLASS_SKUS = List.of(
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u044f\u043d\u0432\u0430\u0440\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0444\u0435\u0432\u0440\u0430\u043b\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u043c\u0430\u0440\u0442",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0430\u043f\u0440\u0435\u043b\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u043c\u0430\u0439",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0438\u044e\u043d\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0438\u044e\u043b\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0430\u0432\u0433\u0443\u0441\u0442",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0441\u0435\u043d\u0442\u044f\u0431\u0440",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u043e\u043a\u0442\u044f\u0431\u0440\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u043d\u043e\u044f\u0431\u0440\u044c",
            "\u041c\u0430\u0441\u0442\u0435\u0440-\u041a\u043b\u0430\u0441\u0441 \u0434\u0435\u043a\u0430\u0431\u0440\u044c");
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

    @Transactional(transactionManager = "mssqlTransactionManager", propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public FolioProfitReportResponse calculate(Request request, boolean includeDocuments) {
        // SELECT/NOLOCK report: no shared transaction can poison later independent reads
        // or fail its commit after a partial response has already been assembled.
        try (var budget = new FolioProfitReadBudget(90, 30)) {
            return calculateWithinBudget(request, includeDocuments);
        }
    }

    private FolioProfitReportResponse calculateWithinBudget(Request request, boolean includeDocuments) {
        if (!properties.isEnabled()) {
            throw validation("PROFIT_REPORT_DISABLED", "Отчёт прибыли отключён настройкой сервиса");
        }

        YearMonth month = parseMonth(request.month());
        List<Warning> warnings = new ArrayList<>();
        Map<String, SectionStatus> sections = new LinkedHashMap<>();
        ExpenseInputs expenseInputs = readSection("EXPENSE_INPUTS", sections, warnings, () -> expenseInputs(request));
        BigDecimal taxShare = expenseInputs == null ? null : expenseInputs.taxShare();
        BigDecimal rubRate = expenseInputs == null ? null : expenseInputs.rubRate();
        BigDecimal additionalSalary = expenseInputs == null ? null : expenseInputs.odesaSalary();
        BigDecimal kyivAdditionalSalary = expenseInputs == null ? null : expenseInputs.kyivSalary();
        String additionalSalarySource = expenseInputs == null ? "UNAVAILABLE"
                : request.odesaAdditionalSalary() == null ? "DEFAULT" : "REQUEST_OVERRIDE";
        String kyivSalarySource = expenseInputs == null ? "UNAVAILABLE"
                : request.kyivAdditionalSalary() == null ? "DEFAULT" : "REQUEST_OVERRIDE";
        List<PeriodDiagnostic> periodDiagnostics = new ArrayList<>();
        PaymentResolution readPayments = readSection("EXPENSES", sections, warnings, () -> {
            if (expenseInputs == null) throw validation("EXPENSE_INPUTS_UNAVAILABLE", "Параметры расходов требуют исправления");
            return resolvePayments(month, rubRate, taxShare, periodDiagnostics);
        });
        boolean expensesAvailable = readPayments != null;
        if (!expensesAvailable) periodDiagnostics.clear();
        PaymentResolution paymentResolution = expensesAvailable ? readPayments : new PaymentResolution(List.of(), 0, 0);
        List<ResolvedPayment> resolved = paymentResolution.included();
        if (paymentResolution.problemCount() > 0) warnings.add(warning("EXPENSE_PERIOD_REVIEW_REQUIRED",
                "Есть повреждённые или смешанные периоды: итог предварительный; см. periodDiagnostics",
                Map.of("count", paymentResolution.problemCount())));
        List<GrossMarginRow> grossRows = readSection("GROSS_MARGIN", sections, warnings,
                () -> dao.findGrossMargins(month.atDay(1), month.plusMonths(1).atDay(1)));
        MasterClassComputation readMasterClass = readSection("MASTER_CLASS", sections, warnings,
                () -> calculateMasterClass(month, includeDocuments, warnings));
        MasterClassComputation masterClass = readMasterClass == null ? unavailableMasterClass(month) : readMasterClass;
        if (readMasterClass != null && !masterClass.valid()) sections.put("MASTER_CLASS",
                new SectionStatus("UNAVAILABLE", "MASTER_CLASS_DATA_INCOMPLETE", null, "Данные МК требуют проверки"));
        InventoryResult kyivInventory = readSection("INVENTORY_KYIV", sections, warnings, () -> {
            List<Integer> ids = warehouseIdsOrDefault(request.kyivStockWarehouseIds(), properties.getKyivWarehouseIds(), "kyivStockWarehouseIds");
            assertWarehousesDoNotOverlap(ids, request.odesaStockWarehouseIds() == null ? List.of(properties.getOdesaWarehouseId()) : request.odesaStockWarehouseIds());
            return calculateInventory(month, City.KYIV, ids);
        });
        InventoryResult odesaInventory = readSection("INVENTORY_ODESA", sections, warnings, () -> {
            List<Integer> ids = warehouseIdsOrDefault(request.odesaStockWarehouseIds(), List.of(properties.getOdesaWarehouseId()), "odesaStockWarehouseIds");
            assertWarehousesDoNotOverlap(request.kyivStockWarehouseIds() == null ? properties.getKyivWarehouseIds() : request.kyivStockWarehouseIds(), ids);
            return calculateInventory(month, City.ODESA, ids);
        });
        List<Integer> kyivStockWarehouseIds = kyivInventory == null ? null : kyivInventory.warehouseIds();
        List<Integer> odesaStockWarehouseIds = odesaInventory == null ? null : odesaInventory.warehouseIds();
        List<InventoryResult> inventoryRows = java.util.stream.Stream.of(kyivInventory, odesaInventory).filter(java.util.Objects::nonNull).toList();
        InventoryComputation inventory = new InventoryComputation(inventoryRows,
                inventoryRows.stream().mapToInt(InventoryResult::negativeClosingPositionCount).sum(),
                inventoryRows.stream().mapToInt(InventoryResult::zeroValueClosingPositionCount).sum());

        Map<SummaryKey, SummaryAccumulator> summaries = new LinkedHashMap<>();
        Map<String, BigDecimal> taxPools = new LinkedHashMap<>();
        FolioProfitExpenseLines lines = new FolioProfitExpenseLines();
        BigDecimal selectedAmount = BigDecimal.ZERO;
        BigDecimal capitalizedTotal = BigDecimal.ZERO;
        BigDecimal excludedTotal = BigDecimal.ZERO;
        BigDecimal unclassifiedTotal = BigDecimal.ZERO;
        int unclassifiedCount = 0;

        for (ResolvedPayment payment : resolved) {
            ClassifiedPayment classified = payment.classified();
            lines.add(classified, taxShare);
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

        if (expensesAvailable) {
            addSummary(summaries, City.ODESA, Category.SALARY, Treatment.OPERATING_EXPENSE,
                    additionalSalary, additionalSalary, 0);
            addSummary(summaries, City.KYIV, Category.SALARY, Treatment.OPERATING_EXPENSE,
                    kyivAdditionalSalary, kyivAdditionalSalary, 0);
            lines.manual("KYIV_ADDITIONAL_SALARY", kyivAdditionalSalary, kyivSalarySource);
            lines.manual("ODESA_ADDITIONAL_SALARY", additionalSalary, additionalSalarySource);
        }

        if (unclassifiedCount > 0) {
            warnings.add(warning("UNCLASSIFIED_DOCUMENTS",
                    "Есть документы без подтверждённого правила; они не уменьшают прибыль",
                    Map.of("count", unclassifiedCount, "amount", money(unclassifiedTotal))));
        }
        if (request.odesaMasterClassIncome() != null || request.odesaMasterClassReturn() != null) {
            warnings.add(warning("MASTER_CLASS_LEGACY_PARAMETERS_IGNORED",
                    "Ручные параметры МК больше не участвуют в расчёте; суммы получены автоматически из ФОЛИО",
                    Map.of("source", masterClass.summary().source())));
        }
        if (expensesAvailable) warnings.add(warning("IMPORT_TRANSPORT_CAPITALIZED",
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

        BigDecimal kyivExpenses = expensesAvailable ? cityOperatingExpenses(summaries, City.KYIV) : null;
        BigDecimal odesaExpenses = expensesAvailable ? cityOperatingExpenses(summaries, City.ODESA) : null;
        BigDecimal kyivBaseGross = grossRows == null ? null : grossFor(grossRows, properties.getKyivWarehouseIds());
        BigDecimal odesaBaseGross = grossRows == null ? null : grossFor(grossRows, List.of(properties.getOdesaWarehouseId()));
        BigDecimal odesaGrossAdjustment = masterClass.valid() ? masterClass.summary().grossAdjustmentApplied() : null;

        List<CityResult> cities = List.of(
                cityResult(City.KYIV, kyivBaseGross, BigDecimal.ZERO, kyivExpenses),
                cityResult(City.ODESA, odesaBaseGross, odesaGrossAdjustment, odesaExpenses)
        );

        List<DocumentLine> documents = includeDocuments
                ? resolved.stream().filter(p -> !p.period().problem()).limit(properties.getMaxAuditDocuments())
                    .map(p -> toDocumentLine(p, taxShare, rubRate, true)).toList()
                : List.of();
        boolean auditTruncated = includeDocuments && resolved.stream().filter(p -> !p.period().problem()).count()
                > properties.getMaxAuditDocuments();
        BigDecimal operatingTotal = expensesAvailable ? money(kyivExpenses.add(odesaExpenses)) : null;
        boolean complete = unclassifiedCount == 0
                && sections.values().stream().allMatch(s -> "AVAILABLE".equals(s.status()))
                && paymentResolution.problemCount() == 0
                && masterClass.valid()
                && inventory.negativeClosingPositionCount() == 0
                && inventory.zeroValueClosingPositionCount() == 0;

        return new FolioProfitReportResponse(
                true,
                month.toString(),
                OffsetDateTime.now(REPORT_ZONE),
                complete,
                RULE_VERSION,
                new Inputs(taxShare, "REGISTERED_EMPLOYEE_SHARE", rubRate,
                        null, null, additionalSalary, additionalSalarySource,
                        List.copyOf(properties.getKyivWarehouseIds()), List.of(properties.getOdesaWarehouseId()),
                        kyivStockWarehouseIds, odesaStockWarehouseIds, kyivAdditionalSalary, kyivSalarySource),
                cities,
                inventory.results(),
                expenseRows,
                documents,
                masterClass.summary(),
                masterClass.documents(),
                expensesAvailable ? new Controls(resolved.size(), money(selectedAmount), operatingTotal, money(capitalizedTotal),
                        money(excludedTotal), money(unclassifiedTotal), unclassifiedCount, auditTruncated,
                        Map.copyOf(taxPools), paymentResolution.diagnosticCount(), paymentResolution.problemCount(),
                        (int) resolved.stream().filter(p -> p.period().problem()).count(),
                        money(resolved.stream().filter(p -> p.period().problem()).map(p -> p.classified().reportAmount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                        money(resolved.stream().filter(p -> p.period().problem()).map(p -> {
                            var a = FolioProfitExpenseLines.allocation(p.classified(), taxShare);
                            return a.kyiv().add(a.odesa());
                        }).reduce(BigDecimal.ZERO, BigDecimal::add))) : null,
                List.copyOf(warnings),
                expensesAvailable ? lines.rows() : List.of(),
                new PeriodPolicy(month.minusMonths(1).atDay(1), month.plusMonths(2).atDay(1), true,
                        "Все расходы: M-1/M/M+1 и явные маркеры вне окна. Ошибочные периоды требуют проверки; "
                        + "прежнее влияние сохранено как предварительное, без распределения смешанных сумм."),
                List.copyOf(periodDiagnostics),
                paymentResolution.diagnosticCount() > periodDiagnostics.size(),
                Map.copyOf(sections)
        );
    }

    private MasterClassComputation calculateMasterClass(
            YearMonth month,
            boolean includeDocuments,
            List<Warning> warnings) {
        int warehouseId = properties.getOdesaWarehouseId();
        String sku = MASTER_CLASS_SKUS.get(month.getMonthValue() - 1);
        boolean articleFound = dao.masterClassArticleExists(sku);
        if (!articleFound) {
            warnings.add(warning("MASTER_CLASS_ARTICLE_NOT_FOUND",
                    "Не найден точный артикул МК для отчётного месяца; ноль нельзя считать подтверждённым",
                    Map.of("warehouseId", warehouseId, "sku", sku, "month", month.toString())));
            return new MasterClassComputation(new MasterClassSummary(
                    warehouseId, sku, false, MASTER_CLASS_SOURCE,
                    null, null, null, null, null,
                    0, 0, 0, 0, false), List.of(), false);
        }

        List<MasterClassMovementRow> sourceRows = dao.findMasterClassMovements(
                warehouseId, sku, month.atDay(1), month.plusMonths(1).atDay(1));
        Map<Long, MasterClassMovementRow> uniqueRows = new LinkedHashMap<>();
        int duplicateLineCount = 0;
        for (MasterClassMovementRow row : sourceRows) {
            if (uniqueRows.putIfAbsent(row.movementId(), row) != null) {
                duplicateLineCount++;
            }
        }

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal returns = BigDecimal.ZERO;
        BigDecimal grossAlreadyInBase = BigDecimal.ZERO;
        int incomeLineCount = 0;
        int returnLineCount = 0;
        int ignoredLineCount = 0;
        int negativeAmountCount = 0;
        List<MasterClassDocumentLine> auditRows = new ArrayList<>();

        for (MasterClassMovementRow row : uniqueRows.values()) {
            MasterClassLineClassification classification = classifyMasterClassLine(row);
            if (classification.included() && row.amount().compareTo(BigDecimal.ZERO) < 0) {
                negativeAmountCount++;
            }
            if (classification.type() == MasterClassLineType.INCOME) {
                income = income.add(row.amount());
                incomeLineCount++;
                if (baseGrossIncludes(row)) {
                    grossAlreadyInBase = grossAlreadyInBase.add(row.amount().subtract(row.accountingCost()));
                }
            } else if (classification.type() == MasterClassLineType.RETURN) {
                returns = returns.add(row.amount());
                returnLineCount++;
            } else {
                ignoredLineCount++;
            }
            if (includeDocuments) {
                auditRows.add(toMasterClassDocumentLine(row, classification));
            }
        }

        if (duplicateLineCount > 0) {
            warnings.add(warning("MASTER_CLASS_DUPLICATE_MOVEMENT_ROWS",
                    "В источнике МК повторился идентификатор строки движения; дубли исключены из суммы",
                    Map.of("count", duplicateLineCount, "warehouseId", warehouseId, "sku", sku)));
        }
        if (negativeAmountCount > 0) {
            warnings.add(warning("MASTER_CLASS_NEGATIVE_SOURCE_AMOUNT",
                    "В исходных строках МК найдены отрицательные суммы; требуется проверка документов",
                    Map.of("count", negativeAmountCount, "warehouseId", warehouseId, "sku", sku)));
        }
        if (ignoredLineCount > 0) {
            warnings.add(warning("MASTER_CLASS_LINES_IGNORED",
                    "Часть строк точного артикула МК не соответствует правилу дохода или возврата и не включена",
                    Map.of("count", ignoredLineCount, "warehouseId", warehouseId, "sku", sku)));
        }

        income = money(income);
        returns = money(returns);
        BigDecimal net = money(income.subtract(returns));
        grossAlreadyInBase = money(grossAlreadyInBase);
        BigDecimal adjustment = money(net.subtract(grossAlreadyInBase));
        boolean auditTruncated = includeDocuments
                && auditRows.size() > properties.getMaxAuditDocuments();
        List<MasterClassDocumentLine> returnedAuditRows = includeDocuments
                ? auditRows.stream().limit(properties.getMaxAuditDocuments()).toList()
                : List.of();
        MasterClassSummary summary = new MasterClassSummary(
                warehouseId, sku, true, MASTER_CLASS_SOURCE,
                income, returns, net, grossAlreadyInBase, adjustment,
                incomeLineCount, returnLineCount, ignoredLineCount,
                duplicateLineCount, auditTruncated);
        return new MasterClassComputation(summary, returnedAuditRows,
                duplicateLineCount == 0 && negativeAmountCount == 0);
    }

    private static MasterClassLineClassification classifyMasterClassLine(MasterClassMovementRow row) {
        boolean expense = EXPENSE_DOCUMENT.equals(safe(row.documentType()))
                && EXPENSE_DOCUMENT.equals(safe(row.movementType()));
        boolean receipt = RECEIPT_DOCUMENT.equals(safe(row.documentType()))
                && RECEIPT_DOCUMENT.equals(safe(row.movementType()));
        if (row.lineAccounted() && expense && !row.headerReturn() && !row.lineReturn()) {
            return new MasterClassLineClassification(MasterClassLineType.INCOME, true,
                    "Проведённая расходная накладная точного артикула МК");
        }
        if (row.lineAccounted() && receipt && RETURN_OPERATION.equals(upper(row.operationKind()))) {
            return new MasterClassLineClassification(MasterClassLineType.RETURN, true,
                    "Проведённая приходная накладная с видом операции ВОЗВРАТ");
        }
        if (!row.lineAccounted()) {
            return new MasterClassLineClassification(MasterClassLineType.IGNORED, false,
                    "Строка не проведена в складском учёте");
        }
        return new MasterClassLineClassification(MasterClassLineType.IGNORED, false,
                "Тип документа или вид операции не соответствует правилу МК");
    }

    private static boolean baseGrossIncludes(MasterClassMovementRow row) {
        return row.lineAccounted()
                && !row.headerReturn()
                && !OWN_ORGANIZATION.equalsIgnoreCase(safe(row.organizationType()));
    }

    private static MasterClassDocumentLine toMasterClassDocumentLine(
            MasterClassMovementRow row,
            MasterClassLineClassification classification) {
        return new MasterClassDocumentLine(
                row.movementId(), row.documentId(), row.documentNumber(), row.documentNumberSuffix(),
                row.lineNumber(), row.documentDate(), row.warehouseId(), row.sku(),
                row.documentType(), row.movementType(), row.operationKind(),
                row.headerReturn() || row.lineReturn(), row.lineAccounted(),
                classification.type().name(), row.quantity(), money(row.unitPrice()),
                money(row.amount()), REPORT_CURRENCY, AMOUNT_SOURCE,
                classification.included(), classification.reason());
    }

    private InventoryResult calculateInventory(YearMonth month, City city, List<Integer> allWarehouseIds) {
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

        return inventoryResult(city, allWarehouseIds, warehouseNames, positions);
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

    private PaymentResolution resolvePayments(YearMonth target, BigDecimal rubRate, BigDecimal taxShare,
            List<PeriodDiagnostic> diagnostics) {
        List<PaymentRow> candidates = dao.findPaymentCandidates(
                target.minusMonths(1).atDay(1), target.plusMonths(2).atDay(1), target.format(FOLIO_PERIOD));
        List<ResolvedPayment> result = new ArrayList<>();
        int problemCount = 0;
        int diagnosticCount = 0;
        for (PaymentRow row : candidates) {
            Resolution period = FolioExpensePeriod.resolve(row.note(), row.documentDate());
            ResolvedPayment payment = new ResolvedPayment(row, classifier.classify(row, rubRate), period);
            boolean included = !period.excluded() && target.equals(period.month());
            if (period.problem()) problemCount++;
            if (period.problem() || !included) {
                diagnosticCount++;
                if (diagnostics.size() < properties.getMaxAuditDocuments()) {
                    diagnostics.add(new PeriodDiagnostic(toDocumentLine(payment, taxShare, rubRate, included),
                            period.problem() ? period.status() : "OUTSIDE_REPORT_MONTH",
                            period.problem() ? "Период требует ручной проверки; автоматическое распределение не выполнено"
                                    : "Разрешённый месяц не совпадает с отчётным",
                            included, included ? "LEGACY_PROVISIONAL_INCLUDED" : "EXCLUDED"));
                }
            }
            if (included) result.add(payment);
        }
        return new PaymentResolution(result, problemCount, diagnosticCount);
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
        BigDecimal gross = baseGross == null || manualGross == null ? null : money(baseGross.add(manualGross));
        return new CityResult(city.name(), money(baseGross), money(manualGross), gross,
                money(expenses), gross == null || expenses == null ? null : money(gross.subtract(expenses)));
    }

    private ExpenseSummary toSummary(SummaryKey key, SummaryAccumulator value) {
        return new ExpenseSummary(key.city().name(), key.category().name(), classifier.label(key.category()),
                key.treatment().name(), money(value.amount), money(value.profitImpact), value.documentCount);
    }

    private DocumentLine toDocumentLine(ResolvedPayment resolved, BigDecimal taxShare, BigDecimal rubRate,
            boolean includedInTotals) {
        PaymentRow row = resolved.source();
        ClassifiedPayment classified = resolved.classified();
        var allocation = FolioProfitExpenseLines.allocation(classified, taxShare);
        BigDecimal impact = includedInTotals ? allocation.kyiv().add(allocation.odesa()) : money(BigDecimal.ZERO);
        List<String> ids = FolioProfitExpenseLines.lineIds(classified);
        return new DocumentLine(
                row.paymentId(), row.documentNumber(), row.documentDate(), resolved.period().month().toString(),
                resolved.period().source(), row.bank() ? "BANK" : "CASH", row.warehouseId(),
                row.purposeCode(), row.expenseCode(), row.name(), row.documentClass(), money(row.amount()),
                classified.sourceCurrency(), classified.reportAmount(), REPORT_CURRENCY, classified.city().name(),
                classified.category().name(), classified.treatment().name(),
                includedInTotals && allocation.operating(),
                classified.reason(), FolioProfitExpenseLines.documentLineId(classified), ids, safeSourceInfo(row.sourceInfo()),
                resolved.period().evidence(), resolved.period().status(),
                resolved.period().problem() ? List.of(resolved.period().status()) : List.of(),
                money(impact), includedInTotals ? allocation.kyiv() : money(BigDecimal.ZERO),
                includedInTotals ? allocation.odesa() : money(BigDecimal.ZERO),
                "RUB".equals(classified.sourceCurrency()) ? rubRate : BigDecimal.ONE);
    }

    private static String safeSourceInfo(String value) {
        // IST_INF is a short classification label, not a place for payment credentials.
        return value != null && value.matches("[\\p{L}\\p{N}№ ._-]{1,32}") ? value.trim() : null;
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

    private ExpenseInputs expenseInputs(Request request) {
        return new ExpenseInputs(
                fractionOrDefault(request.odesaTaxShare(), properties.getDefaultOdesaTaxShare(),
                        "ODESA_TAX_SHARE_INVALID", "Доля налогов Одессы должна быть от 0 до 1"),
                positiveOrDefault(request.rubToUahRate(), properties.getDefaultRubToUahRate(),
                        "RUB_RATE_INVALID", "Курс RUB/UAH должен быть больше нуля"),
                optionalNonNegative(request.odesaAdditionalSalary() == null
                                ? properties.getDefaultOdesaAdditionalSalary() : request.odesaAdditionalSalary(),
                        "ODESA_ADDITIONAL_SALARY_INVALID"),
                optionalNonNegative(request.kyivAdditionalSalary(), "KYIV_ADDITIONAL_SALARY_INVALID"));
    }

    private MasterClassComputation unavailableMasterClass(YearMonth month) {
        return new MasterClassComputation(new MasterClassSummary(properties.getOdesaWarehouseId(),
                MASTER_CLASS_SKUS.get(month.getMonthValue() - 1), false, "UNAVAILABLE",
                null, null, null, null, null, 0, 0, 0, 0, false), List.of(), false);
    }

    private static <T> T readSection(String section, Map<String, SectionStatus> sections,
            List<Warning> warnings, java.util.function.Supplier<T> read) {
        String operationId = java.util.UUID.randomUUID().toString();
        long start = System.nanoTime();
        LOG.info("Folio profit section started: section={} operationId={}", section, operationId);
        try {
            FolioProfitReadBudget.remainingQuerySeconds();
            T result = java.util.Objects.requireNonNull(read.get(), "Section returned no result");
            sections.put(section, new SectionStatus("AVAILABLE", null, null, null));
            LOG.info("Folio profit section completed: section={} operationId={} durationMs={}",
                    section, operationId, (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (RuntimeException failure) {
            String code = failure instanceof FolioAccountValidationException validation ? validation.getCode()
                    : failure instanceof org.springframework.dao.QueryTimeoutException
                        ? ("PROFIT_REPORT_READ_BUDGET_EXHAUSTED".equals(failure.getMessage())
                            ? "PROFIT_REPORT_READ_BUDGET_EXHAUSTED" : "PROFIT_REPORT_READ_TIMEOUT")
                    : failure instanceof org.springframework.dao.DataAccessException
                        ? "PROFIT_REPORT_SOURCE_UNAVAILABLE" : "PROFIT_REPORT_SECTION_FAILED";
            String message = "Раздел недоступен. Проверьте параметры и источник данных; неизвестные суммы не заменены нулями.";
            sections.put(section, new SectionStatus("UNAVAILABLE", code, operationId, message));
            warnings.add(warning("PROFIT_REPORT_SECTION_UNAVAILABLE", message,
                    Map.of("section", section, "errorCode", code, "errorId", operationId)));
            // No raw exception message/SQL/financial notes/connection details in the response or log.
            Integer vendorCode = null;
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql) { vendorCode = sql.getErrorCode(); break; }
            }
            String location = java.util.Arrays.stream(failure.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("org.example.proect.lavka."))
                    .findFirst().map(StackTraceElement::toString).orElse("unavailable");
            LOG.warn("Folio profit section unavailable: section={} operationId={} durationMs={} code={} exceptionType={} sqlVendorCode={} location={}",
                    section, operationId, (System.nanoTime() - start) / 1_000_000, code,
                    failure.getClass().getSimpleName(), vendorCode, location);
            return null;
        }
    }

    private record ExpenseInputs(BigDecimal taxShare, BigDecimal rubRate,
            BigDecimal odesaSalary, BigDecimal kyivSalary) {}

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
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public record Request(
            String month,
            BigDecimal odesaTaxShare,
            BigDecimal rubToUahRate,
            BigDecimal odesaMasterClassIncome,
            BigDecimal odesaMasterClassReturn,
            BigDecimal odesaAdditionalSalary,
            List<Integer> kyivStockWarehouseIds,
            List<Integer> odesaStockWarehouseIds,
            BigDecimal kyivAdditionalSalary
    ) {
        public Request(String month, BigDecimal taxShare, BigDecimal rubRate, BigDecimal mkIncome,
                BigDecimal mkReturn, BigDecimal odesaSalary, List<Integer> kyivStock, List<Integer> odesaStock) {
            this(month, taxShare, rubRate, mkIncome, mkReturn, odesaSalary, kyivStock, odesaStock, null);
        }
    }

    private record ResolvedPayment(PaymentRow source, ClassifiedPayment classified, Resolution period) {
    }

    private record PaymentResolution(List<ResolvedPayment> included, int problemCount, int diagnosticCount) {}

    private record SummaryKey(City city, Category category, Treatment treatment) {
    }

    private record WarehouseSkuKey(int warehouseId, String sku) {
    }

    private record InventoryComputation(
            List<InventoryResult> results,
            int negativeClosingPositionCount,
            int zeroValueClosingPositionCount) {
    }

    private record MasterClassComputation(
            MasterClassSummary summary,
            List<MasterClassDocumentLine> documents,
            boolean valid) {
    }

    private record MasterClassLineClassification(
            MasterClassLineType type,
            boolean included,
            String reason) {
    }

    private enum MasterClassLineType {
        INCOME,
        RETURN,
        IGNORED
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
