package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioAccountingPriceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.Capture;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.CaptureConsumer;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MonthlyActivity;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.MovementFact;
import org.example.proect.lavka.dao.folio.FolioProductSnapshotSourceDao.ProductCard;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Change;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.ExistingItem;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Item;
import org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.Publish;
import org.example.proect.lavka.service.folio.FolioProductEconomicsCalculator.Alert;
import org.example.proect.lavka.service.folio.FolioProductEconomicsCalculator.CurrentMetric;
import org.example.proect.lavka.service.folio.FolioProductEconomicsCalculator.MonthlyMetric;
import org.example.proect.lavka.dto.folio.FolioProductSnapshotRefreshRequest;
import org.example.proect.lavka.dto.folio.FolioProductSnapshotStatusResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.example.proect.lavka.dao.wp.FolioProductSnapshotDao.ANALYTICS_SCHEMA_VERSION;

@Slf4j
@Service
public class FolioProductSnapshotService {

    private static final int STAGING_BATCH_SIZE = 300;

    private final FolioProductSnapshotSourceDao sourceDao;
    private final FolioProductSnapshotDao snapshotDao;
    private final FolioAccountingPriceDao accountingPriceDao;
    private final FolioProductEconomicsCalculator economicsCalculator;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    private final int defaultHorizonMonths;
    private final int queryTimeoutSeconds;
    private final int lockTimeoutMs;
    private final int leaseSeconds;
    private final TransactionTemplate sourceTransaction;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<FolioProductSnapshotStatusResponse> live =
            new AtomicReference<>();

    public FolioProductSnapshotService(
            FolioProductSnapshotSourceDao sourceDao,
            FolioProductSnapshotDao snapshotDao,
            FolioAccountingPriceDao accountingPriceDao,
            FolioProductEconomicsCalculator economicsCalculator,
            @Qualifier("folioProductSnapshotExecutor") TaskExecutor executor,
            @Qualifier("folioBalanceClock") Clock clock,
            @Qualifier("mssqlTransactionManager") PlatformTransactionManager txManager,
            @Value("${lavka.folio.product-snapshot.enabled:true}") boolean enabled,
            @Value("${lavka.folio.product-snapshot.default-horizon-months:24}")
            int defaultHorizonMonths,
            @Value("${lavka.folio.product-snapshot.query-timeout-seconds:600}")
            int queryTimeoutSeconds,
            @Value("${lavka.folio.product-snapshot.lock-timeout-ms:5000}") int lockTimeoutMs,
            @Value("${lavka.folio.product-snapshot.lease-seconds:3600}") int leaseSeconds) {
        this.sourceDao = sourceDao;
        this.snapshotDao = snapshotDao;
        this.accountingPriceDao = accountingPriceDao;
        this.economicsCalculator = economicsCalculator;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.defaultHorizonMonths = Math.max(12, Math.min(36, defaultHorizonMonths));
        this.queryTimeoutSeconds = Math.max(30, queryTimeoutSeconds);
        this.lockTimeoutMs = Math.max(0, lockTimeoutMs);
        this.leaseSeconds = Math.max(300, leaseSeconds);
        this.sourceTransaction = new TransactionTemplate(txManager);
        this.sourceTransaction.setReadOnly(true);
        this.sourceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.sourceTransaction.setTimeout(this.queryTimeoutSeconds);
    }

    public FolioProductSnapshotStatusResponse request(
            FolioProductSnapshotRefreshRequest request) {
        if (!enabled) {
            throw new FolioAccountingPriceDisabledException(
                    "PRODUCT_SNAPSHOT_DISABLED", "Folio product snapshot API is disabled");
        }
        if (!running.compareAndSet(false, true)) {
            return withAccepted(status(), false);
        }
        int warehouseId = request.warehouseId();
        int horizonMonths = request.effectiveHorizonMonths(defaultHorizonMonths);
        String sourceDatabase = sourceDao.currentDatabaseName();
        LocalDateTime started = LocalDateTime.now(clock);
        live.set(new FolioProductSnapshotStatusResponse(
                true, true, true, null, "QUEUED", "QUEUED",
                sourceDatabase, warehouseId, horizonMonths, ANALYTICS_SCHEMA_VERSION, started, null,
                0, 0, 0, 0, 0, 0, 0, 0, null,
                null, null, null, null, null));
        try {
            executor.execute(() -> run(sourceDatabase, warehouseId, horizonMonths, started));
            return live.get();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    public FolioProductSnapshotStatusResponse status() {
        FolioProductSnapshotStatusResponse current = live.get();
        if (current != null) return withAccepted(current, false);
        return snapshotDao.latest().map(g -> {
            if ("BUILDING".equalsIgnoreCase(g.status())) {
                return new FolioProductSnapshotStatusResponse(
                        false, false, false, g.id(), "INTERRUPTED",
                        "RECOVERY_REQUIRED", g.sourceDatabase(), g.warehouseId(),
                        g.horizonMonths(), g.analyticsSchemaVersion(), g.startedAt(),
                        g.completedAt(), g.totalProducts(), g.movementRows(),
                        g.movementFactRows(), g.monthlyMetricRows(),
                        g.unverified(), g.dirty(), g.created(), g.removed(),
                        g.warehouseDigest(), "PRODUCT_SNAPSHOT_INTERRUPTED_BY_RESTART",
                        null, null,
                        "No snapshot process is active. Start snapshot refresh again; "
                                + "the previous active snapshot remains available.",
                        "The product snapshot was interrupted by a Java restart"
                );
            }
            return new FolioProductSnapshotStatusResponse(
                    "ACTIVE".equals(g.status()), false, false, g.id(), g.status(),
                    g.status(), g.sourceDatabase(), g.warehouseId(), g.horizonMonths(),
                    g.analyticsSchemaVersion(), g.startedAt(), g.completedAt(),
                    g.totalProducts(), g.movementRows(), g.movementFactRows(),
                    g.monthlyMetricRows(), g.unverified(), g.dirty(), g.created(),
                    g.removed(), g.warehouseDigest(), null, null, null, null, g.error()
            );
        }).orElseGet(() -> new FolioProductSnapshotStatusResponse(
                true, false, false, null, "NOT_READY", "IDLE", null, null,
                null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, null,
                null, null, null, null, null));
    }

    private void run(String sourceDatabase, int warehouseId, int horizonMonths,
                     LocalDateTime startedAt) {
        String owner = UUID.randomUUID().toString();
        String scope = sourceDatabase + ":" + warehouseId;
        Long generationId = null;
        try {
            if (!snapshotDao.tryAcquireLease(scope, owner, leaseSeconds)) {
                throw new IllegalStateException("Another product snapshot owns this warehouse lease");
            }
            int abandoned = snapshotDao.failAbandonedGenerations(
                    sourceDatabase, warehouseId, startedAt);
            snapshotDao.discardStagingForScope(sourceDatabase, warehouseId);
            if (abandoned > 0) {
                log.warn("[folio.product.snapshot] recovered abandoned generations db={} warehouse={} count={}",
                        sourceDatabase, warehouseId, abandoned);
            }
            generationId = snapshotDao.createGeneration(
                    sourceDatabase, warehouseId, horizonMonths, "MANUAL", startedAt);
            setRunning(generationId, "SOURCE_CAPTURE", sourceDatabase,
                    warehouseId, horizonMonths, startedAt);

            LocalDate asOfDate = LocalDate.now(clock);
            LocalDate horizonStart = asOfDate.minusMonths(horizonMonths - 1L)
                    .withDayOfMonth(1);
            LocalDateTime stagedAt = LocalDateTime.now(clock);
            StagingSink staging = new StagingSink(
                    generationId, sourceDatabase, warehouseId, stagedAt,
                    horizonStart, asOfDate, scope, owner);
            Capture capture = sourceTransaction.execute(status -> {
                accountingPriceDao.acquireRecalculationMutex(lockTimeoutMs);
                return sourceDao.capture(warehouseId, horizonStart, asOfDate,
                        queryTimeoutSeconds, staging);
            });
            staging.finish();
            if (capture == null) throw new IllegalStateException("Folio source capture returned no data");
            if (!sourceDatabase.equals(capture.warehouse().databaseName())) {
                throw new IllegalStateException("Folio database changed during snapshot capture");
            }
            if (capture.movementFactRows() != staging.movementFactRows) {
                throw new IllegalStateException("Product movement staging row count mismatch");
            }

            Map<String, ExistingItem> existing = snapshotDao.findExisting(
                    sourceDatabase, warehouseId);
            LocalDateTime calculatedAt = LocalDateTime.now(clock);
            Classification classification = classify(
                    generationId, sourceDatabase, warehouseId,
                    capture.products(), existing, calculatedAt);

            setRunning(generationId, "PUBLISHING", sourceDatabase,
                    warehouseId, horizonMonths, startedAt);
            snapshotDao.publish(new Publish(
                    generationId, sourceDatabase, warehouseId,
                    capture.warehouse().warehouseName(),
                    capture.warehouseDigest(), capture.movementRows(),
                    classification.items(), classification.changes(),
                    staging.movementFactRows, staging.monthlyMetricRows,
                    classification.unverified(), classification.dirty(),
                    classification.created(), classification.removed(),
                    asOfDate, calculatedAt));

            live.set(new FolioProductSnapshotStatusResponse(
                    true, false, false, generationId, "ACTIVE", "COMPLETED",
                    sourceDatabase, warehouseId, horizonMonths, ANALYTICS_SCHEMA_VERSION, startedAt, calculatedAt,
                    capture.products().size(), capture.movementRows(),
                    staging.movementFactRows, staging.monthlyMetricRows,
                    classification.unverified(), classification.dirty(),
                    classification.created(), classification.removed(),
                    capture.warehouseDigest(), null,
                    capture.warehouse().rawAccountingCode().intValue(),
                    FolioAccountingMode.decode(
                            capture.warehouse().rawAccountingCode().intValue()).name(),
                    null, null));
            log.info("[folio.product.snapshot] generation={} db={} warehouse={} products={} movements={} monthly={} unverified={} dirty={} new={} removed={}",
                    generationId, sourceDatabase, warehouseId, capture.products().size(),
                    capture.movementRows(), staging.monthlyMetricRows,
                    classification.unverified(), classification.dirty(),
                    classification.created(), classification.removed());
        } catch (Exception e) {
            LocalDateTime failedAt = LocalDateTime.now(clock);
            ModeFailure modeFailure = modeFailure(e);
            if (generationId != null) {
                try { snapshotDao.failGeneration(generationId, rootMessage(e), failedAt); }
                catch (Exception failure) { e.addSuppressed(failure); }
                try { snapshotDao.discardStaging(generationId); }
                catch (Exception failure) { e.addSuppressed(failure); }
            }
            live.set(new FolioProductSnapshotStatusResponse(
                    false, false, false, generationId, "FAILED", "FAILED",
                    sourceDatabase, warehouseId, horizonMonths, ANALYTICS_SCHEMA_VERSION, startedAt, failedAt,
                    0, 0, 0, 0, 0, 0, 0, 0, null,
                    modeFailure.errorCode(), modeFailure.rawCode(),
                    modeFailure.modeName(), modeFailure.recommendation(),
                    rootMessage(e)));
            log.error("[folio.product.snapshot] failed generation={} db={} warehouse={}",
                    generationId, sourceDatabase, warehouseId, e);
        } finally {
            try { snapshotDao.releaseLease(scope, owner); }
            catch (Exception e) { log.error("[folio.product.snapshot] lease release failed scope={}", scope, e); }
            running.set(false);
        }
    }

    private Classification classify(long generationId, String db, int warehouseId,
                                    List<ProductCard> cards,
                                    Map<String, ExistingItem> existing,
                                    LocalDateTime at) {
        boolean firstBaseline = existing.isEmpty();
        List<Item> items = new ArrayList<>(cards.size() + existing.size());
        List<Change> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int unverified = 0, dirty = 0, created = 0, removed = 0;
        for (ProductCard card : cards) {
            seen.add(card.sku());
            ExistingItem before = existing.get(card.sku());
            String state;
            String change = null;
            if (before == null) {
                state = firstBaseline ? "UNVERIFIED" : "NEW";
                change = firstBaseline ? "BASELINE" : "ADDED";
            } else if (!before.present()) {
                state = "NEW";
                change = "RESTORED";
            } else if (before.appliedDigest() != null
                    && before.appliedDigest().equals(card.sourceDigest())) {
                state = "VERIFIED";
                if (!card.sourceDigest().equals(before.observedDigest())) {
                    change = "CHANGED";
                }
            } else if (!card.sourceDigest().equals(before.observedDigest())) {
                state = "FAILED".equals(before.state()) ? "FAILED" : "DIRTY";
                change = "CHANGED";
            } else {
                state = before.state();
            }
            if ("UNVERIFIED".equals(state)) unverified++;
            if ("DIRTY".equals(state) || "FAILED".equals(state)) dirty++;
            if ("NEW".equals(state)) created++;
            LocalDateTime firstSeen = before == null ? at : before.firstSeenAt();
            items.add(new Item(db, warehouseId, card.sku(), card.productName(),
                    card.currentSupplier(), card.supplierState(),
                    card.sourceDigest(), before == null ? null : before.appliedDigest(),
                    state, true, card.movementCount(), card.minRecno(), card.maxRecno(),
                    card.firstMovementDate(), card.lastMovementDate(), card.priceRuleCount(),
                    firstSeen, at, at, before == null ? null : before.appliedAt(), generationId,
                    "FAILED".equals(state) && before != null ? before.lastError() : null));
            if (change != null) {
                changes.add(new Change(generationId, db, warehouseId, card.sku(), change,
                        before == null ? null : before.observedDigest(),
                        card.sourceDigest(), at));
            }
        }
        for (ExistingItem before : existing.values()) {
            if (seen.contains(before.sku()) || !before.present()) continue;
            removed++;
            items.add(new Item(db, warehouseId, before.sku(), before.productName(),
                    before.currentSupplier(), before.supplierState(), null,
                    before.appliedDigest(), "REMOVED", false, before.movementCount(),
                    before.minRecno(), before.maxRecno(), before.firstMovementDate(),
                    before.lastMovementDate(), before.priceRuleCount(),
                    before.firstSeenAt(), at, at, before.appliedAt(),
                    generationId, before.lastError()));
            changes.add(new Change(generationId, db, warehouseId, before.sku(),
                    "REMOVED", before.observedDigest(), null, at));
        }
        return new Classification(List.copyOf(items), List.copyOf(changes),
                unverified, dirty, created, removed);
    }

    private void setRunning(long generationId, String phase, String db,
                            int warehouseId, int horizonMonths, LocalDateTime started) {
        live.set(new FolioProductSnapshotStatusResponse(
                true, false, true, generationId, "BUILDING", phase, db,
                warehouseId, horizonMonths, ANALYTICS_SCHEMA_VERSION, started, null,
                0, 0, 0, 0, 0, 0, 0, 0, null,
                null, null, null, null, null));
    }

    private static FolioProductSnapshotStatusResponse withAccepted(
            FolioProductSnapshotStatusResponse value, boolean accepted) {
        return new FolioProductSnapshotStatusResponse(
                value.ok(), accepted, value.running(), value.generationId(), value.status(),
                value.phase(), value.sourceDatabase(), value.warehouseId(),
                value.horizonMonths(), value.analyticsSchemaVersion(),
                value.startedAt(), value.completedAt(),
                value.totalProducts(), value.movementRows(), value.movementFactRows(),
                value.monthlyMetricRows(),
                value.unverifiedProducts(), value.dirtyProducts(), value.newProducts(),
                value.removedProducts(), value.warehouseDigest(), value.errorCode(),
                value.accountingRawCode(), value.accountingMode(),
                value.recommendation(), value.error());
    }

    private static ModeFailure modeFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof FolioAccountingModeUnsupportedException unsupported) {
                return new ModeFailure(
                        unsupported.getCode(), unsupported.rawCode(),
                        unsupported.modeName(), unsupported.recommendation());
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return new ModeFailure(null, null, null, null);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String value = current.getMessage();
        return value == null || value.isBlank() ? current.getClass().getSimpleName() : value;
    }

    private record Classification(List<Item> items, List<Change> changes,
                                  int unverified, int dirty, int created, int removed) { }

    private record ModeFailure(String errorCode, Integer rawCode,
                               String modeName, String recommendation) { }

    private final class StagingSink implements CaptureConsumer {
        private final long generationId;
        private final String sourceDatabase;
        private final int warehouseId;
        private final LocalDateTime capturedAt;
        private final LocalDate horizonStart;
        private final LocalDate asOfDate;
        private final String leaseScope;
        private final String leaseOwner;
        private final List<MonthlyMetric> monthly = new ArrayList<>(STAGING_BATCH_SIZE);
        private final List<CurrentMetric> current = new ArrayList<>(STAGING_BATCH_SIZE);
        private final List<Alert> alerts = new ArrayList<>(STAGING_BATCH_SIZE);
        private long movementFactRows;
        private int monthlyMetricRows;
        private int movementRowsSinceHeartbeat;
        private int productsSinceHeartbeat;

        private StagingSink(long generationId, String sourceDatabase, int warehouseId,
                            LocalDateTime capturedAt, LocalDate horizonStart,
                            LocalDate asOfDate, String leaseScope, String leaseOwner) {
            this.generationId = generationId;
            this.sourceDatabase = sourceDatabase;
            this.warehouseId = warehouseId;
            this.capturedAt = capturedAt;
            this.horizonStart = horizonStart;
            this.asOfDate = asOfDate;
            this.leaseScope = leaseScope;
            this.leaseOwner = leaseOwner;
        }

        @Override
        public void acceptMovementBatch(List<MovementFact> rows) {
            snapshotDao.stageMovements(generationId, sourceDatabase, warehouseId,
                    capturedAt, rows);
            movementFactRows += rows.size();
            movementRowsSinceHeartbeat += rows.size();
            if (movementRowsSinceHeartbeat >= 30_000) heartbeat();
        }

        @Override
        public void acceptProductActivity(ProductCard product,
                                          List<MonthlyActivity> rows) {
            var result = economicsCalculator.calculateProduct(
                    product, rows, horizonStart, asOfDate);
            monthly.addAll(result.monthly());
            current.add(result.current());
            alerts.addAll(result.alerts());
            productsSinceHeartbeat++;
            if (productsSinceHeartbeat >= 1_000) heartbeat();
            flushIfNeeded();
        }

        private void flushIfNeeded() {
            if (monthly.size() >= STAGING_BATCH_SIZE) flushMonthly();
            if (current.size() >= STAGING_BATCH_SIZE) flushCurrent();
            if (alerts.size() >= STAGING_BATCH_SIZE) flushAlerts();
        }

        private void finish() {
            flushMonthly();
            flushCurrent();
            flushAlerts();
            heartbeat();
        }

        private void heartbeat() {
            if (!snapshotDao.renewLease(leaseScope, leaseOwner, leaseSeconds)) {
                throw new IllegalStateException("Product snapshot lease was lost during capture");
            }
            snapshotDao.heartbeatGeneration(generationId, LocalDateTime.now(clock));
            Runtime runtime = Runtime.getRuntime();
            long usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long maxMiB = runtime.maxMemory() / (1024 * 1024);
            log.info("[folio.product.snapshot] streaming generation={} warehouse={} movementFacts={} monthly={} heapMiB={}/{}",
                    generationId, warehouseId, movementFactRows,
                    monthlyMetricRows + monthly.size(), usedMiB, maxMiB);
            movementRowsSinceHeartbeat = 0;
            productsSinceHeartbeat = 0;
        }

        private void flushMonthly() {
            if (monthly.isEmpty()) return;
            snapshotDao.stageMonthly(generationId, sourceDatabase, warehouseId,
                    capturedAt, List.copyOf(monthly));
            monthlyMetricRows += monthly.size();
            monthly.clear();
        }

        private void flushCurrent() {
            if (current.isEmpty()) return;
            snapshotDao.stageCurrent(generationId, sourceDatabase, warehouseId,
                    capturedAt, List.copyOf(current));
            current.clear();
        }

        private void flushAlerts() {
            if (alerts.isEmpty()) return;
            snapshotDao.stageAlerts(generationId, sourceDatabase, warehouseId,
                    capturedAt, List.copyOf(alerts));
            alerts.clear();
        }
    }
}
