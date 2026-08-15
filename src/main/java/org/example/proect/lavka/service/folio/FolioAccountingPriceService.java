package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.ArticleRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementTotals;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseScope;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullStatusResponse;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse.AccountingMethod;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse.Issue;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse.PriceState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class FolioAccountingPriceService {

    private static final Charset FOLIO_CHARSET = Charset.forName("windows-1251");
    // I_UCHET_TOVAR treats an expense as negative when it exceeds the running
    // balance by more than 1e-11. Keep the Java precheck at the same boundary.
    private static final BigDecimal NEGATIVE_EPSILON = new BigDecimal("0.00000000001");
    private static final BigDecimal POSTCHECK_ABSOLUTE_EPSILON = new BigDecimal("0.000001");
    private static final BigDecimal POSTCHECK_RELATIVE_EPSILON = new BigDecimal("0.000000000001");
    private static final MovementTotals EMPTY_MOVEMENT_TOTALS =
            new MovementTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private final FolioAccountingPriceDao dao;
    private final TaskExecutor executor;
    private final Clock clock;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;
    private final boolean apiEnabled;
    private final boolean applyEnabled;
    private final boolean fullApplyEnabled;
    private final int lockTimeoutMs;
    private final int queryTimeoutSeconds;
    private final int maxReportedWarnings;
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);
    private final AtomicReference<FolioAccountingPriceFullStatusResponse> fullStatus =
            new AtomicReference<>(idleStatus());

    @Autowired
    public FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            @Qualifier("folioAccountingPriceExecutor") TaskExecutor executor,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager,
            @Value("${lavka.folio.accounting-prices.api-enabled:false}") boolean apiEnabled,
            @Value("${lavka.folio.accounting-prices.apply-enabled:false}") boolean applyEnabled,
            @Value("${lavka.folio.accounting-prices.full-apply-enabled:false}") boolean fullApplyEnabled,
            @Value("${lavka.folio.accounting-prices.lock-timeout-ms:5000}") int lockTimeoutMs,
            @Value("${lavka.folio.accounting-prices.query-timeout-seconds:120}") int queryTimeoutSeconds,
            @Value("${lavka.folio.accounting-prices.max-reported-warnings:200}") int maxReportedWarnings,
            @Value("${lavka.folio.accounting-prices.zone:Europe/Kyiv}") String zone
    ) {
        this(dao, executor, Clock.system(ZoneId.of(zone)), transactionManager,
                apiEnabled, applyEnabled, fullApplyEnabled, lockTimeoutMs,
                queryTimeoutSeconds, maxReportedWarnings);
    }

    FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            @Qualifier("folioAccountingPriceExecutor") TaskExecutor executor,
            Clock clock,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager,
            boolean apiEnabled,
            boolean applyEnabled,
            boolean fullApplyEnabled,
            int lockTimeoutMs,
            int queryTimeoutSeconds,
            int maxReportedWarnings
    ) {
        this.dao = dao;
        this.executor = executor;
        this.clock = clock;
        this.apiEnabled = apiEnabled;
        this.applyEnabled = applyEnabled;
        this.fullApplyEnabled = fullApplyEnabled;
        this.lockTimeoutMs = Math.max(0, lockTimeoutMs);
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
        this.maxReportedWarnings = Math.max(1, maxReportedWarnings);

        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setTimeout(this.queryTimeoutSeconds);

        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.writeTransaction.setTimeout(this.queryTimeoutSeconds);
    }

    public FolioAccountingPriceRecalculationResponse recalculate(
            FolioAccountingPriceRecalculationRequest request) {
        requireApiEnabled();
        String sku = validatePointRequest(request);
        boolean previewOnly = request.previewOnly();
        if (!previewOnly && !applyEnabled) {
            throw new FolioAccountingPriceDisabledException(
                    "ACCOUNTING_PRICE_APPLY_DISABLED",
                    "Folio accounting-price apply is disabled; use previewOnly=true or enable the server feature flag"
            );
        }
        if (!operationRunning.compareAndSet(false, true)) {
            throw new FolioAccountingPriceBusyException();
        }
        try {
            if (previewOnly) {
                return Objects.requireNonNull(readTransaction.execute(status ->
                        inspect(sku, request.warehouseId(), false, true)));
            }
            try {
                return Objects.requireNonNull(writeTransaction.execute(status ->
                        applyOne(sku, request.warehouseId())));
            } catch (CannotAcquireLockException e) {
                throw new FolioAccountingPriceBusyException();
            }
        } finally {
            operationRunning.set(false);
        }
    }

    public FolioAccountingPriceFullStatusResponse requestFull(
            FolioAccountingPriceFullRecalculationRequest request) {
        requireApiEnabled();
        validateFullRequest(request);
        if (!request.previewOnly() && (!applyEnabled || !fullApplyEnabled)) {
            throw new FolioAccountingPriceDisabledException(
                    "ACCOUNTING_PRICE_FULL_APPLY_DISABLED",
                    "Full Folio accounting-price apply is disabled; use previewOnly=true or enable both server feature flags"
            );
        }
        if (!operationRunning.compareAndSet(false, true)) {
            return busyFullStatus();
        }

        String jobId = UUID.randomUUID().toString();
        FolioAccountingPriceFullStatusResponse queued = new FolioAccountingPriceFullStatusResponse(
                true, true, true, jobId, "QUEUED", request,
                LocalDateTime.now(clock), null,
                0, 0, 0, 0, 0, 0, null,
                0, false, List.of(), null
        );
        fullStatus.set(queued);
        try {
            executor.execute(() -> runFull(jobId, request));
        } catch (RuntimeException e) {
            operationRunning.set(false);
            fullStatus.set(failedStatus(queued, e));
            throw e;
        }
        return queued;
    }

    public FolioAccountingPriceFullStatusResponse status(boolean accepted) {
        requireApiEnabled();
        FolioAccountingPriceFullStatusResponse current = fullStatus.get();
        return new FolioAccountingPriceFullStatusResponse(
                current.ok(), accepted, current.running(), current.jobId(), current.status(),
                current.request(), current.startedAt(), current.completedAt(),
                current.totalProducts(), current.processedProducts(), current.eligibleProducts(),
                current.recalculatedProducts(), current.priceChangedProducts(), current.skippedProducts(),
                current.currentSku(), current.warningCount(), current.warningsTruncated(),
                current.warnings(), current.error()
        );
    }

    private FolioAccountingPriceFullStatusResponse busyFullStatus() {
        FolioAccountingPriceFullStatusResponse current = fullStatus.get();
        if (current.running()) {
            return status(false);
        }
        return new FolioAccountingPriceFullStatusResponse(
                false, false, true, null, "BUSY", null,
                null, null, 0, 0, 0, 0, 0, 0, null,
                0, false, List.of(),
                "A point Folio accounting-price recalculation is already running"
        );
    }

    private FolioAccountingPriceRecalculationResponse applyOne(String sku, int warehouseId) {
        dao.acquireRecalculationMutex(lockTimeoutMs);
        FolioAccountingPriceRecalculationResponse inspection = inspect(sku, warehouseId, true, false);
        if (!inspection.eligibleToApply()) {
            return blockedApply(inspection);
        }

        WarehouseScope scope = requireScope(warehouseId);
        List<MovementRow> movementsBefore = dao.findChronologicalMovements(
                sku, scope.affectedWarehouseIds(), true);
        dao.rebuildOne(sku, warehouseId, queryTimeoutSeconds);
        int scratchRowsAfter = dao.countScratchRows();
        if (scratchRowsAfter != 0) {
            throw new IllegalStateException(
                    "Folio left " + scratchRowsAfter + " rows in TMP_MOVE; recalculation was rolled back");
        }

        List<PriceState> after = priceStates(
                dao.findArticles(sku, scope.affectedWarehouseIds(), false),
                dao.findMovementTotals(sku, scope.affectedWarehouseIds())
        );
        List<MovementRow> movementsAfter = dao.findChronologicalMovements(
                sku, scope.affectedWarehouseIds(), true);
        verifyPostconditions(inspection.before(), after, movementsBefore, movementsAfter);
        boolean changed = pricesChanged(inspection.before(), after);
        log.info("[folio.accounting-price] recalculated sku={} warehouse={} affected={} priceChanged={}",
                sku, warehouseId, scope.affectedWarehouseIds(), changed);
        return new FolioAccountingPriceRecalculationResponse(
                true, false, "RECALCULATED", sku, warehouseId,
                scope.affectedWarehouseIds(), inspection.accountingMethod(),
                true, true, changed, inspection.before(), after,
                inspection.warnings(), List.of()
        );
    }

    private FolioAccountingPriceRecalculationResponse inspect(String sku,
                                                               int warehouseId,
                                                               boolean forUpdate,
                                                               boolean previewOnly) {
        WarehouseScope scope = requireScope(warehouseId);
        AccountingMethod method = method(scope.requested().rawAccountingCode());
        List<Integer> warehouseIds = scope.affectedWarehouseIds();
        List<ArticleRow> articles = dao.findArticles(sku, warehouseIds, forUpdate);
        if (articles.stream().noneMatch(article -> article.warehouseId() == warehouseId)) {
            throw new FolioAccountingPriceNotFoundException(
                    "FOLIO_PRODUCT_NOT_FOUND",
                    "Folio product " + sku + " was not found in warehouse " + warehouseId
            );
        }

        List<Issue> warnings = new ArrayList<>();
        List<Issue> errors = new ArrayList<>();
        validateAccountingScope(scope, method, articles, warnings);

        int scratchRows = dao.countScratchRows();
        if (scratchRows != 0) {
            errors.add(issue(
                    "TMP_MOVE_NOT_EMPTY",
                    "Folio scratch table TMP_MOVE is not empty; automatic recalculation is unsafe",
                    "rowCount", scratchRows
            ));
        }

        List<MovementRow> movements = dao.findChronologicalMovements(
                sku, warehouseIds, forUpdate);
        validateMovements(articles, movements, warnings);

        List<PriceState> before = priceStates(
                articles, dao.findMovementTotals(sku, warehouseIds));
        boolean eligible = warnings.isEmpty() && errors.isEmpty();
        return new FolioAccountingPriceRecalculationResponse(
                true, previewOnly,
                previewOnly
                        ? (eligible ? "PREVIEW_READY" : "PREVIEW_BLOCKED")
                        : (eligible ? "READY" : "BLOCKED"),
                sku, warehouseId, warehouseIds, method,
                eligible, false, null, before, List.of(),
                List.copyOf(warnings), List.copyOf(errors)
        );
    }

    private void validateAccountingScope(WarehouseScope scope,
                                         AccountingMethod method,
                                         List<ArticleRow> articles,
                                         List<Issue> warnings) {
        if (method.calculationMode() != 0) {
            warnings.add(issue(
                    "ACCOUNTING_METHOD_UNSUPPORTED",
                    "Automatic exact rebuild is enabled only for the experimentally verified average-price method",
                    "rawCode", method.rawCode(),
                    "calculationMode", method.calculationMode(),
                    "method", method.name()
            ));
        }

        if (scope.requested().accountingGroup() != null
                || scope.affected().size() > 1) {
            warnings.add(issue(
                    "ACCOUNTING_GROUP_UNSUPPORTED",
                    "Automatic rebuild for a shared Folio accounting group requires a separate golden-master test",
                    "accountingGroup", scope.requested().accountingGroup(),
                    "warehouseIds", scope.affectedWarehouseIds()
            ));
        }

        List<Integer> inconsistentWarehouses = scope.affected().stream()
                .filter(row -> !Objects.equals(
                        row.rawAccountingCode(), scope.requested().rawAccountingCode()))
                .map(WarehouseRow::warehouseId)
                .toList();
        if (!inconsistentWarehouses.isEmpty()) {
            warnings.add(issue(
                    "ACCOUNTING_GROUP_SETTINGS_MISMATCH",
                    "Warehouses in the accounting group have different accounting settings",
                    "warehouseIds", inconsistentWarehouses
            ));
        }

        List<Integer> hiddenWarehouses = articles.stream()
                .filter(ArticleRow::hiddenType)
                .map(ArticleRow::warehouseId)
                .toList();
        if (!hiddenWarehouses.isEmpty()) {
            warnings.add(issue(
                    "HIDDEN_PRODUCT_TYPE",
                    "Folio skips this product type during accounting-price recalculation",
                    "warehouseIds", hiddenWarehouses
            ));
        }
    }

    private void validateMovements(List<ArticleRow> articles,
                                   List<MovementRow> movements,
                                   List<Issue> warnings) {
        MovementRow returnMovement = movements.stream()
                .filter(MovementRow::returnMovement)
                .findFirst()
                .orElse(null);
        if (returnMovement != null) {
            warnings.add(issue(
                    "RETURN_MOVEMENT_REQUIRES_REVIEW",
                    "The product has a return movement; its chronology requires the exact Folio return branch",
                    "recno", returnMovement.recno(),
                    "documentDate", returnMovement.documentDate()
            ));
        }

        MovementRow zeroQuantity = movements.stream()
                .filter(movement -> movement.quantity().compareTo(BigDecimal.ZERO) == 0)
                .findFirst()
                .orElse(null);
        if (zeroQuantity != null) {
            warnings.add(issue(
                    "ZERO_QUANTITY_ACCOUNTED_MOVEMENT",
                    "An accounted movement has zero quantity",
                    "recno", zeroQuantity.recno(),
                    "documentDate", zeroQuantity.documentDate()
            ));
        }

        MovementRow nonIntegralKey = movements.stream()
                .filter(movement -> !isIntegral(movement.documentId())
                        || (movement.documentNumber() != null && !isIntegral(movement.documentNumber())))
                .findFirst()
                .orElse(null);
        if (nonIntegralKey != null) {
            warnings.add(issue(
                    "NON_INTEGRAL_TECHNICAL_KEY",
                    "A legacy movement has a non-integral technical document key",
                    "recno", nonIntegralKey.recno(),
                    "documentId", nonIntegralKey.documentId(),
                    "documentNumber", nonIntegralKey.documentNumber()
            ));
        }

        MovementRow missingDate = movements.stream()
                .filter(movement -> movement.documentDate() == null)
                .findFirst()
                .orElse(null);
        if (missingDate != null) {
            warnings.add(issue(
                    "MOVEMENT_DATE_MISSING",
                    "An accounted movement has no document date",
                    "recno", missingDate.recno()
            ));
        }

        if (returnMovement != null || missingDate != null) {
            return;
        }
        BigDecimal running = articles.stream()
                .map(ArticleRow::initialQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (MovementRow movement : movements) {
            if (FolioAccountingPriceDao.TYPE_RECEIPT.equals(movement.documentType())) {
                running = running.add(movement.quantity());
            } else if (FolioAccountingPriceDao.TYPE_EXPENSE.equals(movement.documentType())) {
                running = running.subtract(movement.quantity());
            }
            if (running.compareTo(NEGATIVE_EPSILON.negate()) < 0) {
                warnings.add(issue(
                        "NEGATIVE_CHRONOLOGICAL_STOCK",
                        "The chronological stock becomes negative; Folio cannot safely recalculate this product",
                        "recno", movement.recno(),
                        "warehouseId", movement.warehouseId(),
                        "documentDate", movement.documentDate(),
                        "runningQuantity", running
                ));
                break;
            }
        }
    }

    private void runFull(String jobId, FolioAccountingPriceFullRecalculationRequest request) {
        MutableProgress progress = new MutableProgress(jobId, request, LocalDateTime.now(clock));
        try {
            WarehouseScope scope = requireScope(request.warehouseId());
            AccountingMethod method = method(scope.requested().rawAccountingCode());
            if (method.calculationMode() != 0) {
                throw new IllegalStateException(
                        "Full automatic recalculation is currently supported only for average-price warehouses; raw code="
                                + method.rawCode());
            }
            if (scope.requested().accountingGroup() != null
                    || scope.affected().size() > 1) {
                throw new IllegalStateException(
                        "Full automatic recalculation for accounting group "
                                + scope.requested().accountingGroup()
                                + " is not enabled before a separate golden-master test");
            }
            int scratchRowsBefore = dao.countScratchRows();
            if (scratchRowsBefore != 0) {
                throw new IllegalStateException(
                        "Folio scratch table TMP_MOVE contains " + scratchRowsBefore
                                + " rows; full recalculation was not started");
            }

            List<String> skus = dao.findSkus(request.warehouseId());
            progress.totalProducts = skus.size();
            progress.status = "RUNNING";
            publish(progress, true, null);

            for (String sku : skus) {
                progress.currentSku = sku;
                FolioAccountingPriceRecalculationResponse result;
                try {
                    if (request.previewOnly()) {
                        result = Objects.requireNonNull(readTransaction.execute(status ->
                                inspect(sku, request.warehouseId(), false, true)));
                    } else {
                        result = Objects.requireNonNull(writeTransaction.execute(status ->
                                applyOne(sku, request.warehouseId())));
                    }
                } catch (FolioAccountingPriceNotFoundException e) {
                    result = missingDuringFull(request, sku, e);
                }

                progress.processedProducts++;
                if (result.eligibleToApply()) {
                    progress.eligibleProducts++;
                }
                if (result.procedureExecuted()) {
                    progress.recalculatedProducts++;
                    if (Boolean.TRUE.equals(result.priceChanged())) {
                        progress.priceChangedProducts++;
                    }
                }
                if (!result.eligibleToApply()) {
                    progress.skippedProducts++;
                }
                addIssues(progress, sku, result.warnings());
                addIssues(progress, sku, result.errors());

                if (!result.errors().isEmpty()) {
                    throw new IllegalStateException(
                            "Fatal precheck failed for SKU " + sku + ": "
                                    + result.errors().get(0).code());
                }

                boolean negative = result.warnings().stream()
                        .anyMatch(issue -> "NEGATIVE_CHRONOLOGICAL_STOCK".equals(issue.code()));
                if (negative && !request.shouldContinueOnNegativeStock()) {
                    progress.status = "STOPPED_ON_NEGATIVE_STOCK";
                    progress.currentSku = sku;
                    publish(progress, false, null);
                    return;
                }
                publish(progress, true, null);
            }

            progress.currentSku = null;
            progress.status = progress.warningCount == 0
                    ? "COMPLETED"
                    : "COMPLETED_WITH_WARNINGS";
            publish(progress, false, null);
            log.info("[folio.accounting-price] full job={} status={} warehouse={} preview={} processed={} recalculated={} skipped={} warnings={}",
                    jobId, progress.status, request.warehouseId(), request.previewOnly(),
                    progress.processedProducts, progress.recalculatedProducts,
                    progress.skippedProducts, progress.warningCount);
        } catch (Exception e) {
            progress.status = progress.recalculatedProducts == 0 ? "FAILED" : "FAILED_PARTIAL";
            log.error("[folio.accounting-price] full job={} failed after processed={}: {}",
                    jobId, progress.processedProducts, e.getMessage(), e);
            publish(progress, false, safeMessage(e));
        } finally {
            operationRunning.set(false);
        }
    }

    private FolioAccountingPriceRecalculationResponse missingDuringFull(
            FolioAccountingPriceFullRecalculationRequest request,
            String sku,
            FolioAccountingPriceNotFoundException exception) {
        return new FolioAccountingPriceRecalculationResponse(
                true, request.previewOnly(), "SKIPPED", sku, request.warehouseId(),
                List.of(request.warehouseId()), null, false, false, null,
                List.of(), List.of(),
                List.of(issue(exception.getCode(), exception.getMessage())), List.of()
        );
    }

    private void addIssues(MutableProgress progress, String sku, List<Issue> issues) {
        for (Issue issue : issues) {
            progress.warningCount++;
            if (progress.warnings.size() >= maxReportedWarnings) {
                progress.warningsTruncated = true;
                continue;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sku", sku);
            if (issue.details() != null) {
                issue.details().forEach((key, value) -> {
                    if (value != null) {
                        details.put(key, value);
                    }
                });
            }
            progress.warnings.add(new Issue(issue.code(), issue.message(), Map.copyOf(details)));
        }
    }

    private void publish(MutableProgress progress, boolean running, String error) {
        LocalDateTime completedAt = running ? null : LocalDateTime.now(clock);
        fullStatus.set(new FolioAccountingPriceFullStatusResponse(
                error == null, false, running,
                progress.jobId, progress.status, progress.request,
                progress.startedAt, completedAt,
                progress.totalProducts, progress.processedProducts,
                progress.eligibleProducts, progress.recalculatedProducts,
                progress.priceChangedProducts, progress.skippedProducts,
                progress.currentSku, progress.warningCount,
                progress.warningsTruncated, List.copyOf(progress.warnings), error
        ));
    }

    private FolioAccountingPriceRecalculationResponse blockedApply(
            FolioAccountingPriceRecalculationResponse inspection) {
        return new FolioAccountingPriceRecalculationResponse(
                false, false, "BLOCKED", inspection.sku(), inspection.requestedWarehouseId(),
                inspection.affectedWarehouseIds(), inspection.accountingMethod(),
                false, false, null, inspection.before(), List.of(),
                inspection.warnings(), inspection.errors()
        );
    }

    private WarehouseScope requireScope(int warehouseId) {
        WarehouseScope scope = dao.findWarehouseScope(warehouseId);
        if (scope == null) {
            throw new FolioAccountingPriceNotFoundException(
                    "FOLIO_WAREHOUSE_NOT_FOUND",
                    "Folio warehouse was not found: " + warehouseId
            );
        }
        return scope;
    }

    private static AccountingMethod method(Integer rawCode) {
        if (rawCode == null || rawCode < 1000) {
            return new AccountingMethod(rawCode, 3, 0, false, "NO_RECALCULATION");
        }
        int calculationMode = Math.abs(rawCode) % 10;
        int periodMode = (Math.abs(rawCode) / 10) % 10;
        boolean includeTax = ((Math.abs(rawCode) / 100) % 10) != 0;
        String name = switch (calculationMode) {
            case 0 -> "AVERAGE";
            case 1 -> "LIFO";
            case 2 -> "FIFO";
            case 3 -> "NO_RECALCULATION";
            case 4 -> "FIXED";
            case 5 -> "BATCH";
            default -> "UNKNOWN";
        };
        return new AccountingMethod(rawCode, calculationMode, periodMode, includeTax, name);
    }

    private static List<PriceState> priceStates(List<ArticleRow> articles,
                                                Map<Integer, MovementTotals> totals) {
        return articles.stream().map(article -> {
            MovementTotals movement = totals.getOrDefault(
                    article.warehouseId(), EMPTY_MOVEMENT_TOTALS);
            return new PriceState(
                    article.warehouseId(), article.warehouseName(),
                    article.initialQuantity(), article.physicalQuantity(), article.availableQuantity(),
                    article.accountingQuantity(), article.accountingAmount(),
                    article.accountingCurrencyAmount(), article.accountingPrice(),
                    article.accountingCurrencyPrice(), article.initialAccountingPrice(),
                    article.initialAccountingCurrencyPrice(), movement.count(), movement.quantity(),
                    movement.accountingAmount(), movement.accountingCurrencyAmount()
            );
        }).toList();
    }

    private static void verifyPostconditions(List<PriceState> before,
                                             List<PriceState> after,
                                             List<MovementRow> movementsBefore,
                                             List<MovementRow> movementsAfter) {
        if (!movementsBefore.equals(movementsAfter)) {
            throw new IllegalStateException(
                    "Folio movement structure changed during accounting-price rebuild");
        }
        if (before.size() != after.size()) {
            throw new IllegalStateException(
                    "Folio article scope changed during accounting-price rebuild");
        }

        Map<Integer, PriceState> afterByWarehouse = new LinkedHashMap<>();
        for (PriceState state : after) {
            afterByWarehouse.put(state.warehouseId(), state);
        }
        for (PriceState oldState : before) {
            PriceState newState = afterByWarehouse.get(oldState.warehouseId());
            if (newState == null
                    || !sameNumber(oldState.initialQuantity(), newState.initialQuantity())
                    || !sameNumber(oldState.physicalQuantity(), newState.physicalQuantity())
                    || !sameNumber(oldState.availableQuantity(), newState.availableQuantity())
                    || !sameNumber(oldState.initialAccountingPrice(), newState.initialAccountingPrice())
                    || !sameNumber(oldState.initialAccountingCurrencyPrice(),
                    newState.initialAccountingCurrencyPrice())
                    || oldState.accountedMovementCount() != newState.accountedMovementCount()
                    || !sameNumber(oldState.accountedMovementQuantity(),
                    newState.accountedMovementQuantity())) {
                throw new IllegalStateException(
                        "Folio changed a protected stock or movement invariant for warehouse "
                                + oldState.warehouseId());
            }

            BigDecimal expectedAmount = newState.initialQuantity()
                    .multiply(newState.initialAccountingPrice())
                    .add(newState.accountedMovementAmount());
            BigDecimal expectedCurrencyAmount = newState.initialQuantity()
                    .multiply(newState.initialAccountingCurrencyPrice())
                    .add(newState.accountedMovementCurrencyAmount());
            if (!closeNumber(expectedAmount, newState.accountingAmount())
                    || !closeNumber(expectedCurrencyAmount,
                    newState.accountingCurrencyAmount())) {
                throw new IllegalStateException(
                        "Folio accounting amount does not match the signed movement history for warehouse "
                                + oldState.warehouseId());
            }

            if (newState.accountingQuantity().compareTo(NEGATIVE_EPSILON) > 0) {
                BigDecimal expectedPrice = newState.accountingAmount()
                        .divide(newState.accountingQuantity(), MathContext.DECIMAL64);
                BigDecimal expectedCurrencyPrice = newState.accountingCurrencyAmount()
                        .divide(newState.accountingQuantity(), MathContext.DECIMAL64);
                if (!closeNumber(expectedPrice, newState.accountingPrice())
                        || !closeNumber(expectedCurrencyPrice,
                        newState.accountingCurrencyPrice())) {
                    throw new IllegalStateException(
                            "Folio accounting price does not match amount divided by quantity for warehouse "
                                    + oldState.warehouseId());
                }
            }
        }

        if (after.size() == 1) {
            BigDecimal expectedQuantity = before.get(0).initialQuantity();
            for (MovementRow movement : movementsBefore) {
                if (FolioAccountingPriceDao.TYPE_RECEIPT.equals(movement.documentType())) {
                    expectedQuantity = expectedQuantity.add(movement.quantity());
                } else if (FolioAccountingPriceDao.TYPE_EXPENSE.equals(movement.documentType())) {
                    expectedQuantity = expectedQuantity.subtract(movement.quantity());
                }
            }
            if (expectedQuantity.subtract(after.get(0).accountingQuantity()).abs()
                    .compareTo(NEGATIVE_EPSILON) > 0) {
                throw new IllegalStateException(
                        "Folio accounting quantity does not match the recalculated movement chronology");
            }
        }
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean pricesChanged(List<PriceState> before, List<PriceState> after) {
        if (before == null || after == null || before.size() != after.size()) {
            return true;
        }
        Map<Integer, PriceState> afterByWarehouse = new LinkedHashMap<>();
        for (PriceState state : after) {
            afterByWarehouse.put(state.warehouseId(), state);
        }
        for (PriceState oldState : before) {
            PriceState newState = afterByWarehouse.get(oldState.warehouseId());
            if (newState == null
                    || !sameNumber(oldState.accountingPrice(), newState.accountingPrice())
                    || !sameNumber(oldState.accountingCurrencyPrice(),
                    newState.accountingCurrencyPrice())) {
                return true;
            }
        }
        return false;
    }

    private static boolean closeNumber(BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null) {
            return false;
        }
        BigDecimal relative = expected.abs().multiply(POSTCHECK_RELATIVE_EPSILON);
        BigDecimal tolerance = relative.max(POSTCHECK_ABSOLUTE_EPSILON);
        return expected.subtract(actual).abs().compareTo(tolerance) <= 0;
    }

    private void requireApiEnabled() {
        if (!apiEnabled) {
            throw new FolioAccountingPriceDisabledException(
                    "ACCOUNTING_PRICE_API_DISABLED",
                    "Folio accounting-price API is disabled until external /admin authorization is confirmed"
            );
        }
    }

    private static boolean isIntegral(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() <= 0;
    }

    private static String validatePointRequest(FolioAccountingPriceRecalculationRequest request) {
        if (request == null) {
            throw new FolioAccountValidationException(
                    "ACCOUNTING_PRICE_REQUEST_REQUIRED", "Request body is required");
        }
        if (request.previewOnly() == null) {
            throw new FolioAccountValidationException(
                    "PREVIEW_ONLY_REQUIRED", "previewOnly must be explicitly true or false");
        }
        if (request.warehouseId() == null || request.warehouseId() <= 0) {
            throw new FolioAccountValidationException(
                    "WAREHOUSE_ID_INVALID", "warehouseId must be greater than zero");
        }
        String sku = request.sku() == null ? null : request.sku().trim();
        if (sku == null || sku.isEmpty()) {
            throw new FolioAccountValidationException("SKU_REQUIRED", "sku is required");
        }
        CharsetEncoder encoder = FOLIO_CHARSET.newEncoder();
        if (!encoder.canEncode(sku)) {
            throw new FolioAccountValidationException(
                    "SKU_NOT_CP1251", "sku contains characters that cannot be stored in Folio CP1251");
        }
        if (sku.getBytes(FOLIO_CHARSET).length > 20) {
            throw new FolioAccountValidationException(
                    "SKU_TOO_LONG", "sku must not exceed 20 CP1251 bytes");
        }
        return sku;
    }

    private static void validateFullRequest(FolioAccountingPriceFullRecalculationRequest request) {
        if (request == null) {
            throw new FolioAccountValidationException(
                    "ACCOUNTING_PRICE_FULL_REQUEST_REQUIRED", "Request body is required");
        }
        if (request.previewOnly() == null) {
            throw new FolioAccountValidationException(
                    "PREVIEW_ONLY_REQUIRED", "previewOnly must be explicitly true or false");
        }
        if (request.warehouseId() == null || request.warehouseId() <= 0) {
            throw new FolioAccountValidationException(
                    "WAREHOUSE_ID_INVALID", "warehouseId must be greater than zero");
        }
    }

    private static Issue issue(String code, String message, Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                details.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return new Issue(code, message, Map.copyOf(details));
    }

    private static FolioAccountingPriceFullStatusResponse idleStatus() {
        return new FolioAccountingPriceFullStatusResponse(
                true, false, false, null, "IDLE", null,
                null, null, 0, 0, 0, 0, 0, 0, null,
                0, false, List.of(), null
        );
    }

    private FolioAccountingPriceFullStatusResponse failedStatus(
            FolioAccountingPriceFullStatusResponse base,
            Exception error) {
        return new FolioAccountingPriceFullStatusResponse(
                false, false, false, base.jobId(), "FAILED", base.request(),
                base.startedAt(), LocalDateTime.now(clock),
                0, 0, 0, 0, 0, 0, null,
                0, false, List.of(), safeMessage(error)
        );
    }

    private static String safeMessage(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "\u2026";
    }

    private static final class MutableProgress {
        private final String jobId;
        private final FolioAccountingPriceFullRecalculationRequest request;
        private final LocalDateTime startedAt;
        private final List<Issue> warnings = new ArrayList<>();
        private String status = "QUEUED";
        private String currentSku;
        private int totalProducts;
        private int processedProducts;
        private int eligibleProducts;
        private int recalculatedProducts;
        private int priceChangedProducts;
        private int skippedProducts;
        private int warningCount;
        private boolean warningsTruncated;

        private MutableProgress(String jobId,
                                FolioAccountingPriceFullRecalculationRequest request,
                                LocalDateTime startedAt) {
            this.jobId = jobId;
            this.request = request;
            this.startedAt = startedAt;
        }
    }
}
