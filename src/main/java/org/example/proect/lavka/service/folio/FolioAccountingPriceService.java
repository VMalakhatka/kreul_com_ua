package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.ArticleRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.ArithmeticSessionOptions;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.MovementTotals;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeFullChunkOutput;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeChronologyProblem;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeProtectedSnapshot;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.NativeSkuProtectedState;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseRow;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao.WarehouseScope;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductFingerprint;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final int MAX_DIVIDE_ISOLATION_PROBES_PER_PROBLEM = 64;
    private static final int MAX_DIVIDE_RANGE_SKUS_IN_WARNING = 100;
    private static final MovementTotals EMPTY_MOVEMENT_TOTALS =
            new MovementTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private final FolioAccountingPriceDao dao;
    private final FolioProductVerificationRecorder verificationRecorder;
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
    private volatile int nativeRestartWaitSeconds;
    private volatile int nativeRestartProbeIntervalSeconds = 15;
    private volatile FolioAccountingPriceRuntimeMonitor runtimeMonitor =
            FolioAccountingPriceRuntimeMonitor.noop();

    @Autowired
    public FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            FolioProductVerificationRecorder verificationRecorder,
            @Qualifier("folioAccountingPriceExecutor") TaskExecutor executor,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager transactionManager,
            @Value("${lavka.folio.accounting-prices.api-enabled:true}") boolean apiEnabled,
            @Value("${lavka.folio.accounting-prices.apply-enabled:false}") boolean applyEnabled,
            @Value("${lavka.folio.accounting-prices.full-apply-enabled:false}") boolean fullApplyEnabled,
            @Value("${lavka.folio.accounting-prices.native-full-enabled:true}") boolean nativeFullEnabled,
            @Value("${lavka.folio.accounting-prices.native-full-apply-enabled:false}") boolean nativeFullApplyEnabled,
            @Value("${lavka.folio.accounting-prices.native-full-allowed-databases:Paint_Rus,Paint_Ua}") String nativeFullAllowedDatabases,
            @Value("${lavka.folio.accounting-prices.native-full-max-chunks:100000}") int nativeFullMaxChunks,
            @Value("${lavka.folio.accounting-prices.lock-timeout-ms:5000}") int lockTimeoutMs,
            @Value("${lavka.folio.accounting-prices.query-timeout-seconds:120}") int queryTimeoutSeconds,
            @Value("${lavka.folio.accounting-prices.native-full-timeout-seconds:900}") int nativeFullTimeoutSeconds,
            @Value("${lavka.folio.accounting-prices.max-reported-warnings:200}") int maxReportedWarnings,
            @Value("${lavka.folio.accounting-prices.zone:Europe/Kyiv}") String zone
    ) {
        this(dao, verificationRecorder, executor, Clock.system(ZoneId.of(zone)), transactionManager,
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
        this(dao, FolioProductVerificationRecorder.NOOP, executor, clock, transactionManager,
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
        this(dao, FolioProductVerificationRecorder.NOOP, executor, clock, transactionManager,
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
        this(dao, FolioProductVerificationRecorder.NOOP, executor, clock,
                transactionManager, apiEnabled, applyEnabled, fullApplyEnabled,
                nativeFullEnabled, nativeFullApplyEnabled,
                nativeFullAllowedDatabases, nativeFullMaxChunks, lockTimeoutMs,
                queryTimeoutSeconds, nativeFullTimeoutSeconds, maxReportedWarnings);
    }

    FolioAccountingPriceService(
            FolioAccountingPriceDao dao,
            FolioProductVerificationRecorder verificationRecorder,
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
        this.verificationRecorder = verificationRecorder;
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

    @Autowired
    void setRuntimeMonitor(FolioAccountingPriceRuntimeMonitor runtimeMonitor) {
        this.runtimeMonitor = Objects.requireNonNull(runtimeMonitor);
    }

    @Autowired
    void configureNativeRestartRecovery(
            @Value("${lavka.folio.accounting-prices.native-restart-wait-seconds:600}")
            int waitSeconds,
            @Value("${lavka.folio.accounting-prices.native-restart-probe-interval-seconds:15}")
            int probeIntervalSeconds) {
        this.nativeRestartWaitSeconds = Math.max(0, waitSeconds);
        this.nativeRestartProbeIntervalSeconds = this.nativeRestartWaitSeconds == 0
                ? 0 : Math.max(1, probeIntervalSeconds);
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
                AppliedProduct applied = Objects.requireNonNull(
                        writeTransaction.execute(status ->
                                applyOne(sku, request.warehouseId())));
                FolioAccountingPriceRecalculationResponse response =
                        recordAppliedVerification(applied);
                if (!response.eligibleToApply()) {
                    recordFailedVerificationForCurrentDatabase(
                            request.warehouseId(), sku, firstIssueMessage(response));
                }
                return response;
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
        validateNativeFullRequest(request);
        if (request.isSafeApplyOnly()) {
            throw new FolioAccountValidationException(
                    "NATIVE_FULL_SAFE_APPLY_ONLY_NOT_ALLOWED",
                    "SAFE_APPLY_ONLY is supported only by /recalculate/native-range");
        }
        if (request.hasSelection()) {
            throw new FolioAccountValidationException(
                    "NATIVE_FULL_SELECTION_NOT_ALLOWED",
                    "Use /recalculate/native-range for fromSku/toSku or skus[]");
        }
        return requestNative(request, false);
    }

    public FolioAccountingPriceNativeFullStatusResponse requestNativeRange(
            FolioAccountingPriceNativeFullRequest request) {
        validateNativeFullRequest(request);
        validateNativeSelection(request);
        return requestNative(request, true);
    }

    private FolioAccountingPriceNativeFullStatusResponse requestNative(
            FolioAccountingPriceNativeFullRequest request, boolean selection) {
        requireApiEnabled();
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
        runtimeMonitor.start(jobId, database, request.warehouseId(),
                selection ? "native-range" : "native-full",
                request.previewOnly());
        publishNative(progress, true, true, null);
        try {
            executor.execute(() -> {
                if (selection) {
                    runNativeSelection(progress);
                } else {
                    runNativeFull(progress);
                }
            });
        } catch (RuntimeException e) {
            operationRunning.set(false);
            progress.status = "FAILED";
            progress.phase = "FAILED";
            publishNative(progress, false, false, safeMessage(e));
            runtimeMonitor.finish(progress.jobId, progress.status, safeMessage(e));
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
                0, 0, 0, 0, 0,
                null, null, null, null, null,
                null, null, null, null, null,
                0, false, List.of(), null,
                null, null,
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

    private AppliedProduct applyOne(String sku, int warehouseId) {
        dao.acquireRecalculationMutex(lockTimeoutMs);
        FolioAccountingPriceRecalculationResponse inspection = inspect(sku, warehouseId, true, false);
        if (!inspection.eligibleToApply()) {
            return new AppliedProduct(blockedApply(inspection), Optional.empty());
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
        Optional<ProductFingerprint> fingerprint = verificationRecorder.capture(
                warehouseId, sku, queryTimeoutSeconds);
        boolean changed = pricesChanged(inspection.before(), after);
        log.info("[folio.accounting-price] recalculated sku={} warehouse={} affected={} priceChanged={}",
                sku, warehouseId, scope.affectedWarehouseIds(), changed);
        return new AppliedProduct(new FolioAccountingPriceRecalculationResponse(
                true, false, "RECALCULATED", sku, warehouseId,
                scope.affectedWarehouseIds(), inspection.accountingMethod(),
                true, true, changed, inspection.before(), after,
                inspection.warnings(), List.of()
        ), fingerprint);
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
            String sourceDatabase = dao.currentDatabaseName();
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
                        AppliedProduct applied = Objects.requireNonNull(
                                writeTransaction.execute(status ->
                                        applyOne(sku, request.warehouseId())));
                        result = recordAppliedVerification(applied);
                        if (!result.eligibleToApply()) {
                            recordFailedVerification(
                                    sourceDatabase, request.warehouseId(), sku,
                                    firstIssueMessage(result));
                        }
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
            nativeCheckpoint(progress, "JOB_STARTED", null);
            WarehouseScope scope = requireScope(progress.request.warehouseId());
            AccountingMethod method = method(scope.requested().rawAccountingCode());
            progress.accountingMethod = method;
            validateNativeScope(scope, method);
            if (!dao.safeNativeProceduresInstalled()) {
                throw new IllegalStateException(
                        "Required dbo.LAVKA_I_UCHET_*_SAFE procedures are not installed");
            }
            logNativeArithmeticSessionOptions(progress);

            // The guarded procedure handles one SKU per transaction and
            // returns structured zero-denominator/negative-stock diagnostics.
            // Temporary TIP_TOVR quarantine is therefore no longer needed.
            Set<String> skippedSkus = new LinkedHashSet<>();
            String quarantineMarker = null;

            progress.phase = "PRECHECK_RUNNING";
            progress.status = "RUNNING";
            nativeCheckpoint(progress, "PRECHECK_STARTED", null);
            publishNative(progress, true, true, null);
            NativePassResult preflight = runNativePass(
                    progress, method, true, 0, null,
                    skippedSkus, quarantineMarker);

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
            // exact guarded Folio algorithm one SKU at a time and rolled every
            // transaction back. During apply, clean SKUs commit individually;
            // diagnosed SKUs roll back individually and do not stop the rest.
            nativeCheckpoint(progress, "PROTECTED_BASELINE_CAPTURE", null);
            NativeProtectedSnapshot protectedBaseline = captureNativeBaseline(
                    progress.database, progress.request.warehouseId(), method);
            progress.phase = "APPLY_RUNNING";
            progress.status = "RUNNING";
            progress.progressUnits = 0;
            progress.currentArt = null;
            progress.nextArt = null;
            progress.checkpointArt = null;
            nativeCheckpoint(progress, "APPLY_STARTED", null);
            publishNative(progress, true, true, null);
            runNativePass(progress, method, false, preflight.totalUnits(),
                    protectedBaseline, skippedSkus, quarantineMarker);
            nativeCheckpoint(progress, "PROTECTED_BASELINE_VERIFY", null);
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
            captureNativeFailureMetadata(progress, e);
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
            finishNativeRuntime(progress);
        }
    }

    private void runNativeSelection(NativeProgress progress) {
        List<String> committedForBatchVerification = new ArrayList<>();
        try {
            nativeCheckpoint(progress, "JOB_STARTED", null);
            WarehouseScope scope = requireScope(progress.request.warehouseId());
            AccountingMethod method = method(scope.requested().rawAccountingCode());
            progress.accountingMethod = method;
            validateNativeScope(scope, method);
            if (!dao.safeNativeProceduresInstalled()) {
                throw new IllegalStateException(
                        "Required dbo.LAVKA_I_UCHET_*_SAFE procedures are not installed");
            }
            NativeSelectionResolution selection = resolveNativeSelection(progress.request);
            List<String> selectedSkus = selection.existingSkus();
            if (selection.requestedCount() == 0) {
                throw new FolioAccountValidationException(
                        "NATIVE_RANGE_TOTAL_UNKNOWN",
                        "Java could not determine the selected batch size before apply");
            }
            Set<String> missingSelectedSkus = new LinkedHashSet<>(
                    selection.missingSkus());
            progress.totalUnits = selection.requestedCount();
            progress.processedSku = missingSelectedSkus.size();
            progress.progressUnits = missingSelectedSkus.size();
            addMissingSelectedIssues(
                    progress, selection.missingSkus(), "SELECTION_RESOLVED");
            nativeCheckpoint(progress, "SELECTION_RESOLVED", null);
            logNativeArithmeticSessionOptions(progress);

            boolean safeApplyOnly = !progress.request.previewOnly()
                    && progress.request.isSafeApplyOnly();
            if (!safeApplyOnly) {
                progress.phase = "PRECHECK_RUNNING";
                progress.status = "RUNNING";
                nativeCheckpoint(progress, "PRECHECK_STARTED", null);
                publishNative(progress, true, true, null);
                runNativeSelectionPass(progress, method, selectedSkus, true, null);
            } else {
                log.info("[folio.accounting-price] native_selection_safe_apply_only job={} warehouse={} skuCount={}",
                        progress.jobId, progress.request.warehouseId(), selectedSkus.size());
            }

            if (progress.request.previewOnly()) {
                progress.status = progress.warningCount == 0
                        ? "PREVIEW_READY" : "PREVIEW_READY_WITH_WARNINGS";
                progress.phase = "PRECHECK_COMPLETED";
                clearNativeCursor(progress);
                publishNative(progress, false, true, null);
                return;
            }

            nativeCheckpoint(progress, "PROTECTED_BASELINE_CAPTURE", null);
            NativeProtectedSnapshot protectedBaseline = safeApplyOnly
                    ? captureNativeSelectionBaseline(
                    progress.database, progress.request.warehouseId(), method,
                    selectedSkus)
                    : captureNativeBaseline(
                    progress.database, progress.request.warehouseId(), method);
            List<String> missingAtBaseline = selectedSkus.stream()
                    .filter(sku -> !protectedBaseline.states().containsKey(sku))
                    .toList();
            missingSelectedSkus.addAll(missingAtBaseline);
            addMissingSelectedIssues(
                    progress, missingAtBaseline, "PROTECTED_BASELINE_CAPTURE");
            selectedSkus = selectedSkus.stream()
                    .filter(protectedBaseline.states()::containsKey)
                    .toList();
            Map<String, ProductFingerprint> recoveryBaseline = safeApplyOnly
                    ? captureNativeRecoveryBaseline(
                    progress, method, selectedSkus)
                    : Map.of();
            progress.phase = "APPLY_RUNNING";
            progress.status = "RUNNING";
            progress.progressUnits = missingSelectedSkus.size();
            progress.processedSku = missingSelectedSkus.size();
            clearNativeCursor(progress);
            nativeCheckpoint(progress, "APPLY_STARTED", null);
            publishNative(progress, true, true, null);
            try {
                runNativeSelectionPass(
                        progress, method, selectedSkus, false, protectedBaseline,
                        committedForBatchVerification, recoveryBaseline);
            } catch (RuntimeException applyError) {
                if (safeApplyOnly && !committedForBatchVerification.isEmpty()) {
                    try {
                        finalizeNativeSelectionVerification(
                                progress, method, selectedSkus, protectedBaseline,
                                committedForBatchVerification);
                    } catch (RuntimeException verificationError) {
                        applyError.addSuppressed(verificationError);
                        log.error("[folio.accounting-price] native_selection_partial_verification_failed job={} warehouse={} committed={}: {}",
                                progress.jobId, progress.request.warehouseId(),
                                committedForBatchVerification.size(),
                                safeMessage(verificationError), verificationError);
                    }
                }
                throw applyError;
            }
            nativeCheckpoint(progress, "PROTECTED_BASELINE_VERIFY", null);
            if (safeApplyOnly) {
                finalizeNativeSelectionVerification(
                        progress, method, selectedSkus, protectedBaseline,
                        committedForBatchVerification);
            } else {
                verifyNativeBaseline(progress.database, progress.request.warehouseId(),
                        method, protectedBaseline);
            }

            progress.status = progress.warningCount == 0
                    ? "COMPLETED" : "COMPLETED_WITH_WARNINGS";
            progress.phase = "APPLY_COMPLETED";
            clearNativeCursor(progress);
            publishNative(progress, false, true, null);
        } catch (Exception error) {
            captureNativeFailureMetadata(progress, error);
            boolean outcomeUnknown = isNativeOutcomeUnknown(error);
            progress.status = outcomeUnknown
                    ? "OUTCOME_UNKNOWN"
                    : progress.committedChunks == 0 ? "FAILED" : "FAILED_PARTIAL";
            progress.phase = "FAILED";
            log.error("[folio.accounting-price] native_selection_failed job={} warehouse={} checkpoint={} committed={}: {}",
                    progress.jobId, progress.request.warehouseId(),
                    progress.checkpointArt, progress.committedChunks,
                    safeMessage(error), error);
            publishNative(progress, false, false, safeMessage(error));
        } finally {
            operationRunning.set(false);
            finishNativeRuntime(progress);
        }
    }

    private void runNativeSelectionPass(NativeProgress progress,
                                        AccountingMethod method,
                                        List<String> selectedSkus,
                                        boolean rollbackOnly,
                                        NativeProtectedSnapshot protectedBaseline) {
        runNativeSelectionPass(progress, method, selectedSkus, rollbackOnly,
                protectedBaseline, null, Map.of());
    }

    private void runNativeSelectionPass(NativeProgress progress,
                                        AccountingMethod method,
                                        List<String> selectedSkus,
                                        boolean rollbackOnly,
                                        NativeProtectedSnapshot protectedBaseline,
                                        List<String> committedForBatchVerification,
                                        Map<String, ProductFingerprint> recoveryBaseline) {
        int processed = progress.processedSku == null
                ? 0 : progress.processedSku;
        int canonicalTotal = progress.totalUnits;
        for (String sku : selectedSkus) {
            long skuStartedNanos = System.nanoTime();
            progress.currentArt = sku;
            progress.checkpointArt = sku;
            progress.nextArt = null;
            nativeCheckpoint(progress, "SKU_TRANSACTION_START", sku);
            Set<String> seen = new HashSet<>();
            seen.add(sku);
            NativeExecutedChunk executed = executeNativeChunk(
                    progress, progress.database, progress.request.warehouseId(), method,
                    sku, 0, 0, seen, rollbackOnly, canonicalTotal,
                    protectedBaseline, Set.of(), null, true,
                    recoveryBaseline.get(sku), !rollbackOnly, false);
            nativeCheckpoint(progress, "SKU_TRANSACTION_FINISHED", sku);
            NativeFullChunkOutput output = executed.output();
            progress.returnCode = output.returnCode();
            progress.currentArt = output.art();
            progress.nextArt = output.newArt();
            progress.currentUnits = output.currentUnits();
            progress.procedureCurrentUnits = output.currentUnits();
            progress.procedureTotalUnits = output.totalUnits();
            processed++;
            progress.progressUnits = processed;
            progress.processedSku = processed;
            progress.totalUnits = canonicalTotal;

            if (output.hasProblem()) {
                String problemSku = output.problemArt() == null
                        ? sku : output.problemArt();
                String key = (output.problemCode() == null
                        ? "FOLIO_NATIVE_RECALCULATION_PROBLEM"
                        : output.problemCode()) + '\u0000' + problemSku;
                Issue issue = null;
                if (progress.reportedProblemKeys.add(key)) {
                    issue = diagnoseNativeProblem(
                            progress.request.warehouseId(), output, sku);
                    addNativeIssue(progress, issue);
                }
                if (!rollbackOnly) {
                    recordFailedVerification(
                            progress.database, progress.request.warehouseId(), problemSku,
                            issue == null ? nativeProblemMessage(output)
                                    : issue.code() + ": " + issue.message());
                }
            } else if (!rollbackOnly) {
                progress.committedChunks++;
                progress.lastCommittedArt = sku;
                if (committedForBatchVerification == null) {
                    recordNativeAppliedVerification(progress, executed.fingerprint());
                } else {
                    committedForBatchVerification.add(sku);
                }
            }
            publishNative(progress, true, true, null);
            long durationMs = (System.nanoTime() - skuStartedNanos) / 1_000_000L;
            if (processed % 25 == 0 || durationMs >= 30_000L) {
                log.info("[folio.accounting-price] native_selection_progress job={} warehouse={} pass={} processed={}/{} sku={} durationMs={} calls={} committed={}",
                        progress.jobId, progress.request.warehouseId(),
                        rollbackOnly ? "PRECHECK" : "APPLY", processed,
                        canonicalTotal, sku, durationMs,
                        progress.procedureCalls, progress.committedChunks);
            }
        }
    }

    private NativeSelectionResolution resolveNativeSelection(
            FolioAccountingPriceNativeFullRequest request) {
        List<String> selected;
        List<String> missing = List.of();
        int requestedCount;
        if (request.skus() != null && !request.skus().isEmpty()) {
            Set<String> requested = request.skus().stream()
                    .map(String::trim)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            selected = dao.findSkus(request.warehouseId()).stream()
                    .filter(requested::contains)
                    .toList();
            Set<String> absent = new LinkedHashSet<>(requested);
            absent.removeAll(selected);
            missing = List.copyOf(absent);
            requestedCount = requested.size();
        } else {
            String fromSku = request.fromSku().trim();
            String toSku = request.toSku().trim();
            selected = dao.findSkusInRange(
                    request.warehouseId(), fromSku, toSku);
            if (selected.isEmpty()) {
                throw new FolioAccountingPriceNotFoundException(
                        "ACCOUNTING_PRICE_RANGE_EMPTY",
                        "No Folio products were found in range " + fromSku + ".." + toSku);
            }
            if (selected.size() > 500) {
                throw new FolioAccountValidationException(
                        "NATIVE_SELECTION_TOO_LARGE",
                        "native-range is limited to 500 SKU per request");
            }
            requestedCount = selected.size();
        }
        return new NativeSelectionResolution(
                requestedCount, List.copyOf(selected), missing);
    }

    private void addMissingSelectedIssues(NativeProgress progress,
                                          List<String> missingSkus,
                                          String stage) {
        for (String sku : missingSkus) {
            String key = "SELECTED_PRODUCT_NO_LONGER_EXISTS\u0000" + sku;
            if (!progress.reportedProblemKeys.add(key)) continue;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sku", sku);
            details.put("warehouseId", progress.request.warehouseId());
            details.put("stage", stage);
            details.put("source", "NATIVE_RANGE_SELECTION");
            details.put("skipped", true);
            details.put("procedureExecuted", false);
            details.put("committed", false);
            details.put("recommendation",
                    "Refresh the product snapshot; the missing product should become REMOVED before the next campaign");
            addNativeIssue(progress, new Issue(
                    "SELECTED_PRODUCT_NO_LONGER_EXISTS",
                    "The selected product no longer exists in the Folio warehouse; it was skipped and remaining products will continue",
                    Map.copyOf(details)));
            log.warn("[folio.accounting-price] native_selected_product_missing job={} warehouse={} sku={} stage={} committed=false",
                    progress.jobId, progress.request.warehouseId(), sku, stage);
        }
    }

    private static void clearNativeCursor(NativeProgress progress) {
        progress.currentArt = null;
        progress.nextArt = null;
        progress.checkpointArt = null;
    }

    private void logNativeArithmeticSessionOptions(NativeProgress progress) {
        try {
            ArithmeticSessionOptions options = dao.readArithmeticSessionOptions();
            if (options != null) {
                log.info("[folio.accounting-price] native_session_options job={} database={} optionMask={} ansiWarnings={} arithAbort={} arithIgnore={}",
                        progress.jobId, progress.database, options.optionMask(),
                        options.ansiWarnings(), options.arithAbort(),
                        options.arithIgnore());
            }
        } catch (Exception error) {
            // Session-option logging is diagnostic only and must not prevent a
            // rollback preview. The procedure safety rules remain unchanged.
            log.warn("[folio.accounting-price] native_session_options_unavailable job={} database={} msg={}",
                    progress.jobId, progress.database, safeMessage(error));
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
        String effectiveQuarantineMarker = quarantineMarker;
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
            nativeCheckpoint(progress, "SKU_TRANSACTION_START", cursor);
            if (!skippedSkus.isEmpty()) {
                progress.phase = "QUARANTINE_PREPARATION";
                publishNative(progress, true, true, null);
            }
            NativeExecutedChunk executed;
            try {
                executed = executeNativeChunk(
                        progress, progress.database, progress.request.warehouseId(), method,
                        cursor, passTotalUnits, passProgressUnits,
                        seenCursors, rollbackOnly, requiredTotalUnits,
                        protectedBaseline, skippedSkus, effectiveQuarantineMarker,
                        false);
            } catch (RuntimeException error) {
                // The installed safe procedure must convert every supported
                // zero-denominator branch into return code 20. A raw SQL 8134
                // therefore means an installation mismatch or an unvalidated
                // branch and must remain fail-stop.
                throw error;
            }
            nativeCheckpoint(progress, "SKU_TRANSACTION_FINISHED", cursor);
            NativeFullChunkOutput output = executed.output();
            passChunks++;
            progress.returnCode = output.returnCode();
            progress.currentArt = output.art();
            progress.nextArt = output.newArt();
            progress.currentUnits = output.currentUnits();
            progress.procedureCurrentUnits = output.currentUnits();
            progress.procedureTotalUnits = output.totalUnits();

            passProgressUnits += output.currentUnits();
            if (passTotalUnits == 0 && output.totalUnits() > 0) {
                passTotalUnits = output.totalUnits();
            }
            progress.progressUnits = passProgressUnits;
            progress.totalUnits = passTotalUnits;

            if (output.hasProblem()) {
                problemDetected = true;
                Issue diagnosedIssue = null;
                String problemKey = (output.problemCode() == null
                        ? "NEGATIVE_CHRONOLOGICAL_STOCK" : output.problemCode())
                        + '\u0000'
                        + (output.problemArt() == null ? output.art() : output.problemArt());
                if (progress.reportedProblemKeys.add(problemKey)) {
                    diagnosedIssue = diagnoseNativeProblem(
                            progress.request.warehouseId(), output, cursor);
                    addNativeIssue(progress, diagnosedIssue);
                    log.warn("[folio.accounting-price] native_safe_sku_skipped job={} warehouse={} art={} code={} date={} checkpoint={} newArt={} committedChunks={}",
                            progress.jobId, progress.request.warehouseId(), output.art(),
                            output.problemCode(), output.problemDate(), cursor,
                            output.newArt(), progress.committedChunks);
                }
                if (!rollbackOnly) {
                    String failedSku = output.problemArt() == null
                            ? output.art() : output.problemArt();
                    recordFailedVerification(
                            progress.database, progress.request.warehouseId(), failedSku,
                            diagnosedIssue == null
                                    ? nativeProblemMessage(output)
                                    : diagnosedIssue.code() + ": " + diagnosedIssue.message());
                }
                publishNative(progress, true, true, null);
                // LAVKA_I_UCHET_TOVAR_SAFE processes exactly one SKU. Its
                // transaction has already been rolled back, so both preview
                // and apply can safely continue with output.newArt().
            } else if (!rollbackOnly) {
                progress.committedChunks++;
                progress.lastCommittedArt = executed.processedEndArt();
                recordNativeAppliedVerification(progress, executed.fingerprint());
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

    private NativeDivideIsolation isolateNativeDivideByZero(
            NativeProgress progress,
            AccountingMethod method,
            String failedCheckpoint,
            Set<String> alreadySkipped,
            String quarantineMarker) {
        progress.phase = "DIVIDE_BY_ZERO_ISOLATION";
        publishNative(progress, true, true, null);

        List<String> candidates = dao.findNativeEligibleSkus(
                        progress.request.warehouseId(), failedCheckpoint,
                        nativeFullTimeoutSeconds)
                .stream()
                .filter(sku -> !alreadySkipped.contains(sku))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot isolate the Folio divide-by-zero SKU: no eligible articles remain");
        }

        int probesBeforeIsolation = progress.divideIsolationProbes;
        int firstCandidate = 0;
        int lastCandidate = candidates.size() - 1;

        // The old procedure can carry arithmetic state from one article to
        // the next. Confirm the complete failed range before narrowing it;
        // no SKU range may be quarantined merely by inference.
        requireDivideIsolationProbeBudget(progress, probesBeforeIsolation);
        NativeIsolationProbeResult fullProbe = probeNativeRangeForDivideByZero(
                progress, method,
                candidates.get(firstCandidate), candidates.get(lastCandidate),
                alreadySkipped, quarantineMarker);
        if (fullProbe != NativeIsolationProbeResult.DIVIDE_BY_ZERO) {
            throw new IllegalStateException(
                    "The native divide-by-zero error could not be reproduced for the failed range "
                            + candidates.get(firstCandidate) + ".."
                            + candidates.get(lastCandidate));
        }

        // Find the shortest confirmed prefix. Its left boundary intentionally
        // remains fixed so a dependency between adjacent SKUs is preserved.
        int lowEnd = firstCandidate;
        int highEnd = lastCandidate;
        int confirmedEnd = lastCandidate;
        while (lowEnd < highEnd) {
            requireDivideIsolationProbeBudget(progress, probesBeforeIsolation);
            int middle = lowEnd + (highEnd - lowEnd) / 2;
            NativeIsolationProbeResult probe = probeNativeRangeForDivideByZero(
                    progress, method,
                    candidates.get(firstCandidate), candidates.get(middle),
                    alreadySkipped, quarantineMarker);
            if (probe == NativeIsolationProbeResult.DIVIDE_BY_ZERO) {
                confirmedEnd = middle;
                highEnd = middle;
            } else {
                lowEnd = middle + 1;
            }
        }
        confirmedEnd = lowEnd;

        // Remove as much of the leading side as possible, but keep only ranges
        // that themselves reproduced error 8134 in a rollback probe.
        // The complete prefix [firstCandidate..confirmedEnd] is already a
        // confirmed failing range, so start probing strictly after its known
        // left boundary and avoid executing the same expensive probe twice.
        int lowStart = firstCandidate + 1;
        int highStart = confirmedEnd;
        int confirmedStart = firstCandidate;
        while (lowStart <= highStart) {
            requireDivideIsolationProbeBudget(progress, probesBeforeIsolation);
            int middle = lowStart + (highStart - lowStart) / 2;
            NativeIsolationProbeResult probe = probeNativeRangeForDivideByZero(
                    progress, method,
                    candidates.get(middle), candidates.get(confirmedEnd),
                    alreadySkipped, quarantineMarker);
            if (probe == NativeIsolationProbeResult.DIVIDE_BY_ZERO) {
                confirmedStart = middle;
                lowStart = middle + 1;
            } else {
                highStart = middle - 1;
            }
        }

        List<String> confirmedSkus = List.copyOf(
                candidates.subList(confirmedStart, confirmedEnd + 1));
        return new NativeDivideIsolation(
                confirmedSkus, confirmedSkus.size() == 1);
    }

    private NativeIsolationProbeResult probeNativeRangeForDivideByZero(
            NativeProgress progress,
            AccountingMethod method,
            String firstArt,
            String lastArt,
            Set<String> alreadySkipped,
            String quarantineMarker) {
        progress.divideIsolationProbes++;
        progress.currentArt = firstArt;
        progress.nextArt = lastArt;
        publishNative(progress, true, true, null);

        try {
            return Objects.requireNonNull(nativeWriteTransaction.execute(status -> {
                validateNativeTransactionScope(
                        progress.database, progress.request.warehouseId(), method);
                dao.createNativeQuarantineType(quarantineMarker);
                dao.quarantineNativeOutsideRange(
                        progress.request.warehouseId(), firstArt, lastArt,
                        Set.copyOf(alreadySkipped), quarantineMarker);

                String cursor = null;
                int totalUnits = 0;
                int cumulativeUnits = 0;
                int rangeChunks = 0;
                Set<String> seenCursors = new HashSet<>();
                while (true) {
                    if (++rangeChunks > nativeFullMaxChunks) {
                        throw new IllegalStateException(
                                "Native divide-by-zero range probe exceeded the chunk safety limit "
                                        + nativeFullMaxChunks);
                    }
                    if (!seenCursors.add(cursor == null ? "<START>" : cursor)) {
                        throw new IllegalStateException(
                                "Native isolation repeated cursor " + cursor);
                    }
                    progress.procedureCalls++;
                    progress.preflightChunks++;
                    nativeCheckpoint(progress, "DIVIDE_PROBE_PROCEDURE_CALL",
                            firstArt + ".." + lastArt);
                    NativeFullChunkOutput output = dao.callNativeFullChunk(
                            null, progress.request.warehouseId(),
                            method.calculationMode(), method.periodMode(),
                            method.includeTax(), cursor, 0, totalUnits,
                            nativeFullTimeoutSeconds);
                    nativeCheckpoint(progress, "DIVIDE_PROBE_PROCEDURE_RETURNED",
                            firstArt + ".." + lastArt);
                    if (output.transactionCountBefore()
                            != output.transactionCountAfter()) {
                        throw new NativeOutcomeUnknownException(
                                "I_UCHET_TOVAR changed the surrounding transaction boundary during divide-by-zero isolation");
                    }
                    WarehouseRow after = dao.findWarehouseForUpdate(
                            progress.request.warehouseId());
                    if (after.accountingGroup() != null
                            || !Objects.equals(after.rawAccountingCode(), method.rawCode())) {
                        throw new IllegalStateException(
                                "Folio warehouse accounting settings changed during divide-by-zero isolation");
                    }
                    validateNativeOutput(
                            progress.request.warehouseId(), output, cursor,
                            totalUnits, 0, cumulativeUnits, seenCursors,
                            false, true);
                    if (output.hasProblem()) {
                        throw new IllegalStateException(
                                "Native isolation found an unexpected Folio problem for "
                                        + output.art() + " on " + output.problemDate());
                    }
                    cumulativeUnits += output.currentUnits();
                    if (totalUnits == 0 && output.totalUnits() > 0) {
                        totalUnits = output.totalUnits();
                    }
                    if (output.newArt() == null) {
                        status.setRollbackOnly();
                        return NativeIsolationProbeResult.CLEAN;
                    }
                    cursor = output.newArt();
                }
            }));
        } catch (RuntimeException error) {
            if (isDivideByZero(error)) {
                return NativeIsolationProbeResult.DIVIDE_BY_ZERO;
            }
            throw error;
        }
    }

    private static void requireDivideIsolationProbeBudget(
            NativeProgress progress,
            int probesBeforeIsolation) {
        if (progress.divideIsolationProbes - probesBeforeIsolation
                >= MAX_DIVIDE_ISOLATION_PROBES_PER_PROBLEM) {
            throw new IllegalStateException(
                    "Native divide-by-zero isolation exceeded the safety limit "
                            + MAX_DIVIDE_ISOLATION_PROBES_PER_PROBLEM
                            + " for one problem range");
        }
    }

    private Issue nativeDivideByZeroIssue(int warehouseId,
                                          NativeDivideIsolation isolation,
                                          String checkpointArt,
                                          int isolationProbes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sku", isolation.firstSku());
        details.put("warehouseId", warehouseId);
        if (checkpointArt != null) {
            details.put("checkpointArt", checkpointArt);
        }
        details.put("skipped", true);
        details.put("source", isolation.exactSkuConfirmed()
                ? "FOLIO_SINGLE_SKU_ROLLBACK_PROBE"
                : "FOLIO_MULTI_SKU_ROLLBACK_PROBE");
        details.put("exactSkuConfirmed", isolation.exactSkuConfirmed());
        details.put("rangeConfirmed", true);
        details.put("firstSku", isolation.firstSku());
        details.put("lastSku", isolation.lastSku());
        details.put("skuCount", isolation.skus().size());
        int reportedSkuCount = Math.min(
                isolation.skus().size(), MAX_DIVIDE_RANGE_SKUS_IN_WARNING);
        details.put("skus", List.copyOf(
                isolation.skus().subList(0, reportedSkuCount)));
        details.put("skusTruncated", reportedSkuCount < isolation.skus().size());
        details.put("isolationProbes", isolationProbes);
        if (isolation.exactSkuConfirmed()) {
            String sku = isolation.firstSku();
            try {
                FolioAccountingPriceRecalculationResponse inspection =
                        Objects.requireNonNull(readTransaction.execute(status ->
                                inspect(sku, warehouseId, false, true)));
                if (!inspection.before().isEmpty()) {
                    PriceState state = inspection.before().get(0);
                    Map<String, Object> currentState = new LinkedHashMap<>();
                    putIfNotNull(currentState, "initialQuantity", state.initialQuantity());
                    putIfNotNull(currentState, "physicalQuantity", state.physicalQuantity());
                    putIfNotNull(currentState, "availableQuantity", state.availableQuantity());
                    putIfNotNull(currentState, "accountingQuantity", state.accountingQuantity());
                    putIfNotNull(currentState, "accountingAmount", state.accountingAmount());
                    putIfNotNull(currentState, "accountingPrice", state.accountingPrice());
                    currentState.put("movementCount", state.accountedMovementCount());
                    details.put("currentState", Map.copyOf(currentState));
                }
            } catch (Exception diagnosticError) {
                details.put("diagnosticError", safeMessage(diagnosticError));
            }
        }
        return new Issue(
                "ACCOUNTING_PRICE_DIVIDE_BY_ZERO",
                isolation.exactSkuConfirmed()
                        ? "Folio divides by zero while recalculating this exact SKU; the SKU will be skipped and other products will continue"
                        : "Folio divides by zero only while recalculating this confirmed contiguous SKU range; the range will be skipped and other products will continue",
                Map.copyOf(details));
    }

    private static boolean isDivideByZero(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 8134
                    || "22012".equals(sqlException.getSQLState()))) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("divide by zero")
                        || normalized.contains("division by zero")
                        || normalized.contains("делени")
                        && normalized.contains("нол")) {
                    return true;
                }
            }
        }
        return false;
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
                                                    String quarantineMarker,
                                                    boolean partialSelection) {
        return executeNativeChunk(
                progress, expectedDatabase, warehouseId, method, cursor,
                totalUnits, cumulativeUnits, seenCursors, rollbackOnly,
                requiredTotalUnits, protectedBaseline, skippedSkus,
                quarantineMarker, partialSelection, null, false, false);
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
                                                    String quarantineMarker,
                                                    boolean partialSelection,
                                                    ProductFingerprint recoveryBaseline,
                                                    boolean allowRestartRecovery,
                                                    boolean captureRollbackFingerprint) {
        AtomicReference<NativeExecutedChunk> preparedChunk = new AtomicReference<>();
        try {
            nativeCheckpoint(progress, "MSSQL_TRANSACTION_ACQUIRE", cursor);
            NativeExecutedChunk executed = Objects.requireNonNull(
                    nativeWriteTransaction.execute(status -> {
                nativeCheckpoint(progress, "MUTEX_ACQUIRE", cursor);
                dao.acquireRecalculationMutex(lockTimeoutMs);
                nativeCheckpoint(progress, "SCOPE_VALIDATE", cursor);
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
                boolean procedureCompleted = false;
                NativeFullChunkOutput output;
                try {
                    if (!skippedSkus.isEmpty()) {
                        nativeCheckpoint(progress, "QUARANTINE_PREPARE", cursor);
                        dao.createNativeQuarantineType(quarantineMarker);
                        quarantineTypeCreated = true;
                        quarantined = dao.quarantineNativeSkus(
                                warehouseId, skippedSkus, quarantineMarker);
                    }
                    progress.phase = rollbackOnly ? "PRECHECK_RUNNING" : "APPLY_RUNNING";
                    publishNative(progress, true, true, null);
                    progress.procedureCalls++;
                    if (rollbackOnly) {
                        progress.preflightChunks++;
                    }
                    nativeCheckpoint(progress, "FOLIO_PROCEDURE_CALL", cursor);
                    output = dao.callNativeFullChunk(
                            null, warehouseId,
                            method.calculationMode(), method.periodMode(), method.includeTax(),
                            cursor, 0, totalUnits, nativeFullTimeoutSeconds);
                    procedureCompleted = true;
                    nativeCheckpoint(progress, "FOLIO_PROCEDURE_RETURNED", cursor);
                } finally {
                    // A SQL arithmetic error can leave the legacy connection
                    // unable to execute reliable compensating statements.
                    // Preserve the original exception and let Spring roll the
                    // whole transaction back. Explicit restoration is needed
                    // only after the procedure returned successfully and the
                    // transaction may still be committed.
                    if (procedureCompleted) {
                        if (!quarantined.isEmpty()) {
                            dao.restoreNativeSkus(warehouseId, quarantined);
                        }
                        if (quarantineTypeCreated) {
                            dao.deleteNativeQuarantineType(quarantineMarker);
                        }
                        nativeCheckpoint(progress, "QUARANTINE_RESTORED", cursor);
                    }
                }
                nativeCheckpoint(progress, "WAREHOUSE_POSTCHECK", cursor);
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
                    nativeCheckpoint(progress, "OUTPUT_VALIDATE", cursor);
                    validateNativeOutput(
                            warehouseId, output, cursor, totalUnits, requiredTotalUnits,
                            cumulativeUnits, seenCursors, partialSelection,
                            !partialSelection);
                    if (partialSelection
                            && (cursor == null || !cursor.equals(output.art()))) {
                        throw new IllegalStateException(
                                "LAVKA_I_UCHET_TOVAR_SAFE processed a different SKU than requested");
                    }
                } catch (RuntimeException validationError) {
                    if (partialSelection) {
                        progress.errorCode = "NATIVE_RANGE_CONTRACT_INVALID";
                    }
                    progress.failedChunk = chunkDiagnostics(
                            cursor, output, requiredTotalUnits, partialSelection,
                            validationError.getMessage());
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
                Optional<ProductFingerprint> fingerprint = Optional.empty();
                if (!output.hasProblem()
                        && (!rollbackOnly || captureRollbackFingerprint)) {
                    // native-range calls the safe procedure with an explicit
                    // SKU cursor.  That procedure is deliberately guarded to
                    // process exactly that SKU, while newArt is only the
                    // continuation cursor in the legacy article order.  Do
                    // not infer the protected end from the global predecessor
                    // of newArt: CP1251/collation/trailing-space differences
                    // can make that query return a different article and
                    // falsely report that the selected SKU escaped its range.
                    processedEndArt = partialSelection
                            ? (cursor == null ? null : cursor.trim())
                            : dao.findProcessedRangeEnd(
                                    warehouseId, output.newArt());
                    if (processedEndArt == null
                            || (cursor != null && !dao.isArtAtOrAfter(
                            warehouseId, cursor, processedEndArt))) {
                        throw new IllegalStateException(
                                "Cannot determine the protected article range processed by I_UCHET_TOVAR");
                    }
                    if (partialSelection && !cursor.equals(processedEndArt)) {
                        throw new IllegalStateException(
                                "LAVKA_I_UCHET_TOVAR_SAFE changed more than the selected SKU");
                    }
                    nativeCheckpoint(progress, "PROTECTED_POSTCHECK", cursor);
                    NativeProtectedSnapshot protectedAfter =
                            dao.captureNativeProtectedSnapshot(
                                    warehouseId, cursor, processedEndArt);
                    NativeProtectedSnapshot expectedRange = nativeSnapshotRange(
                            protectedBaseline, cursor, processedEndArt);
                    if (!Objects.equals(expectedRange, protectedAfter)) {
                        throw new IllegalStateException(
                                "I_UCHET_TOVAR changed a protected stock or movement invariant");
                    }
                    nativeCheckpoint(progress, "FINGERPRINT_CAPTURE", cursor);
                    if (captureRollbackFingerprint
                            || !(partialSelection
                            && progress.request.isSafeApplyOnly())) {
                        fingerprint = verificationRecorder.capture(
                                warehouseId, processedEndArt, nativeFullTimeoutSeconds);
                    }
                }
                if (output.hasProblem() || rollbackOnly) {
                    status.setRollbackOnly();
                }
                nativeCheckpoint(progress, "TRANSACTION_COMPLETION", cursor);
                NativeExecutedChunk prepared =
                        new NativeExecutedChunk(output, processedEndArt, fingerprint);
                preparedChunk.set(prepared);
                return prepared;
            }));
            nativeCheckpoint(progress, "TRANSACTION_FINISHED", cursor);
            return executed;
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        } catch (RuntimeException error) {
            if (allowRestartRecovery && partialSelection && !rollbackOnly
                    && isNativeOutcomeUnknown(error)) {
                return recoverNativeSelectionAfterRestart(
                        progress, expectedDatabase, warehouseId, method, cursor,
                        totalUnits, cumulativeUnits, seenCursors,
                        requiredTotalUnits, protectedBaseline, skippedSkus,
                        quarantineMarker, recoveryBaseline, preparedChunk.get(), error);
            }
            throw error;
        }
    }

    private NativeExecutedChunk recoverNativeSelectionAfterRestart(
            NativeProgress progress,
            String expectedDatabase,
            int warehouseId,
            AccountingMethod method,
            String sku,
            int totalUnits,
            int cumulativeUnits,
            Set<String> seenCursors,
            int requiredTotalUnits,
            NativeProtectedSnapshot protectedBaseline,
            Set<String> skippedSkus,
            String quarantineMarker,
            ProductFingerprint recoveryBaseline,
            NativeExecutedChunk preparedChunk,
            RuntimeException originalError) {
        if (nativeRestartWaitSeconds <= 0) {
            throw originalError;
        }

        int waitedSeconds = awaitNativeDatabaseRestart(
                progress, expectedDatabase, sku, originalError);
        progress.phase = "APPLY_RECOVERY";
        progress.recommendation = null;
        publishNative(progress, true, true, null);

        // The transaction callback did not return, therefore Spring never
        // started COMMIT. SQL Server recovery rolls that open transaction
        // back, so replaying this exact SKU once is safe.
        if (preparedChunk == null) {
            NativeExecutedChunk retried = executeNativeChunk(
                    progress, expectedDatabase, warehouseId, method, sku,
                    totalUnits, cumulativeUnits, freshSeenCursors(sku), false,
                    requiredTotalUnits, protectedBaseline, skippedSkus,
                    quarantineMarker, true, recoveryBaseline, false, false);
            addNativeRestartIssue(
                    progress, "MSSQL_RESTART_TRANSACTION_ROLLED_BACK_AND_RETRIED",
                    sku, waitedSeconds, false, true, originalError);
            return retried;
        }

        ProductFingerprint current = verificationRecorder.capture(
                        warehouseId, sku, nativeFullTimeoutSeconds)
                .orElseThrow(() -> nativeRecoveryUnknown(
                        progress, sku,
                        "The current SKU fingerprint is unavailable after the MSSQL restart",
                        originalError));

        // Exact equality with the batch baseline proves that the interrupted
        // transaction did not publish an observable accounting result. A
        // single replay is idempotent and remains isolated to this SKU.
        if (sameFingerprintState(recoveryBaseline, current)) {
            NativeExecutedChunk retried = executeNativeChunk(
                    progress, expectedDatabase, warehouseId, method, sku,
                    totalUnits, cumulativeUnits, freshSeenCursors(sku), false,
                    requiredTotalUnits, protectedBaseline, skippedSkus,
                    quarantineMarker, true, recoveryBaseline, false, false);
            addNativeRestartIssue(
                    progress, "MSSQL_RESTART_COMMIT_ROLLED_BACK_AND_RETRIED",
                    sku, waitedSeconds, false, true, originalError);
            return retried;
        }

        // A different fingerprint may mean that COMMIT succeeded but its ACK
        // was lost. Re-run the deterministic safe procedure under ROLLBACK
        // and compare its expected fingerprint with the independently read
        // current state. Only an exact match proves the committed result.
        NativeExecutedChunk rollbackProbe = executeNativeChunk(
                progress, expectedDatabase, warehouseId, method, sku,
                totalUnits, cumulativeUnits, freshSeenCursors(sku), true,
                requiredTotalUnits, protectedBaseline, skippedSkus,
                quarantineMarker, true, recoveryBaseline, false, true);
        progress.phase = "APPLY_RECOVERY";
        publishNative(progress, true, true, null);
        if (rollbackProbe.output().hasProblem()) {
            throw nativeRecoveryUnknown(
                    progress, sku,
                    "The rollback recovery probe returned a Folio problem after the MSSQL restart",
                    originalError);
        }
        ProductFingerprint expected = rollbackProbe.fingerprint()
                .orElseThrow(() -> nativeRecoveryUnknown(
                        progress, sku,
                        "The rollback recovery probe did not return an expected SKU fingerprint",
                        originalError));
        if (!sameFingerprintState(current, expected)) {
            throw nativeRecoveryUnknown(
                    progress, sku,
                    "The SKU state after restart matches neither the pre-commit baseline nor the deterministic recalculated result",
                    originalError);
        }

        addNativeRestartIssue(
                progress, "MSSQL_RESTART_COMMIT_CONFIRMED",
                sku, waitedSeconds, true, false, originalError);
        return new NativeExecutedChunk(
                preparedChunk.output(), preparedChunk.processedEndArt(),
                Optional.of(current));
    }

    private int awaitNativeDatabaseRestart(NativeProgress progress,
                                           String expectedDatabase,
                                           String sku,
                                           RuntimeException originalError) {
        long startedNanos = System.nanoTime();
        long maxWaitNanos = nativeRestartWaitSeconds * 1_000_000_000L;
        long deadline = startedNanos + maxWaitNanos;
        int probe = 0;
        RuntimeException lastProbeError = originalError;
        progress.phase = "WAITING_FOR_FOLIO_RESTART";
        progress.recommendation = "Java is waiting for the planned Folio MSSQL restart to finish; do not start another recalculation";
        publishNative(progress, true, true, null);
        nativeCheckpoint(progress, "MSSQL_RESTART_WAIT_STARTED", sku);
        log.warn("[folio.accounting-price] native_mssql_restart_wait job={} warehouse={} sku={} maxWaitSeconds={} originalError={}",
                progress.jobId, progress.request.warehouseId(), sku,
                nativeRestartWaitSeconds, safeMessage(originalError));

        while (true) {
            probe++;
            nativeCheckpoint(progress, "MSSQL_RESTART_WAIT_PROBE", sku);
            try {
                String actualDatabase = dao.currentDatabaseName();
                if (actualDatabase != null
                        && expectedDatabase.equalsIgnoreCase(actualDatabase)) {
                    int waitedSeconds = (int) Math.max(0L,
                            (System.nanoTime() - startedNanos) / 1_000_000_000L);
                    nativeCheckpoint(progress, "MSSQL_RESTART_RECOVERED", sku);
                    log.info("[folio.accounting-price] native_mssql_restart_recovered job={} warehouse={} sku={} waitedSeconds={} probes={}",
                            progress.jobId, progress.request.warehouseId(), sku,
                            waitedSeconds, probe);
                    return waitedSeconds;
                }
                if (actualDatabase != null) {
                    throw nativeRecoveryUnknown(
                            progress, sku,
                            "MSSQL reconnected to unexpected database " + actualDatabase,
                            originalError);
                }
            } catch (NativeOutcomeUnknownException error) {
                throw error;
            } catch (RuntimeException probeError) {
                lastProbeError = probeError;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                progress.errorCode = "FOLIO_MSSQL_RESTART_TIMEOUT";
                progress.recommendation = "Check Folio MSSQL availability, then inspect the current SKU before any manual retry";
                throw new NativeOutcomeUnknownException(
                        "Folio MSSQL did not recover within "
                                + nativeRestartWaitSeconds + " seconds; last probe: "
                                + safeMessage(lastProbeError), lastProbeError);
            }
            long intervalNanos = nativeRestartProbeIntervalSeconds <= 0
                    ? 0L
                    : nativeRestartProbeIntervalSeconds * 1_000_000_000L;
            long sleepNanos = Math.min(remainingNanos, intervalNanos);
            if (sleepNanos <= 0L) {
                continue;
            }
            try {
                Thread.sleep(
                        sleepNanos / 1_000_000L,
                        (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                progress.errorCode = "FOLIO_MSSQL_RESTART_WAIT_INTERRUPTED";
                progress.recommendation = "The recovery wait was interrupted; inspect the current SKU before retrying";
                throw new NativeOutcomeUnknownException(
                        "Interrupted while waiting for Folio MSSQL restart",
                        interrupted);
            }
        }
    }

    private NativeOutcomeUnknownException nativeRecoveryUnknown(
            NativeProgress progress,
            String sku,
            String message,
            Throwable cause) {
        progress.errorCode = "NATIVE_COMMIT_OUTCOME_UNPROVEN";
        progress.recommendation = "Do not retry SKU " + sku
                + " automatically; compare its Folio accounting state and refresh the product snapshot";
        return new NativeOutcomeUnknownException(message, cause);
    }

    private void addNativeRestartIssue(NativeProgress progress,
                                       String code,
                                       String sku,
                                       int waitedSeconds,
                                       boolean committed,
                                       boolean retried,
                                       Throwable originalError) {
        addNativeIssue(progress, issue(
                code,
                committed
                        ? "Folio MSSQL restarted; Java independently confirmed that the SKU commit succeeded"
                        : "Folio MSSQL restarted; Java proved rollback and safely recalculated the SKU once",
                "sku", sku,
                "warehouseId", progress.request.warehouseId(),
                "waitedSeconds", waitedSeconds,
                "committed", committed,
                "retried", retried,
                "originalError", safeMessage(originalError)));
    }

    private static Set<String> freshSeenCursors(String sku) {
        Set<String> result = new HashSet<>();
        result.add(sku);
        return result;
    }

    private static boolean sameFingerprintState(ProductFingerprint expected,
                                                ProductFingerprint actual) {
        return expected != null && actual != null
                && expected.warehouseId() == actual.warehouseId()
                && Objects.equals(expected.sourceDatabase(), actual.sourceDatabase())
                && Objects.equals(expected.sku(), actual.sku())
                && Objects.equals(expected.sourceDigest(), actual.sourceDigest());
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

    private NativeProtectedSnapshot captureNativeSelectionBaseline(
            String expectedDatabase,
            int warehouseId,
            AccountingMethod method,
            List<String> selectedSkus) {
        long startedNanos = System.nanoTime();
        try {
            NativeProtectedSnapshot snapshot = Objects.requireNonNull(
                    nativeWriteTransaction.execute(status -> {
                        validateNativeTransactionScope(
                                expectedDatabase, warehouseId, method);
                        NativeProtectedSnapshot selected =
                                dao.captureNativeProtectedSnapshot(
                                        warehouseId, selectedSkus);
                        status.setRollbackOnly();
                        return selected;
                    }));
            log.info("[folio.accounting-price] native_selected_baseline_captured warehouse={} skuCount={} durationMs={}",
                    warehouseId, selectedSkus.size(),
                    (System.nanoTime() - startedNanos) / 1_000_000L);
            return snapshot;
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        }
    }

    private Map<String, ProductFingerprint> captureNativeRecoveryBaseline(
            NativeProgress progress,
            AccountingMethod method,
            List<String> selectedSkus) {
        if (nativeRestartWaitSeconds <= 0 || selectedSkus.isEmpty()) {
            return Map.of();
        }
        long startedNanos = System.nanoTime();
        try {
            List<ProductFingerprint> captured = Objects.requireNonNullElse(
                    nativeWriteTransaction.execute(status -> {
                        validateNativeTransactionScope(
                                progress.database, progress.request.warehouseId(), method);
                        List<ProductFingerprint> fingerprints =
                                verificationRecorder.captureBatch(
                                        progress.request.warehouseId(), selectedSkus,
                                        nativeFullTimeoutSeconds);
                        status.setRollbackOnly();
                        return fingerprints;
                    }), List.of());
            Map<String, ProductFingerprint> bySku = new LinkedHashMap<>();
            for (ProductFingerprint fingerprint : captured) {
                bySku.put(fingerprint.sku(), fingerprint);
            }
            log.info("[folio.accounting-price] native_restart_recovery_baseline_captured job={} warehouse={} requestedSkuCount={} fingerprintCount={} durationMs={}",
                    progress.jobId, progress.request.warehouseId(),
                    selectedSkus.size(), bySku.size(),
                    (System.nanoTime() - startedNanos) / 1_000_000L);
            return Map.copyOf(bySku);
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        }
    }

    private void finalizeNativeSelectionVerification(
            NativeProgress progress,
            AccountingMethod method,
            List<String> selectedSkus,
            NativeProtectedSnapshot expected,
            List<String> committedSkus) {
        long startedNanos = System.nanoTime();
        NativeSelectionVerification verification;
        try {
            verification = Objects.requireNonNull(
                    nativeWriteTransaction.execute(status -> {
                        validateNativeTransactionScope(
                                progress.database, progress.request.warehouseId(), method);
                        NativeProtectedSnapshot actual =
                                dao.captureNativeProtectedSnapshot(
                                        progress.request.warehouseId(), selectedSkus);
                        List<NativeSelectedProductChange> concurrentChanges =
                                nativeSelectionChanges(expected, actual, committedSkus);
                        Set<String> changedSkus = concurrentChanges.stream()
                                .map(NativeSelectedProductChange::sku)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                        List<String> verifiableSkus = committedSkus.stream()
                                .filter(sku -> !changedSkus.contains(sku))
                                .toList();
                        nativeCheckpoint(progress, "BATCH_FINGERPRINT_CAPTURE", null);
                        List<ProductFingerprint> captured = verifiableSkus.isEmpty()
                                ? List.of()
                                : verificationRecorder.captureBatch(
                                progress.request.warehouseId(),
                                verifiableSkus, nativeFullTimeoutSeconds);
                        status.setRollbackOnly();
                        return new NativeSelectionVerification(
                                List.copyOf(captured), concurrentChanges);
                    }));
        } catch (CannotAcquireLockException e) {
            throw new FolioAccountingPriceBusyException(e);
        }
        for (NativeSelectedProductChange change : verification.concurrentChanges()) {
            addNativeConcurrentChangeIssue(progress, change);
        }
        recordNativeAppliedVerificationsBatch(progress, verification.fingerprints());
        log.info("[folio.accounting-price] native_selected_baseline_verified job={} warehouse={} selectedSkuCount={} committedSkuCount={} fingerprintCount={} concurrentChangeCount={} durationMs={}",
                progress.jobId, progress.request.warehouseId(), selectedSkus.size(),
                committedSkus.size(), verification.fingerprints().size(),
                verification.concurrentChanges().size(),
                (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static List<NativeSelectedProductChange> nativeSelectionChanges(
            NativeProtectedSnapshot expected,
            NativeProtectedSnapshot actual,
            List<String> committedSkus) {
        Set<String> committed = Set.copyOf(committedSkus);
        List<NativeSelectedProductChange> changes = new ArrayList<>();
        for (String sku : expected.orderedSkus()) {
            NativeSkuProtectedState before = expected.states().get(sku);
            NativeSkuProtectedState after = actual.states().get(sku);
            if (!Objects.equals(before, after)) {
                changes.add(new NativeSelectedProductChange(
                        sku, before, after, committed.contains(sku)));
            }
        }
        return List.copyOf(changes);
    }

    private void addNativeConcurrentChangeIssue(
            NativeProgress progress,
            NativeSelectedProductChange change) {
        String key = "SELECTED_PRODUCT_CHANGED_DURING_JOB\u0000" + change.sku();
        if (!progress.reportedProblemKeys.add(key)) return;
        NativeSkuProtectedState before = change.before();
        NativeSkuProtectedState after = change.after();
        boolean productRemoved = after == null;
        boolean articleChanged = !productRemoved
                && !Objects.equals(before.article(), after.article());
        boolean movementsChanged = !productRemoved
                && !Objects.equals(before.movements(), after.movements());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sku", change.sku());
        details.put("warehouseId", progress.request.warehouseId());
        details.put("stage", "PROTECTED_BASELINE_VERIFY");
        details.put("source", "CONCURRENT_FOLIO_CHANGE");
        details.put("changeKind", productRemoved
                ? "PRODUCT_REMOVED"
                : articleChanged && movementsChanged
                ? "ARTICLE_AND_MOVEMENTS_CHANGED"
                : articleChanged ? "ARTICLE_CHANGED" : "MOVEMENTS_CHANGED");
        details.put("articleChanged", articleChanged);
        details.put("movementsChanged", movementsChanged);
        details.put("productExistsAfter", !productRemoved);
        details.put("expectedMovementRows",
                before == null || before.movements() == null
                        ? 0 : before.movements().rowCount());
        details.put("actualMovementRows",
                after == null || after.movements() == null
                        ? 0 : after.movements().rowCount());
        details.put("recalculationCommitted", change.committed());
        details.put("verificationRecorded", false);
        details.put("retryRequiredAfterSnapshot", change.committed());
        details.put("remainingProductsContinue", true);
        details.put("recommendation",
                "Build the final product snapshot and retry only this SKU if it remains DIRTY; do not repeat already VERIFIED products");
        addNativeIssue(progress, new Issue(
                "SELECTED_PRODUCT_CHANGED_DURING_JOB",
                "The product changed concurrently in Folio while native-range was running; its verification was deferred and remaining products will continue",
                Map.copyOf(details)));
        log.warn("[folio.accounting-price] native_selected_product_changed job={} warehouse={} sku={} committed={} kind={} expectedMovementRows={} actualMovementRows={}",
                progress.jobId, progress.request.warehouseId(), change.sku(),
                change.committed(), details.get("changeKind"),
                details.get("expectedMovementRows"), details.get("actualMovementRows"));
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
        if (output.problemCode() != null && !output.problemCode().isBlank()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sku", output.problemArt() == null
                    ? output.art() : output.problemArt());
            details.put("warehouseId", warehouseId);
            details.put("procedureArt", output.art());
            details.put("skipped", true);
            details.put("source", "LAVKA_I_UCHET_TOVAR_SAFE");
            if (checkpointArt != null) {
                details.put("checkpointArt", checkpointArt);
            }
            if (output.newArt() != null) {
                details.put("nextArt", output.newArt());
            }
            if (output.problemRecno() != null) {
                details.put("recno", output.problemRecno());
            }
            if (output.problemOperationDate() != null) {
                details.put("operationDate", output.problemOperationDate());
            }
            if (output.problemFormula() != null) {
                details.put("formula", output.problemFormula());
            }
            if (output.problemNumerator() != null) {
                details.put("numerator", output.problemNumerator());
            }
            if (output.problemDenominator() != null) {
                details.put("denominator", output.problemDenominator());
            }
            if (output.problemQuantityBefore() != null) {
                details.put("quantityBefore", output.problemQuantityBefore());
            }
            if (output.problemMovementQuantity() != null) {
                details.put("movementQuantity", output.problemMovementQuantity());
            }
            return new Issue(
                    output.problemCode(),
                    "Folio safely skipped this SKU before an invalid accounting-price division",
                    Map.copyOf(details));
        }
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

    private FolioAccountingPriceRecalculationResponse recordAppliedVerification(
            AppliedProduct applied) {
        FolioAccountingPriceRecalculationResponse response = applied.response();
        if (applied.fingerprint().isEmpty()) return response;
        ProductFingerprint fingerprint = applied.fingerprint().get();
        try {
            if (verificationRecorder.confirmApplied(fingerprint)) return response;
            return withAdditionalWarning(response, snapshotConfirmationIssue(
                    fingerprint.sku(), fingerprint.warehouseId(),
                    "The active product snapshot has no matching SKU row"));
        } catch (RuntimeException error) {
            log.error("[folio.product.snapshot] applied_digest_publish_failed db={} warehouse={} sku={}",
                    fingerprint.sourceDatabase(), fingerprint.warehouseId(),
                    fingerprint.sku(), error);
            return withAdditionalWarning(response, snapshotConfirmationIssue(
                    fingerprint.sku(), fingerprint.warehouseId(), safeMessage(error)));
        }
    }

    private void recordNativeAppliedVerification(
            NativeProgress progress, Optional<ProductFingerprint> fingerprint) {
        if (fingerprint.isEmpty()) return;
        ProductFingerprint value = fingerprint.get();
        try {
            if (verificationRecorder.confirmApplied(value)) return;
            addNativeIssue(progress, snapshotConfirmationIssue(
                    value.sku(), value.warehouseId(),
                    "The active product snapshot has no matching SKU row"));
        } catch (RuntimeException error) {
            log.error("[folio.product.snapshot] native_applied_digest_publish_failed job={} db={} warehouse={} sku={}",
                    progress.jobId, value.sourceDatabase(), value.warehouseId(),
                    value.sku(), error);
            addNativeIssue(progress, snapshotConfirmationIssue(
                    value.sku(), value.warehouseId(), safeMessage(error)));
        }
    }

    private void recordNativeAppliedVerificationsBatch(
            NativeProgress progress,
            List<ProductFingerprint> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) return;
        try {
            Set<String> confirmed = Objects.requireNonNullElse(
                    verificationRecorder.confirmAppliedBatch(fingerprints), Set.of());
            for (ProductFingerprint fingerprint : fingerprints) {
                if (!confirmed.contains(fingerprint.sku())) {
                    addNativeIssue(progress, snapshotConfirmationIssue(
                            fingerprint.sku(), fingerprint.warehouseId(),
                            "The active product snapshot has no matching SKU row"));
                }
            }
        } catch (RuntimeException error) {
            ProductFingerprint first = fingerprints.get(0);
            log.error("[folio.product.snapshot] native_applied_digest_batch_publish_failed job={} db={} warehouse={} skuCount={}",
                    progress.jobId, first.sourceDatabase(), first.warehouseId(),
                    fingerprints.size(), error);
            for (ProductFingerprint fingerprint : fingerprints) {
                addNativeIssue(progress, snapshotConfirmationIssue(
                        fingerprint.sku(), fingerprint.warehouseId(), safeMessage(error)));
            }
        }
    }

    private void recordFailedVerification(String sourceDatabase, int warehouseId,
                                          String sku, String error) {
        if (sourceDatabase == null || sku == null) return;
        try {
            verificationRecorder.markFailed(
                    sourceDatabase, warehouseId, sku, error);
        } catch (RuntimeException publishError) {
            log.error("[folio.product.snapshot] recalculation_failure_publish_failed db={} warehouse={} sku={}",
                    sourceDatabase, warehouseId, sku, publishError);
        }
    }

    private void recordFailedVerificationForCurrentDatabase(int warehouseId,
                                                             String sku,
                                                             String error) {
        try {
            recordFailedVerification(
                    dao.currentDatabaseName(), warehouseId, sku, error);
        } catch (RuntimeException databaseError) {
            log.warn("[folio.product.snapshot] recalculation_failure_database_not_resolved warehouse={} sku={} msg={}",
                    warehouseId, sku, databaseError.getMessage());
        }
    }

    private static FolioAccountingPriceRecalculationResponse withAdditionalWarning(
            FolioAccountingPriceRecalculationResponse response, Issue warning) {
        List<Issue> warnings = new ArrayList<>(response.warnings());
        warnings.add(warning);
        return new FolioAccountingPriceRecalculationResponse(
                response.ok(), response.previewOnly(), response.status(), response.sku(),
                response.requestedWarehouseId(), response.affectedWarehouseIds(),
                response.accountingMethod(), response.eligibleToApply(),
                response.procedureExecuted(), response.priceChanged(), response.before(),
                response.after(), List.copyOf(warnings), response.errors());
    }

    private static Issue snapshotConfirmationIssue(String sku, int warehouseId,
                                                    String reason) {
        return issue(
                "SNAPSHOT_CONFIRMATION_NOT_RECORDED",
                "Folio recalculation committed, but the product snapshot confirmation was not recorded; rerun the snapshot before incremental recalculation",
                "sku", sku, "warehouseId", warehouseId, "reason", reason);
    }

    private static String firstIssueMessage(
            FolioAccountingPriceRecalculationResponse response) {
        Issue issue = !response.errors().isEmpty()
                ? response.errors().get(0)
                : !response.warnings().isEmpty() ? response.warnings().get(0) : null;
        return issue == null ? "Folio recalculation was not applied"
                : issue.code() + ": " + issue.message();
    }

    private static String nativeProblemMessage(NativeFullChunkOutput output) {
        String code = output.problemCode() == null
                ? "FOLIO_NATIVE_RECALCULATION_PROBLEM" : output.problemCode();
        return code + (output.problemDate() == null
                ? "" : " on " + output.problemDate());
    }

    private void validateNativeOutput(int warehouseId,
                                      NativeFullChunkOutput output,
                                      String inputCursor,
                                      int expectedTotalUnits,
                                      int requiredTotalUnits,
                                      int cumulativeUnits,
                                      Set<String> seenCursors,
                                      boolean partialSelection,
                                      boolean requireCompleteOnNull) {
        if (output.transactionCountBefore() < 1
                || output.transactionCountAfter() < 1) {
            throw new NativeOutcomeUnknownException(
                    "LAVKA_I_UCHET_TOVAR_SAFE was not enclosed by the required transaction");
        }
        if (output.returnCode() == null) {
            throw new FolioAccountValidationException(
                    "NATIVE_SAFE_PROCEDURE_UNDOCUMENTED_CODE",
                    "LAVKA_I_UCHET_TOVAR_SAFE returned a null return code");
        }
        if (output.returnCode() == 31) {
            throw new FolioAccountValidationException(
                    "NATIVE_SAFE_PROCEDURE_TRANSACTION_REQUIRED",
                    "LAVKA_I_UCHET_TOVAR_SAFE returned code 31: the required outer transaction was not detected");
        }
        if (output.returnCode() == 32) {
            throw new FolioAccountValidationException(
                    "NATIVE_SAFE_PROCEDURE_UNSUPPORTED_SCOPE_OR_MODE",
                    "LAVKA_I_UCHET_TOVAR_SAFE returned code 32 (UNSUPPORTED_SCOPE_OR_MODE). "
                            + "For an average warehouse with SCLAD_R.N_2=1100 this means the installed safe procedure may still reject uch_nal=1; upgrade the LAVKA safe procedures and rerun preview");
        }
        if (output.returnCode() != 0 && output.returnCode() != 20) {
            throw new FolioAccountValidationException(
                    "NATIVE_SAFE_PROCEDURE_UNDOCUMENTED_CODE",
                    "LAVKA_I_UCHET_TOVAR_SAFE returned code "
                            + output.returnCode() + " (undocumented)");
        }
        if (output.returnCode() == 20
                && (output.problemCode() == null || output.problemCode().isBlank())) {
            throw new IllegalStateException(
                    "LAVKA_I_UCHET_TOVAR_SAFE returned code 20 without diagnostics");
        }
        if (output.returnCode() == 0
                && output.problemCode() != null && !output.problemCode().isBlank()) {
            throw new IllegalStateException(
                    "LAVKA_I_UCHET_TOVAR_SAFE returned diagnostics without stop code");
        }
        if (output.currentUnits() == null || output.totalUnits() == null
                || output.currentUnits() < 0 || output.totalUnits() < 0) {
            throw new IllegalStateException(
                    "LAVKA_I_UCHET_TOVAR_SAFE returned invalid progress counters");
        }
        if (output.resultRowCount() != 0) {
            throw new IllegalStateException(
                    "LAVKA_I_UCHET_TOVAR_SAFE returned an unexpected diagnostic rowset");
        }
        if (output.currentUnits() > 0 && output.art() == null) {
            throw new IllegalStateException(
                    "LAVKA_I_UCHET_TOVAR_SAFE returned work without the last processed art");
        }
        int effectiveTotal;
        long nextCumulative;
        if (partialSelection) {
            if (requiredTotalUnits <= 0) {
                throw new FolioAccountValidationException(
                        "NATIVE_RANGE_TOTAL_UNKNOWN",
                        "Java could not determine the selected batch size before apply");
            }
            // The safe procedure processes exactly one SKU. Its n_tot OUT
            // parameter describes neither the Java selection nor the WordPress
            // campaign and is therefore diagnostic only. Java owns the
            // canonical selected-SKU total and progress counter.
            effectiveTotal = requiredTotalUnits;
            nextCumulative = cumulativeUnits;
        } else {
            int requiredTotal = expectedTotalUnits > 0
                    ? expectedTotalUnits
                    : requiredTotalUnits;
            if (requiredTotal > 0 && output.totalUnits() != requiredTotal) {
                throw new IllegalStateException(
                        "LAVKA_I_UCHET_TOVAR_SAFE changed total progress from " + requiredTotal
                                + " to " + output.totalUnits());
            }
            effectiveTotal = requiredTotal > 0
                    ? requiredTotal
                    : output.totalUnits();
            if (output.currentUnits() > 0 && effectiveTotal == 0) {
                throw new IllegalStateException(
                        "LAVKA_I_UCHET_TOVAR_SAFE returned work without total progress");
            }
            nextCumulative = (long) cumulativeUnits + output.currentUnits();
            if (effectiveTotal > 0 && nextCumulative > effectiveTotal) {
                throw new IllegalStateException(
                        "I_UCHET_TOVAR progress exceeded total work");
            }
        }
        if (output.newArt() != null && output.newArt().equals(inputCursor)) {
            throw new IllegalStateException(
                    "I_UCHET_TOVAR did not advance the continuation cursor");
        }
        if (output.newArt() != null) {
            if (output.currentUnits() <= 0
                    || seenCursors.contains(output.newArt())
                    || output.art() == null
                    || !dao.isImmediateNextArt(
                            warehouseId, output.art(), output.newArt())) {
                throw new IllegalStateException(
                        "I_UCHET_TOVAR returned an invalid continuation cursor");
            }
        } else if (requireCompleteOnNull
                && effectiveTotal > 0 && nextCumulative != effectiveTotal) {
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
        if (!FolioAccountingMode.supportsSafeNativeRecalculation(
                scope.requested().rawAccountingCode())
                || method.calculationMode() != 0
                || method.periodMode() != 0) {
            String recommendation = "Exclude this warehouse from native recalculation until "
                    + "its SCLAD_R.N_2 mode has a separate Paint_Rus golden-master; do not change N_2 automatically";
            throw new FolioAccountingModeUnsupportedException(
                    "ACCOUNTING_NATIVE_METHOD_UNSUPPORTED",
                    scope.requested().rawAccountingCode(), method.name(), recommendation,
                    "Unsupported native accounting mode: SCLAD_R.N_2="
                            + scope.requested().rawAccountingCode()
                            + ", mode=" + method.name()
                            + ", periodMode=" + method.periodMode()
                            + ", includeTax=" + method.includeTax()
                            + ". " + recommendation
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
        FolioAccountingMode.Decoded decoded = FolioAccountingMode.decode(rawCode);
        return new AccountingMethod(
                decoded.rawCode(), decoded.calculationMode(), decoded.periodMode(),
                decoded.includeTax(), decoded.name());
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
        return validateSku(request.sku(), "sku");
    }

    private static String validateSku(String value, String field) {
        String sku = value == null ? null : value.trim();
        if (sku == null || sku.isEmpty()) {
            throw new FolioAccountValidationException(
                    "SKU_REQUIRED", field + " is required");
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
        String applyMode = request.effectiveApplyMode();
        if (!FolioAccountingPriceNativeFullRequest.PREFLIGHT_AND_APPLY.equals(applyMode)
                && !FolioAccountingPriceNativeFullRequest.SAFE_APPLY_ONLY.equals(applyMode)) {
            throw new FolioAccountValidationException(
                    "NATIVE_APPLY_MODE_INVALID",
                    "applyMode must be PREFLIGHT_AND_APPLY or SAFE_APPLY_ONLY");
        }
        if (request.previewOnly() && request.isSafeApplyOnly()) {
            throw new FolioAccountValidationException(
                    "NATIVE_SAFE_APPLY_ONLY_PREVIEW_INVALID",
                    "SAFE_APPLY_ONLY requires previewOnly=false and confirmApply=true");
        }
    }

    private static void validateNativeSelection(
            FolioAccountingPriceNativeFullRequest request) {
        boolean hasList = request.skus() != null && !request.skus().isEmpty();
        boolean hasFrom = request.fromSku() != null && !request.fromSku().isBlank();
        boolean hasTo = request.toSku() != null && !request.toSku().isBlank();
        if (hasList == (hasFrom || hasTo)) {
            throw new FolioAccountValidationException(
                    "NATIVE_SELECTION_INVALID",
                    "Provide either non-empty skus[] or both fromSku and toSku");
        }
        if (hasList) {
            if (request.skus().size() > 500) {
                throw new FolioAccountValidationException(
                        "NATIVE_SELECTION_TOO_LARGE",
                        "native-range is limited to 500 SKU per request");
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String sku : request.skus()) {
                unique.add(validateSku(sku, "skus[]"));
            }
            if (unique.size() != request.skus().size()) {
                throw new FolioAccountValidationException(
                        "NATIVE_SELECTION_DUPLICATE_SKU",
                        "skus[] must not contain duplicate articles");
            }
            return;
        }
        if (!hasFrom || !hasTo) {
            throw new FolioAccountValidationException(
                    "NATIVE_SELECTION_RANGE_INCOMPLETE",
                    "Both fromSku and toSku are required for a range");
        }
        validateSku(request.fromSku(), "fromSku");
        validateSku(request.toSku(), "toSku");
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

    private static void putIfNotNull(Map<String, Object> target,
                                     String key,
                                     Object value) {
        if (value != null) {
            target.put(key, value);
        }
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
                null, null, null, null,
                0, false, List.of(), null, null, null, null
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
                progress.totalUnits, progress.processedSku,
                progress.currentUnits, progress.procedureCurrentUnits,
                progress.procedureTotalUnits, percent, progress.currentArt,
                progress.nextArt, progress.lastCommittedArt,
                progress.checkpointArt, progress.returnCode,
                progress.warningCount, progress.warningsTruncated,
                List.copyOf(progress.warnings), progress.failedChunk,
                progress.errorCode, progress.recommendation, error
        ));
    }

    private void nativeCheckpoint(NativeProgress progress,
                                  String stage,
                                  String sku) {
        int processed = progress.processedSku == null
                ? progress.progressUnits : progress.processedSku;
        runtimeMonitor.checkpoint(
                progress.jobId,
                progress.phase == null ? "UNKNOWN" : progress.phase,
                stage,
                sku,
                processed,
                progress.totalUnits,
                progress.procedureCalls,
                progress.committedChunks);
    }

    private void finishNativeRuntime(NativeProgress progress) {
        FolioAccountingPriceNativeFullStatusResponse current = nativeFullStatus.get();
        String error = current != null && Objects.equals(current.jobId(), progress.jobId)
                ? current.error() : null;
        runtimeMonitor.finish(progress.jobId, progress.status, error);
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
                current.processedSku(), current.currentUnits(),
                current.procedureCurrentUnits(), current.procedureTotalUnits(),
                current.progressPercent(), current.currentArt(), current.nextArt(),
                current.lastCommittedArt(), current.checkpointArt(),
                current.returnCode(), current.warningCount(),
                current.warningsTruncated(), current.warnings(),
                current.failedChunk(), current.errorCode(),
                current.recommendation(), current.error()
        );
    }

    private static void captureNativeFailureMetadata(
            NativeProgress progress, Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof FolioAccountingModeUnsupportedException unsupported) {
                progress.errorCode = unsupported.getCode();
                progress.recommendation = unsupported.recommendation();
                return;
            }
            if (current instanceof FolioAccountValidationException validation) {
                progress.errorCode = validation.getCode();
                progress.recommendation = switch (validation.getCode()) {
                    case "NATIVE_SAFE_PROCEDURE_UNSUPPORTED_SCOPE_OR_MODE" ->
                            "Upgrade dbo.LAVKA_I_UCHET_1_TOVAR_SAFE for N_2=1100, then rerun previewOnly=true; do not run apply before preview succeeds";
                    case "NATIVE_SAFE_PROCEDURE_TRANSACTION_REQUIRED" ->
                            "Do not retry automatically; verify the Java transaction boundary and rerun previewOnly=true";
                    case "NATIVE_SAFE_PROCEDURE_UNDOCUMENTED_CODE" ->
                            "Do not advance the SKU cursor or retry automatically; inspect the safe-procedure OUT diagnostics and T-SQL contract";
                    default -> progress.recommendation;
                };
                return;
            }
            if (current.getCause() == current) return;
            current = current.getCause();
        }
    }

    private static ChunkDiagnostics chunkDiagnostics(
            String inputArt,
            NativeFullChunkOutput output,
            int requiredTotalUnits,
            boolean partialSelection,
            String validationError) {
        Integer canonicalTotal = partialSelection && requiredTotalUnits > 0
                ? requiredTotalUnits : output.totalUnits();
        return new ChunkDiagnostics(
                inputArt,
                output.art(),
                output.newArt(),
                output.returnCode(),
                output.currentUnits(),
                canonicalTotal,
                output.currentUnits(),
                output.totalUnits(),
                output.problemDate(),
                output.problemCode(),
                output.problemArt(),
                output.problemRecno(),
                formatDate(output.problemOperationDate()),
                output.problemFormula(),
                output.problemNumerator(),
                output.problemDenominator(),
                output.problemQuantityBefore(),
                output.problemMovementQuantity(),
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

    private static String safeMessage(Throwable error) {
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
        private final Set<String> reportedProblemKeys = new HashSet<>();
        private String status;
        private String phase;
        private AccountingMethod accountingMethod;
        private int procedureCalls;
        private int preflightChunks;
        private int committedChunks;
        private int progressUnits;
        private int totalUnits;
        private Integer processedSku;
        private Integer currentUnits;
        private Integer procedureCurrentUnits;
        private Integer procedureTotalUnits;
        private String currentArt;
        private String nextArt;
        private String lastCommittedArt;
        private String checkpointArt;
        private Integer returnCode;
        private int warningCount;
        private boolean warningsTruncated;
        private ChunkDiagnostics failedChunk;
        private int divideIsolationProbes;
        private String errorCode;
        private String recommendation;

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

    private record NativeSelectionResolution(
            int requestedCount,
            List<String> existingSkus,
            List<String> missingSkus
    ) {
    }

    private record NativePassResult(
            boolean problemDetected,
            int totalUnits
    ) {
    }

    private record NativeExecutedChunk(
            NativeFullChunkOutput output,
            String processedEndArt,
            Optional<ProductFingerprint> fingerprint
    ) {
    }

    private record NativeSelectionVerification(
            List<ProductFingerprint> fingerprints,
            List<NativeSelectedProductChange> concurrentChanges
    ) {
    }

    private record NativeSelectedProductChange(
            String sku,
            NativeSkuProtectedState before,
            NativeSkuProtectedState after,
            boolean committed
    ) {
    }

    private record AppliedProduct(
            FolioAccountingPriceRecalculationResponse response,
            Optional<ProductFingerprint> fingerprint
    ) {
    }

    private record NativeDivideIsolation(
            List<String> skus,
            boolean exactSkuConfirmed
    ) {
        private NativeDivideIsolation {
            skus = List.copyOf(skus);
            if (skus.isEmpty()) {
                throw new IllegalArgumentException(
                        "Native divide-by-zero isolation range must not be empty");
            }
        }

        private String firstSku() {
            return skus.get(0);
        }

        private String lastSku() {
            return skus.get(skus.size() - 1);
        }
    }

    private enum NativeIsolationProbeResult {
        CLEAN,
        DIVIDE_BY_ZERO
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

        private NativeOutcomeUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
