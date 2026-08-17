package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.ArticleRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementTotals;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeFullChunkOutput;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeChronologyProblem;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeProtectedSnapshot;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeSkuProtectedState;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseScope;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullStatusResponse;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceNativeFullRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceNativeFullStatusResponse;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceNativeFullStatusResponse.ChunkDiagnostics;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final TransactionTemplate nativeWriteTransaction;
    private final boolean apiEnabled;
    private final boolean applyEnabled;
    private final boolean fullApplyEnabled;
    private final boolean nativeFullEnabled;
    private final boolean nativeFullApplyEnabled;
    private final Set<String> nativeFullAllowedDatabases;
    private final int nativeFullMaxChunks;
    private final int lockTimeoutMs;
    private final int queryTimeoutSeconds;
    private final int nativeFullTimeoutSeconds;
    private final int maxReportedWarnings;
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);
    private final AtomicReference<FolioAccountingPriceFullStatusResponse> fullStatus =
            new AtomicReference<>(idleStatus());
    private final AtomicReference<FolioAccountingPriceNativeFullStatusResponse> nativeFullStatus =
            new AtomicReference<>(idleNativeStatus());

    @Autowired
    public FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            @Qualifier("folioAccountingPriceExecutor") TaskExecutor executor,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager,
            @Value("${lavka.folio.accounting-prices.api-enabled:true}") boolean apiEnabled,
            @Value("${lavka.folio.accounting-prices.apply-enabled:false}") boolean applyEnabled,
            @Value("${lavka.folio.accounting-prices.full-apply-enabled:false}") boolean fullApplyEnabled,
            @Value("${lavka.folio.accounting-prices.native-full-enabled:true}") boolean nativeFullEnabled,
            @Value("${lavka.folio.accounting-prices.native-full-apply-enabled:false}") boolean nativeFullApplyEnabled,
            @Value("${lavka.folio.accounting-prices.native-full-allowed-databases:Paint_Rus,Paint_Ua}") String nativeFullAllowedDatabases,
            @Value("${lavka.folio.accounting-prices.native-full-max-chunks:10000}") int nativeFullMaxChunks,
            @Value("${lavka.folio.accounting-prices.lock-timeout-ms:5000}") int lockTimeoutMs,
            @Value("${lavka.folio.accounting-prices.query-timeout-seconds:120}") int queryTimeoutSeconds,
            @Value("${lavka.folio.accounting-prices.native-full-timeout-seconds:900}") int nativeFullTimeoutSeconds,
            @Value("${lavka.folio.accounting-prices.max-reported-warnings:200}") int maxReportedWarnings,
            @Value("${lavka.folio.accounting-prices.zone:Europe/Kyiv}") String zone
    ) {
        this(dao, executor, Clock.system(ZoneId.of(zone)), transactionManager,
                apiEnabled, applyEnabled, fullApplyEnabled,
                nativeFullEnabled, nativeFullApplyEnabled,
                parseDatabaseNames(nativeFullAllowedDatabases), nativeFullMaxChunks,
                lockTimeoutMs, queryTimeoutSeconds, nativeFullTimeoutSeconds,
                maxReportedWarnings);
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
        this(dao, executor, clock, transactionManager,
                apiEnabled, applyEnabled, fullApplyEnabled,
                false, false, Set.of("Paint_Rus"), 10_000,
                lockTimeoutMs, queryTimeoutSeconds, queryTimeoutSeconds,
                maxReportedWarnings);
    }

    FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            @Qualifier("folioAccountingPriceExecutor") TaskExecutor executor,
            Clock clock,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager,
            boolean apiEnabled,
            boolean applyEnabled,
            boolean fullApplyEnabled,
            boolean nativeFullEnabled,
            boolean nativeFullApplyEnabled,
            Set<String> nativeFullAllowedDatabases,
            int nativeFullMaxChunks,
            int lockTimeoutMs,
            int queryTimeoutSeconds,
            int maxReportedWarnings
    ) {
        this(dao, executor, clock, transactionManager,
                apiEnabled, applyEnabled, fullApplyEnabled,
                nativeFullEnabled, nativeFullApplyEnabled,
                nativeFullAllowedDatabases, nativeFullMaxChunks,
                lockTimeoutMs, queryTimeoutSeconds, queryTimeoutSeconds,
                maxReportedWarnings);
    }

    FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            @Qualifier("folioAccountingPriceExecutor") TaskExecutor executor,
            Clock clock,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager,
            boolean apiEnabled,
            boolean applyEnabled,
            boolean fullApplyEnabled,
            boolean nativeFullEnabled,
            boolean nativeFullApplyEnabled,
            Set<String> nativeFullAllowedDatabases,
            int nativeFullMaxChunks,
            int lockTimeoutMs,
            int queryTimeoutSeconds,
            int nativeFullTimeoutSeconds,
            int maxReportedWarnings
    ) {
        this.dao = dao;
        this.executor = executor;
        this.clock = clock;
        this.apiEnabled = apiEnabled;
        this.applyEnabled = applyEnabled;
        this.fullApplyEnabled = fullApplyEnabled;
        this.nativeFullEnabled = nativeFullEnabled;
        this.nativeFullApplyEnabled = nativeFullApplyEnabled;
        this.nativeFullAllowedDatabases = Set.copyOf(nativeFullAllowedDatabases);
        this.nativeFullMaxChunks = Math.max(1, nativeFullMaxChunks);
        this.lockTimeoutMs = Math.max(0, lockTimeoutMs);
        this.queryTimeoutSeconds = Math.max(1, queryTimeoutSeconds);
        this.nativeFullTimeoutSeconds = Math.max(1, nativeFullTimeoutSeconds);
        this.maxReportedWarnings = Math.max(1, maxReportedWarnings);

        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setTimeout(this.queryTimeoutSeconds);

        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.writeTransaction.setTimeout(this.queryTimeoutSeconds);

        this.nativeWriteTransaction = new TransactionTemplate(transactionManager);
        this.nativeWriteTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.nativeWriteTransaction.setTimeout(this.nativeFullTimeoutSeconds);
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
                throw new FolioAccountingPriceBusyException(e);
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

    public FolioAccountingPriceNativeFullStatusResponse requestNativeFull(
            FolioAccountingPriceNativeFullRequest request) {
        requireApiEnabled();
        validateNativeFullRequest(request);
        if (!nativeFullEnabled) {
            throw new FolioAccountingPriceDisabledException(
                    "ACCOUNTING_PRICE_NATIVE_FULL_DISABLED",
                    "Native Folio I_UCHET_TOVAR execution is disabled by the server feature flag"
            );
        }
        if (!request.previewOnly() && (!applyEnabled || !nativeFullApplyEnabled)) {
            throw new FolioAccountingPriceDisabledException(
                    "ACCOUNTING_PRICE_NATIVE_FULL_APPLY_DISABLED",
                    "Native Folio accounting-price apply is disabled; use previewOnly=true or enable the dedicated server flag"
            );
        }

        String database = dao.currentDatabaseName();
        if (!databaseAllowed(database)) {
            throw new FolioAccountingPriceDisabledException(
                    "ACCOUNTING_PRICE_NATIVE_DATABASE_NOT_ALLOWED",
                    "Native Folio recalculation is not allowed for database " + database
            );
        }
        if (!operationRunning.compareAndSet(false, true)) {
            return busyNativeStatus();
        }

        String jobId = UUID.randomUUID().toString();
        NativeProgress progress = new NativeProgress(
                jobId, request, database, LocalDateTime.now(clock));
        progress.status = "QUEUED";
        progress.phase = "QUEUED";
        publishNative(progress, true, true, null);
        try {
            executor.execute(() -> runNativeFull(progress));
        } catch (RuntimeException e) {
            operationRunning.set(false);
            progress.status = "FAILED";
            progress.phase = "FAILED";
            publishNative(progress, false, false, safeMessage(e));
            throw e;
        }
        return withNativeAccepted(nativeFullStatus.get(), true);
    }

    public FolioAccountingPriceNativeFullStatusResponse nativeFullStatus(boolean accepted) {
        requireApiEnabled();
        return withNativeAccepted(nativeFullStatus.get(), accepted);
    }

    private FolioAccountingPriceNativeFullStatusResponse busyNativeStatus() {
        FolioAccountingPriceNativeFullStatusResponse current = nativeFullStatus.get();
        if (current.running()) {
            return withNativeAccepted(current, false);
        }
        return new FolioAccountingPriceNativeFullStatusResponse(
                false, false, true, null, "BUSY", "BUSY", null,
                null, null, null, null,
                0, 0, 0, 0, 0, null,
                null, null, null, null, null,
                0, false, List.of(), null,
                "Another Folio accounting-price operation is already running"
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
                    "documentDate", formatDate(returnMovement.documentDate())
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
                    "documentDate", formatDate(zeroQuantity.documentDate())
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
        BigDecimal initialQuantity = running;
        int movementPosition = 0;
        for (MovementRow movement : movements) {
            movementPosition++;
            BigDecimal quantityBefore = running;
            String operationKind = "UNKNOWN";
            if (FolioAccountingPriceDao.TYPE_RECEIPT.equals(movement.documentType())) {
                running = running.add(movement.quantity());
                operationKind = "RECEIPT";
            } else if (FolioAccountingPriceDao.TYPE_EXPENSE.equals(movement.documentType())) {
                running = running.subtract(movement.quantity());
                operationKind = "EXPENSE";
            }
            if (running.compareTo(NEGATIVE_EPSILON.negate()) < 0) {
                BigDecimal shortage = running.abs();
                ArticleRow current = articles.stream()
                        .filter(article -> article.warehouseId() == movement.warehouseId())
                        .findFirst()
                        .orElse(null);
                Map<String, Object> operation = diagnosticMap(
                        "kind", operationKind,
                        "documentType", movement.documentType(),
                        "quantity", movement.quantity(),
                        "recno", movement.recno(),
                        "documentId", movement.documentId(),
                        "documentNumber", movement.documentNumber(),
                        "documentDate", formatDate(movement.documentDate()),
                        "warehouseId", movement.warehouseId()
                );
                Map<String, Object> currentState = current == null
                        ? Map.of()
                        : diagnosticMap(
                        "physicalQuantity", current.physicalQuantity(),
                        "availableQuantity", current.availableQuantity(),
                        "accountingQuantity", current.accountingQuantity(),
                        "accountingPrice", current.accountingPrice()
                );
                warnings.add(issue(
                        "NEGATIVE_CHRONOLOGICAL_STOCK",
                        "The chronological stock becomes negative; Folio cannot safely recalculate this product",
                        "warehouseId", movement.warehouseId(),
                        "initialQuantity", initialQuantity,
                        "quantityBefore", quantityBefore,
                        "operation", operation,
                        "quantityAfter", running,
                        "shortageQuantity", shortage,
                        "movementPosition", movementPosition,
                        "movementCount", movements.size(),
                        "currentState", currentState
                ));
                String sku = current != null
                        ? current.sku()
                        : articles.stream().map(ArticleRow::sku).findFirst().orElse("unknown");
                log.warn("[folio.accounting-price] accounting_price_negative_stock sku={} warehouse={} "
                                + "recno={} documentId={} documentNumber={} date={} "
                                + "initialQuantity={} quantityBefore={} operationType={} operationQuantity={} "
                                + "quantityAfter={} shortageQuantity={} movementPosition={} movementCount={} "
                                + "currentPhysical={} currentAvailable={} currentAccountingQuantity={} "
                                + "currentAccountingPrice={}",
                        sku, movement.warehouseId(), movement.recno(), movement.documentId(),
                        movement.documentNumber(), formatDate(movement.documentDate()),
                        initialQuantity, quantityBefore, operationKind, movement.quantity(),
                        running, shortage, movementPosition, movements.size(),
                        current == null ? null : current.physicalQuantity(),
                        current == null ? null : current.availableQuantity(),
                        current == null ? null : current.accountingQuantity(),
                        current == null ? null : current.accountingPrice());
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

    private void runNativeFull(NativeProgress progress) {
        try {
            WarehouseScope scope = requireScope(progress.request.warehouseId());
            AccountingMethod method = method(scope.requested().rawAccountingCode());
            validateNativeScope(scope, method);
            progress.accountingMethod = method;

            progress.phase = "DIAGNOSTIC_SCAN";
            progress.status = "RUNNING";
            publishNative(progress, true, true, null);
            List<NativeChronologyProblem> knownProblems =
                    dao.findNativeChronologyProblems(
                            progress.request.warehouseId(), nativeFullTimeoutSeconds);
            Set<String> skippedSkus = new LinkedHashSet<>();
            for (NativeChronologyProblem problem : knownProblems) {
                skippedSkus.add(problem.sku());
                Issue issue = nativeChronologyIssue(problem);
                addNativeIssue(progress, issue);
                log.warn("[folio.accounting-price] native_sku_skipped job={} warehouse={} sku={} code={} recno={} documentId={} documentNumber={} date={} initialQuantity={} quantityBefore={} operationQuantity={} quantityAfter={} movementPosition={} movementCount={} currentPhysical={} currentAvailable={} currentAccountingQuantity={} currentAccountingPrice={}",
                        progress.jobId, problem.warehouseId(), problem.sku(), problem.code(),
                        problem.recno(), problem.documentId(), problem.documentNumber(),
                        formatDate(problem.documentDate()), problem.initialQuantity(),
                        problem.quantityBefore(), problem.operationQuantity(),
                        problem.quantityAfter(), problem.movementPosition(),
                        problem.movementCount(), problem.physicalQuantity(),
                        problem.availableQuantity(), problem.accountingQuantity(),
                        problem.accountingPrice());
            }
            String quarantineMarker = skippedSkus.isEmpty()
                    ? null
                    : dao.findUnusedProductTypeMarker();
            if (!skippedSkus.isEmpty()
                    && (quarantineMarker == null || quarantineMarker.isBlank())) {
                throw new IllegalStateException(
                        "Folio has no unused product-type marker for safe recalculation quarantine");
            }

            progress.phase = "PRECHECK_RUNNING";
            progress.status = "RUNNING";
            publishNative(progress, true, true, null);
            NativePassResult preflight = runNativePass(
                    progress, method, true, 0, null,
                    skippedSkus, quarantineMarker);

            if (preflight.problemDetected()) {
                progress.status = "BLOCKED_NEGATIVE_STOCK";
                progress.phase = "PRECHECK_COMPLETED";
                progress.currentArt = null;
                progress.nextArt = null;
                publishNative(progress, false, false, null);
                log.warn("[folio.accounting-price] native_full_blocked job={} warehouse={} warnings={} calls={}",
                        progress.jobId, progress.request.warehouseId(),
                        progress.warningCount, progress.procedureCalls);
                return;
            }

            if (progress.request.previewOnly()) {
                progress.status = progress.warningCount == 0
                        ? "PREVIEW_READY"
                        : "PREVIEW_READY_WITH_WARNINGS";
                progress.phase = "PRECHECK_COMPLETED";
                progress.currentArt = null;
                progress.nextArt = null;
                progress.checkpointArt = null;
                publishNative(progress, false, true, null);
                log.info("[folio.accounting-price] native_preview_completed job={} warehouse={} chunks={} units={}/{}",
                        progress.jobId, progress.request.warehouseId(),
                        progress.preflightChunks, progress.progressUnits, progress.totalUnits);
                return;
            }

            // Apply is deliberately a second pass. The first pass ran the
            // exact Folio procedure but rolled every chunk back, proving that
            // the complete scope has no known native stop condition.
            NativeProtectedSnapshot protectedBaseline = captureNativeBaseline(
                    progress.database, progress.request.warehouseId(), method);
            progress.phase = "APPLY_RUNNING";
            progress.status = "RUNNING";
            progress.progressUnits = 0;
            progress.currentArt = null;
            progress.nextArt = null;
            progress.checkpointArt = null;
            publishNative(progress, true, true, null);
            runNativePass(progress, method, false, preflight.totalUnits(),
                    protectedBaseline, skippedSkus, quarantineMarker);
            verifyNativeBaseline(progress.database, progress.request.warehouseId(),
                    method, protectedBaseline);

            progress.status = progress.warningCount == 0
                    ? "COMPLETED"
                    : "COMPLETED_WITH_WARNINGS";
            progress.phase = "APPLY_COMPLETED";
            progress.currentArt = null;
            progress.nextArt = null;
            progress.checkpointArt = null;
            publishNative(progress, false, true, null);
            log.info("[folio.accounting-price] native_full_completed job={} warehouse={} preflightChunks={} committedChunks={} calls={}",
                    progress.jobId, progress.request.warehouseId(),
                    progress.preflightChunks, progress.committedChunks,
                    progress.procedureCalls);
        } catch (NativeNegativeDuringApplyException e) {
            progress.status = progress.committedChunks == 0
                    ? "STOPPED_ON_NEGATIVE_STOCK"
                    : "FAILED_PARTIAL";
            progress.phase = "APPLY_STOPPED";
            publishNative(progress, false, false, e.getMessage());
        } catch (Exception e) {
            boolean outcomeUnknown = isNativeOutcomeUnknown(e);
            progress.status = outcomeUnknown
                    ? "OUTCOME_UNKNOWN"
                    : progress.committedChunks == 0 ? "FAILED" : "FAILED_PARTIAL";
            progress.phase = "FAILED";
            log.error("[folio.accounting-price] {} job={} warehouse={} checkpoint={} committed={}: {}",
                    outcomeUnknown ? "native_outcome_unknown" : "native_full_failed",
                    progress.jobId, progress.request.warehouseId(), progress.checkpointArt,
                    progress.committedChunks, safeMessage(e), e);
            publishNative(progress, false, false, safeMessage(e));
        } finally {
            operationRunning.set(false);
        }
    }

    private NativePassResult runNativePass(NativeProgress progress,
                                           AccountingMethod method,
                                           boolean rollbackOnly,
                                           int requiredTotalUnits,
                                           NativeProtectedSnapshot protectedBaseline,
                                           Set<String> skippedSkus,
                                           String quarantineMarker) {
        String cursor = null;
        int passTotalUnits = 0;
        int passProgressUnits = 0;
        int passChunks = 0;
        boolean problemDetected = false;
        Set<String> seenCursors = new HashSet<>();

        while (true) {
            if (passChunks >= nativeFullMaxChunks) {
                throw new IllegalStateException(
                        "Native Folio recalculation exceeded the chunk safety limit "
                                + nativeFullMaxChunks);
            }
            String cursorKey = cursor == null ? "<START>" : cursor;
            if (!seenCursors.add(cursorKey)) {
                throw new IllegalStateException(
                        "Native Folio recalculation repeated cursor " + cursorKey);
            }

            progress.currentArt = cursor;
            progress.checkpointArt = cursor;
            if (!skippedSkus.isEmpty()) {
                progress.phase = "QUARANTINE_PREPARATION";
                publishNative(progress, true, true, null);
            }
            NativeExecutedChunk executed = executeNativeChunk(
                    progress, progress.database, progress.request.warehouseId(), method,
                    cursor, passTotalUnits, passProgressUnits,
                    seenCursors, rollbackOnly, requiredTotalUnits,
                    protectedBaseline, skippedSkus, quarantineMarker);
            NativeFullChunkOutput output = executed.output();
            passChunks++;
            progress.returnCode = output.returnCode();
            progress.currentArt = output.art();
            progress.nextArt = output.newArt();

            passProgressUnits += output.currentUnits();
            if (passTotalUnits == 0 && output.totalUnits() > 0) {
                passTotalUnits = output.totalUnits();
            }
            progress.progressUnits = passProgressUnits;
            progress.totalUnits = passTotalUnits;

            if (output.hasProblem()) {
                problemDetected = true;
                Issue issue = diagnoseNativeProblem(
                        progress.request.warehouseId(), output, cursor);
                addNativeIssue(progress, issue);
                log.warn("[folio.accounting-price] native_negative_stock job={} warehouse={} art={} date={} checkpoint={} newArt={} committedChunks={}",
                        progress.jobId, progress.request.warehouseId(), output.art(),
                        output.problemDate(), cursor, output.newArt(),
                        progress.committedChunks);
                publishNative(progress, true, true, null);
                if (!rollbackOnly) {
                    throw new NativeNegativeDuringApplyException(
                            "Folio detected a recalculation problem for " + output.art()
                                    + " on " + output.problemDate()
                                    + "; the current chunk was rolled back");
                }
            } else if (!rollbackOnly) {
                progress.committedChunks++;
                progress.lastCommittedArt = executed.processedEndArt();
                log.info("[folio.accounting-price] native_chunk_committed job={} warehouse={} inputArt={} outputArt={} processedEndArt={} newArt={} nCur={} nTot={}",
                        progress.jobId, progress.request.warehouseId(), cursor,
                        output.art(), executed.processedEndArt(), output.newArt(), output.currentUnits(),
                        output.totalUnits());
            }

            publishNative(progress, true, true, null);
            if (output.newArt() == null) {
                progress.checkpointArt = null;
                return new NativePassResult(
                        problemDetected, passTotalUnits);
            }
            cursor = output.newArt();
        }
    }

    private NativeExecutedChunk executeNativeChunk(NativeProgress progress,
                                                    String expectedDatabase,
                                                    int warehouseId,
                                                    AccountingMethod method,
                                                    String cursor,
                                                    int totalUnits,
                                                    int cumulativeUnits,
                                                    Set<String> seenCursors,
                                                    boolean rollbackOnly,
                                                    int requiredTotalUnits,
                                                    NativeProtectedSnapshot protectedBaseline,
                                                    Set<String> skippedSkus,
                                                    String quarantineMarker) {
        try {
            return Objects.requireNonNull(nativeWriteTransaction.execute(status -> {
                dao.acquireRecalculationMutex(lockTimeoutMs);
                String transactionDatabase = dao.currentDatabaseName();
                if (!databaseAllowed(transactionDatabase)
                        || expectedDatabase == null
                        || transactionDatabase == null
                        || !expectedDatabase.equalsIgnoreCase(transactionDatabase)) {
                    throw new NativeOutcomeUnknownException(
                            "MSSQL connection database changed from " + expectedDatabase
                                    + " to " + transactionDatabase);
                }
                WarehouseRow before = dao.findWarehouseForUpdate(warehouseId);
                if (before.accountingGroup() != null
                        || !Objects.equals(before.rawAccountingCode(), method.rawCode())) {
                    throw new IllegalStateException(
                            "Folio warehouse accounting scope or method changed while the native job was running");
                }
                Map<String, String> quarantined = Map.of();
                boolean quarantineTypeCreated = false;
                NativeFullChunkOutput output;
                try {
                    if (!skippedSkus.isEmpty()) {
                        dao.createNativeQuarantineType(quarantineMarker);
                        quarantineTypeCreated = true;
                        quarantined = dao.quarantineNativeSkus(
                                warehouseId, skippedSkus, quarantineMarker);
                    }
                    progress.phase = rollbackOnly ? "PRECHECK_RUNNING" : "APPLY_RUNNING";
                    publishNative(progress, true, true, null);
                    output = dao.callNativeFullChunk(
                            null, warehouseId,
                            method.calculationMode(), method.periodMode(), method.includeTax(),
                            cursor, 0, totalUnits, nativeFullTimeoutSeconds);
                    progress.procedureCalls++;
                    if (rollbackOnly) {
                        progress.preflightChunks++;
                    }
                } finally {
                    if (!quarantined.isEmpty()) {
                        dao.restoreNativeSkus(warehouseId, quarantined);
                    }
                    if (quarantineTypeCreated) {
                        dao.deleteNativeQuarantineType(quarantineMarker);
                    }
                }
                WarehouseRow after = dao.findWarehouseForUpdate(warehouseId);

                if (output.transactionCountBefore() != output.transactionCountAfter()) {
                    throw new NativeOutcomeUnknownException(
                            "I_UCHET_TOVAR changed the surrounding transaction boundary");
                }
                if (!sameWarehouseSettings(before, after)) {
                    throw new IllegalStateException(
                            "I_UCHET_TOVAR unexpectedly changed SCLAD_R accounting settings");
                }
                // These checks must run inside the transaction. Returning an
                // invalid legacy OUT contract to the caller would otherwise
                // commit the chunk before Java notices the failure.
                try {
                    validateNativeOutput(
                            warehouseId, output, cursor, totalUnits, requiredTotalUnits,
                            cumulativeUnits, seenCursors);
                } catch (RuntimeException validationError) {
                    progress.failedChunk = chunkDiagnostics(
                            cursor, output, validationError.getMessage());
                    log.error("[folio.accounting-price] native_chunk_rejected job={} warehouse={} inputArt={} outputArt={} nextArt={} returnCode={} nCur={} nTot={} problemDate={} resultRows={} tranBefore={} tranAfter={} reason={}",
                            progress.jobId, warehouseId, cursor, output.art(),
                            output.newArt(), output.returnCode(),
                            output.currentUnits(), output.totalUnits(),
                            output.problemDate(), output.resultRowCount(),
                            output.transactionCountBefore(),
                            output.transactionCountAfter(),
                            validationError.getMessage());
                    throw validationError;
                }
                String processedEndArt = null;
                if (!output.hasProblem() && !rollbackOnly) {
                    processedEndArt = dao.findProcessedRangeEnd(
                            warehouseId, output.newArt());
                    if (processedEndArt == null
                            || (cursor != null && !dao.isArtAtOrAfter(
                            warehouseId, cursor, processedEndArt))) {
                        throw new IllegalStateException(
                                "Cannot determine the protected article range processed by I_UCHET_TOVAR");
                    }
                    NativeProtectedSnapshot protectedAfter =
                            dao.captureNativeProtectedSnapshot(
                                    warehouseId, cursor, processedEndArt);
                    NativeProtectedSnapshot expectedRange = nativeSnapshotRange(
                            protectedBaseline, cursor, processedEndArt);
                    if (!Objects.equals(expectedRange, protectedAfter)) {
                        throw new IllegalStateException(
                                "I_UCHET_TOVAR changed a protected stock or movement invariant");
                    }
                }
                if (output.hasProblem() || rollbackOnly) {
                    status.setRollbackOnly();
                }
                return new NativeExecutedChunk(output, processedEndArt);
            }));
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        }
    }

    private NativeProtectedSnapshot captureNativeBaseline(String expectedDatabase,
                                                           int warehouseId,
                                                           AccountingMethod method) {
        try {
            return Objects.requireNonNull(nativeWriteTransaction.execute(status -> {
                validateNativeTransactionScope(expectedDatabase, warehouseId, method);
                NativeProtectedSnapshot snapshot =
                        dao.captureNativeProtectedSnapshot(warehouseId, null, null);
                status.setRollbackOnly();
                return snapshot;
            }));
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        }
    }

    private void verifyNativeBaseline(String expectedDatabase,
                                      int warehouseId,
                                      AccountingMethod method,
                                      NativeProtectedSnapshot expected) {
        try {
            nativeWriteTransaction.executeWithoutResult(status -> {
                validateNativeTransactionScope(expectedDatabase, warehouseId, method);
                NativeProtectedSnapshot actual =
                        dao.captureNativeProtectedSnapshot(warehouseId, null, null);
                if (!Objects.equals(expected, actual)) {
                    throw new IllegalStateException(
                            "Folio protected source data changed while the native job was running");
                }
                status.setRollbackOnly();
            });
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        }
    }

    private void validateNativeTransactionScope(String expectedDatabase,
                                                int warehouseId,
                                                AccountingMethod method) {
        dao.acquireRecalculationMutex(lockTimeoutMs);
        String transactionDatabase = dao.currentDatabaseName();
        if (!databaseAllowed(transactionDatabase)
                || expectedDatabase == null
                || transactionDatabase == null
                || !expectedDatabase.equalsIgnoreCase(transactionDatabase)) {
            throw new NativeOutcomeUnknownException(
                    "MSSQL connection database changed from " + expectedDatabase
                            + " to " + transactionDatabase);
        }
        WarehouseRow warehouse = dao.findWarehouseForUpdate(warehouseId);
        if (warehouse.accountingGroup() != null
                || !Objects.equals(warehouse.rawAccountingCode(), method.rawCode())) {
            throw new IllegalStateException(
                    "Folio warehouse accounting scope or method changed while the native job was running");
        }
    }

    private static NativeProtectedSnapshot nativeSnapshotRange(
            NativeProtectedSnapshot baseline,
            String startArt,
            String endArt) {
        if (baseline == null) {
            throw new IllegalStateException("Native protected baseline is missing");
        }
        List<String> ordered = baseline.orderedSkus();
        if (ordered.isEmpty()) {
            if (startArt == null && endArt == null) {
                return baseline;
            }
            throw new IllegalStateException(
                    "Native protected range is absent from the rollback baseline");
        }
        int startIndex = startArt == null ? 0 : ordered.indexOf(startArt);
        int endIndex = endArt == null ? ordered.size() - 1 : ordered.indexOf(endArt);
        if (startIndex < 0 || endIndex < startIndex) {
            throw new IllegalStateException(
                    "Native protected range differs from the rollback baseline");
        }
        List<String> rangeSkus = List.copyOf(
                ordered.subList(startIndex, endIndex + 1));
        Map<String, NativeSkuProtectedState> rangeStates = new LinkedHashMap<>();
        for (String sku : rangeSkus) {
            NativeSkuProtectedState state = baseline.states().get(sku);
            if (state == null) {
                throw new IllegalStateException(
                        "Native protected state is missing for " + sku);
            }
            rangeStates.put(sku, state);
        }
        return new NativeProtectedSnapshot(rangeSkus, Map.copyOf(rangeStates));
    }

    private Issue diagnoseNativeProblem(int warehouseId,
                                        NativeFullChunkOutput output,
                                        String checkpointArt) {
        try {
            FolioAccountingPriceRecalculationResponse inspection =
                    Objects.requireNonNull(readTransaction.execute(status ->
                            inspect(output.art(), warehouseId, false, true)));
            Issue chronology = inspection.warnings().stream()
                    .filter(issue -> "NEGATIVE_CHRONOLOGICAL_STOCK".equals(issue.code()))
                    .findFirst()
                    .orElse(null);
            if (chronology != null) {
                Map<String, Object> details = new LinkedHashMap<>(chronology.details());
                details.put("folioProblemDate", output.problemDate());
                details.put("procedureArt", output.art());
                if (checkpointArt != null) {
                    details.put("checkpointArt", checkpointArt);
                }
                if (output.newArt() != null) {
                    details.put("nextArt", output.newArt());
                }
                return new Issue(chronology.code(), chronology.message(), Map.copyOf(details));
            }
        } catch (Exception e) {
            log.warn("[folio.accounting-price] native_problem_diagnostics_failed warehouse={} art={} msg={}",
                    warehouseId, output.art(), safeMessage(e));
        }
        return issue(
                "FOLIO_NATIVE_RECALCULATION_PROBLEM",
                "Folio stopped the native accounting-price recalculation; the chunk was rolled back",
                "warehouseId", warehouseId,
                "procedureArt", output.art(),
                "folioProblemDate", output.problemDate(),
                "checkpointArt", checkpointArt,
                "nextArt", output.newArt()
        );
    }

    private static Issue nativeChronologyIssue(NativeChronologyProblem problem) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("recno", problem.recno());
        operation.put("documentId", problem.documentId());
        if (problem.documentNumber() != null) {
            operation.put("documentNumber", problem.documentNumber());
        }
        operation.put("documentDate", formatDate(problem.documentDate()));
        operation.put("documentType", problem.documentType());
        operation.put("kind", FolioAccountingPriceDao.TYPE_RECEIPT.equals(
                problem.documentType()) ? "RECEIPT" : "EXPENSE");
        operation.put("returnMovement", problem.returnMovement());
        operation.put("quantity", problem.operationQuantity());
        operation.put("warehouseId", problem.warehouseId());

        Map<String, Object> currentState = new LinkedHashMap<>();
        currentState.put("physicalQuantity", problem.physicalQuantity());
        currentState.put("availableQuantity", problem.availableQuantity());
        currentState.put("accountingQuantity", problem.accountingQuantity());
        currentState.put("accountingPrice", problem.accountingPrice());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sku", problem.sku());
        details.put("warehouseId", problem.warehouseId());
        details.put("initialQuantity", problem.initialQuantity());
        details.put("quantityBefore", problem.quantityBefore());
        details.put("operation", Map.copyOf(operation));
        details.put("quantityAfter", problem.quantityAfter());
        details.put("movementPosition", problem.movementPosition());
        details.put("movementCount", problem.movementCount());
        details.put("currentState", Map.copyOf(currentState));
        details.put("skipped", true);
        details.put("source", "JAVA_CHRONOLOGY_PREFLIGHT");
        if ("NEGATIVE_CHRONOLOGICAL_STOCK".equals(problem.code())) {
            details.put("shortageQuantity", problem.quantityAfter().abs());
            return new Issue(
                    problem.code(),
                    "The chronological stock becomes negative; the product will be skipped and other products will continue",
                    Map.copyOf(details));
        }
        if ("AMBIGUOUS_MOVEMENT_ORDER".equals(problem.code())) {
            return new Issue(
                    problem.code(),
                    "Folio has movements with the same legacy sort key; the product will be skipped because their execution order is not deterministic",
                    Map.copyOf(details));
        }
        details.put("denominator", problem.quantityAfter());
        return new Issue(
                problem.code(),
                "The Folio average-price denominator becomes zero; the product will be skipped and other products will continue",
                Map.copyOf(details));
    }

    private void addNativeIssue(NativeProgress progress, Issue issue) {
        progress.warningCount++;
        if (progress.warnings.size() >= maxReportedWarnings) {
            progress.warningsTruncated = true;
            return;
        }
        progress.warnings.add(issue);
    }

    private void validateNativeOutput(int warehouseId,
                                      NativeFullChunkOutput output,
                                      String inputCursor,
                                      int expectedTotalUnits,
                                      int requiredTotalUnits,
                                      int cumulativeUnits,
                                      Set<String> seenCursors) {
        if (output.transactionCountBefore() < 1
                || output.transactionCountAfter() < 1) {
            throw new NativeOutcomeUnknownException(
                    "I_UCHET_TOVAR was not enclosed by the required transaction");
        }
        if (output.returnCode() == null || output.returnCode() != 0) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR returned code " + output.returnCode());
        }
        if (output.currentUnits() == null || output.totalUnits() == null
                || output.currentUnits() < 0 || output.totalUnits() < 0) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR returned invalid progress counters");
        }
        if (output.resultRowCount() != 0) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR returned an unexpected diagnostic rowset");
        }
        if (output.currentUnits() > 0 && output.art() == null) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR returned work without the last processed art");
        }
        int requiredTotal = expectedTotalUnits > 0
                ? expectedTotalUnits
                : requiredTotalUnits;
        if (requiredTotal > 0 && output.totalUnits() != requiredTotal) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR changed total progress from " + requiredTotal
                            + " to " + output.totalUnits());
        }
        int effectiveTotal = requiredTotal > 0
                ? requiredTotal
                : output.totalUnits();
        if (output.currentUnits() > 0 && effectiveTotal == 0) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR returned work without total progress");
        }
        long nextCumulative = (long) cumulativeUnits + output.currentUnits();
        if (effectiveTotal > 0 && nextCumulative > effectiveTotal) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR progress exceeded total work");
        }
        if (output.newArt() != null && output.newArt().equals(inputCursor)) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR did not advance the continuation cursor");
        }
        if (output.newArt() != null) {
            if (output.currentUnits() <= 0
                    || seenCursors.contains(output.newArt())
                    || output.art() == null
                    || (inputCursor != null && !dao.isArtAfter(
                            warehouseId, inputCursor, output.newArt()))) {
                throw new IllegalStateException(
                        "I_UCHET_TOVAR returned an invalid continuation cursor");
            }
        } else if (effectiveTotal > 0 && nextCumulative != effectiveTotal) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR ended before all progress units were processed");
        }
    }

    private static boolean sameWarehouseSettings(WarehouseRow before,
                                                  WarehouseRow after) {
        return before != null && after != null
                && before.warehouseId() == after.warehouseId()
                && Objects.equals(before.rawAccountingCode(), after.rawAccountingCode())
                && Objects.equals(before.accountingGroup(), after.accountingGroup());
    }

    private static void validateNativeScope(WarehouseScope scope,
                                            AccountingMethod method) {
        if (!Objects.equals(scope.requested().rawAccountingCode(), 1000)
                || method.calculationMode() != 0) {
            throw new FolioAccountValidationException(
                    "ACCOUNTING_NATIVE_METHOD_UNSUPPORTED",
                    "Native full recalculation is currently verified only for SCLAD_R.N_2=1000"
            );
        }
        if (scope.requested().accountingGroup() != null
                || scope.affected().size() > 1) {
            throw new FolioAccountValidationException(
                    "ACCOUNTING_NATIVE_GROUP_UNSUPPORTED",
                    "Native full recalculation for a shared accounting group requires a separate golden-master test"
            );
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
                    "Folio accounting-price API is disabled by server configuration"
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

    private static void validateNativeFullRequest(
            FolioAccountingPriceNativeFullRequest request) {
        if (request == null) {
            throw new FolioAccountValidationException(
                    "ACCOUNTING_PRICE_NATIVE_REQUEST_REQUIRED", "Request body is required");
        }
        if (request.previewOnly() == null) {
            throw new FolioAccountValidationException(
                    "PREVIEW_ONLY_REQUIRED", "previewOnly must be explicitly true or false");
        }
        if (request.warehouseId() == null || request.warehouseId() <= 0) {
            throw new FolioAccountValidationException(
                    "WAREHOUSE_ID_INVALID", "warehouseId must be greater than zero");
        }
        if (!request.previewOnly() && !request.isApplyConfirmed()) {
            throw new FolioAccountValidationException(
                    "NATIVE_FULL_CONFIRMATION_REQUIRED",
                    "confirmApply=true is required for a native full recalculation"
            );
        }
    }

    private boolean databaseAllowed(String database) {
        return database != null && nativeFullAllowedDatabases.stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(database));
    }

    private static Set<String> parseDatabaseNames(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> databases = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String database = part.trim();
            if (!database.isEmpty()) {
                databases.add(database);
            }
        }
        return Set.copyOf(databases);
    }

    private static Issue issue(String code, String message, Object... keyValues) {
        return new Issue(code, message, diagnosticMap(keyValues));
    }

    private static Map<String, Object> diagnosticMap(Object... keyValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                details.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return Map.copyOf(details);
    }

    private static String formatDate(LocalDateTime value) {
        return value == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
    }

    private static FolioAccountingPriceFullStatusResponse idleStatus() {
        return new FolioAccountingPriceFullStatusResponse(
                true, false, false, null, "IDLE", null,
                null, null, 0, 0, 0, 0, 0, 0, null,
                0, false, List.of(), null
        );
    }

    private static FolioAccountingPriceNativeFullStatusResponse idleNativeStatus() {
        return new FolioAccountingPriceNativeFullStatusResponse(
                true, false, false, null, "IDLE", "IDLE", null,
                null, null, null, null,
                0, 0, 0, 0, 0, null,
                null, null, null, null, null,
                0, false, List.of(), null, null
        );
    }

    private void publishNative(NativeProgress progress,
                               boolean running,
                               boolean ok,
                               String error) {
        Integer percent = progress.totalUnits <= 0
                ? null
                : Math.min(100, (int) (((long) progress.progressUnits * 100L)
                / progress.totalUnits));
        nativeFullStatus.set(new FolioAccountingPriceNativeFullStatusResponse(
                ok, false, running, progress.jobId, progress.status, progress.phase,
                progress.request, progress.startedAt,
                running ? null : LocalDateTime.now(clock),
                progress.database, progress.accountingMethod,
                progress.procedureCalls, progress.preflightChunks,
                progress.committedChunks, progress.progressUnits,
                progress.totalUnits, percent, progress.currentArt,
                progress.nextArt, progress.lastCommittedArt,
                progress.checkpointArt, progress.returnCode,
                progress.warningCount, progress.warningsTruncated,
                List.copyOf(progress.warnings), progress.failedChunk, error
        ));
    }

    private static FolioAccountingPriceNativeFullStatusResponse withNativeAccepted(
            FolioAccountingPriceNativeFullStatusResponse current,
            boolean accepted) {
        return new FolioAccountingPriceNativeFullStatusResponse(
                current.ok(), accepted, current.running(), current.jobId(),
                current.status(), current.phase(), current.request(),
                current.startedAt(), current.completedAt(), current.database(),
                current.accountingMethod(), current.procedureCalls(),
                current.preflightChunks(), current.committedChunks(),
                current.progressUnits(), current.totalUnits(),
                current.progressPercent(), current.currentArt(), current.nextArt(),
                current.lastCommittedArt(), current.checkpointArt(),
                current.returnCode(), current.warningCount(),
                current.warningsTruncated(), current.warnings(),
                current.failedChunk(), current.error()
        );
    }

    private static ChunkDiagnostics chunkDiagnostics(
            String inputArt,
            NativeFullChunkOutput output,
            String validationError) {
        return new ChunkDiagnostics(
                inputArt,
                output.art(),
                output.newArt(),
                output.returnCode(),
                output.currentUnits(),
                output.totalUnits(),
                output.problemDate(),
                output.resultRowCount(),
                output.transactionCountBefore(),
                output.transactionCountAfter(),
                validationError);
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

    private static boolean isNativeOutcomeUnknown(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof NativeOutcomeUnknownException
                    || current instanceof TransactionSystemException
                    || current instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
        }
        return false;
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

    private static final class NativeProgress {
        private final String jobId;
        private final FolioAccountingPriceNativeFullRequest request;
        private final String database;
        private final LocalDateTime startedAt;
        private final List<Issue> warnings = new ArrayList<>();
        private String status;
        private String phase;
        private AccountingMethod accountingMethod;
        private int procedureCalls;
        private int preflightChunks;
        private int committedChunks;
        private int progressUnits;
        private int totalUnits;
        private String currentArt;
        private String nextArt;
        private String lastCommittedArt;
        private String checkpointArt;
        private Integer returnCode;
        private int warningCount;
        private boolean warningsTruncated;
        private ChunkDiagnostics failedChunk;

        private NativeProgress(String jobId,
                               FolioAccountingPriceNativeFullRequest request,
                               String database,
                               LocalDateTime startedAt) {
            this.jobId = jobId;
            this.request = request;
            this.database = database;
            this.startedAt = startedAt;
        }
    }

    private record NativePassResult(
            boolean problemDetected,
            int totalUnits
    ) {
    }

    private record NativeExecutedChunk(
            NativeFullChunkOutput output,
            String processedEndArt
    ) {
    }

    private static final class NativeNegativeDuringApplyException
            extends RuntimeException {
        private NativeNegativeDuringApplyException(String message) {
            super(message);
        }
    }

    private static final class NativeOutcomeUnknownException
            extends RuntimeException {
        private NativeOutcomeUnknownException(String message) {
            super(message);
        }
    }
}
